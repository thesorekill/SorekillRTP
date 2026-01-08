/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import net.chumbucket.sorekillrtp.redis.model.ComputeRequest;
import net.chumbucket.sorekillrtp.redis.model.ComputeResponse;
import net.chumbucket.sorekillrtp.rtp.SafeLocationFinder;
import org.bukkit.Bukkit;
import redis.clients.jedis.*;
import redis.clients.jedis.params.SetParams;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RedisManager {

    private final SorekillRTPPlugin plugin;
    private final RedisKeys keys;
    private final Gson gson = new GsonBuilder().create();

    private volatile JedisPool pool;
    private volatile Thread subscriberThread;

    // robust running flag for plugin.isRedisEnabled() + guards
    private final AtomicBoolean running = new AtomicBoolean(false);

    // keep reference so stop() can unsubscribe cleanly
    private volatile JedisPubSub activeSub;

    private final RedisOps ops = new RedisOps() {

        private JedisPool requirePool() {
            if (!isRunning()) throw new IllegalStateException("Redis is not running");
            JedisPool p = pool;
            if (p == null) throw new IllegalStateException("Redis pool is not available");
            return p;
        }

        @Override
        public String get(String key) {
            try (Jedis j = requirePool().getResource()) {
                return j.get(key);
            }
        }

        @Override
        public void setex(String key, int seconds, String value) {
            try (Jedis j = requirePool().getResource()) {
                j.set(key, value, SetParams.setParams().ex(seconds));
            }
        }

        @Override
        public void del(String key) {
            try (Jedis j = requirePool().getResource()) {
                j.del(key);
            }
        }

        @Override
        public void publish(String channel, String message) {
            try (Jedis j = requirePool().getResource()) {
                j.publish(channel, message);
            }
        }

        @Override
        public long ttl(String key) {
            try (Jedis j = requirePool().getResource()) {
                return j.ttl(key);
            }
        }
    };

    public RedisManager(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
        this.keys = new RedisKeys(plugin.cfg().redis());
    }

    public RedisKeys keys() { return keys; }
    public Gson gson() { return gson; }

    public RedisOps sync() {
        return ops;
    }

    /** True only when start() completed and stop() has not been called. */
    public boolean isRunning() {
        return running.get();
    }

    /** Safe to call multiple times; only the first call will start. */
    public synchronized void start() {
        if (running.get()) {
            plugin.getLogger().warning("RedisManager.start() called while already running.");
            return;
        }

        JedisPoolConfig pc = new JedisPoolConfig();
        pc.setMaxTotal(16);
        pc.setMaxIdle(16);
        pc.setMinIdle(0);

        // reliability: validate connections
        pc.setTestOnBorrow(true);
        pc.setTestWhileIdle(true);

        var rc = plugin.cfg().redis();

        String host = rc.host();
        int port = rc.port();
        String password = rc.password();
        boolean ssl = rc.ssl();

        int timeoutMs = rc.timeoutMs();
        int db = rc.database();

        JedisPool newPool;
        if (password != null && !password.isBlank()) {
            newPool = new JedisPool(pc, host, port, timeoutMs, timeoutMs, password, db, null, ssl);
        } else {
            newPool = new JedisPool(pc, host, port, timeoutMs, timeoutMs, null, db, null, ssl);
        }

        this.pool = newPool;
        running.set(true);

        Thread t = new Thread(this::runSubscriber, "SorekillRTP-RedisSub");
        t.setDaemon(true);
        this.subscriberThread = t;
        t.start();

        plugin.getLogger().info("Redis connected + subscribed (" + keys.channelCompute() + ")");
    }

    private void runSubscriber() {
        long backoffMs = 1000;

        while (running.get()) {
            JedisPool p = pool;
            if (p == null) break;

            try (Jedis j = p.getResource()) {
                JedisPubSub sub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        if (!keys.channelCompute().equals(channel)) return;
                        if (!running.get()) return;

                        ComputeRequest req;
                        try {
                            req = gson.fromJson(message, ComputeRequest.class);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Bad compute request JSON: " + e.getMessage());
                            return;
                        }

                        if (!plugin.cfg().serverName().equalsIgnoreCase(req.targetServer())) return;

                        // Bukkit API must be main thread
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!running.get()) return;

                            SafeLocationFinder finder = plugin.rtp().finder();
                            finder.findSafeAsync(req.world()).whenComplete((loc, ex) -> {
                                if (!running.get()) return;

                                ComputeResponse resp;
                                if (ex != null || loc == null) {
                                    resp = new ComputeResponse(
                                            req.requestId(),
                                            false,
                                            plugin.cfg().serverName(),
                                            req.world(),
                                            0, 0, 0, 0f, 0f,
                                            "no_safe_location"
                                    );
                                } else {
                                    resp = new ComputeResponse(
                                            req.requestId(),
                                            true,
                                            plugin.cfg().serverName(),
                                            req.world(),
                                            loc.getX(), loc.getY(), loc.getZ(),
                                            loc.getYaw(), loc.getPitch(),
                                            null
                                    );
                                }

                                String respKey = keys.resp(req.requestId());
                                String json = gson.toJson(resp);

                                int ttl = plugin.cfg().rtp().requestTtlSeconds();
                                try {
                                    ops.setex(respKey, ttl, json);
                                } catch (Exception e2) {
                                    plugin.getLogger().warning("Failed to write compute response: " + e2.getMessage());
                                }
                            });
                        });
                    }
                };

                activeSub = sub;
                backoffMs = 1000; // reset after successful connect

                // blocks until unsubscribe() or connection drops
                j.subscribe(sub, keys.channelCompute());
            } catch (Exception e) {
                activeSub = null;
                if (!running.get()) break;

                plugin.getLogger().warning("Redis subscribe loop error: " + e.getMessage());

                try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
                backoffMs = Math.min(15000, backoffMs * 2);
            }
        }
    }

    /** Safe to call multiple times. */
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return; // already stopped
        }

        // Unsubscribe first so subscribe() unblocks
        try {
            JedisPubSub sub = activeSub;
            if (sub != null) {
                try { sub.unsubscribe(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Interrupt thread as backup
        Thread t = subscriberThread;
        if (t != null) {
            try { t.interrupt(); } catch (Exception ignored) {}
        }

        // Close pool
        JedisPool p = pool;
        pool = null;

        try {
            if (p != null) p.close();
        } catch (Exception ignored) {}

        activeSub = null;
        subscriberThread = null;
    }
}
