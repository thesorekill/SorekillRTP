/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.listener;

import com.google.gson.Gson;
import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import net.chumbucket.sorekillrtp.config.PluginConfig;
import net.chumbucket.sorekillrtp.redis.RedisKeys;
import net.chumbucket.sorekillrtp.redis.model.ComputeRequest;
import net.chumbucket.sorekillrtp.redis.model.ComputeResponse;
import net.chumbucket.sorekillrtp.redis.model.PendingTeleport;
import net.chumbucket.sorekillrtp.redis.model.SpawnPoint;
import net.chumbucket.sorekillrtp.util.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class DeathRtpListener implements Listener {

    private final SorekillRTPPlugin plugin;
    private final Random random = new Random();

    private final Map<UUID, World.Environment> lastDeathEnv = new ConcurrentHashMap<>();

    // Precomputed respawn destinations (local) or a remote plan future (remote)
    private final Map<UUID, DeathPlan> plans = new ConcurrentHashMap<>();

    // Warm cache for local smoothness
    private final Map<String, CachedSafe> safeCacheByWorld = new ConcurrentHashMap<>();

    // Cache shared spawnpoint so we don't do Redis I/O on respawn event thread
    private final Map<UUID, CachedSpawnPoint> sharedSpawnCache = new ConcurrentHashMap<>();

    private static final long PLAN_TTL_MS = 15_000L;
    private static final long SAFE_CACHE_TTL_MS = 45_000L;
    private static final long SHARED_SPAWN_CACHE_TTL_MS = 20_000L;

    // How long (ticks) we will wait on respawn for the death-time remote compute to finish
    private static final long REMOTE_AWAIT_MAX_TICKS = 40L; // 2s
    private static final long REMOTE_AWAIT_POLL_TICKS = 2L;

    // If connect fails silently or player never actually leaves, fallback
    private static final long REMOTE_SWITCH_FALLBACK_TICKS = 30L; // 1.5s

    // Visual mask to eliminate "spawn flash" when we need to wait a few ticks
    private static final int RESPAWN_MASK_TICKS = 30; // 1.5s

    public DeathRtpListener(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- config knobs (SAFE DEFAULTS) ----------------

    private PluginConfig.SpawningConfig spawning() {
        return (plugin == null || plugin.cfg() == null) ? null : plugin.cfg().spawning();
    }

    private boolean redisEnabled() {
        return plugin != null && plugin.isRedisEnabled();
    }

    private boolean crossServerRespawnEnabled() {
        PluginConfig.SpawningConfig s = spawning();
        return s != null && s.crossServerRespawn() && redisEnabled();
    }

    private boolean alwaysSpawnAtSpawnEnabled() {
        PluginConfig.SpawningConfig s = spawning();
        return s != null && s.alwaysSpawnAtSpawn();
    }

    private boolean randomTeleportRespawnEnabled() {
        PluginConfig.SpawningConfig s = spawning();
        return s != null && s.randomTeleportRespawn();
    }

    private boolean respectBedSpawnEnabled() {
        PluginConfig.SpawningConfig s = spawning();
        return s == null || s.respectBedSpawn();
    }

    private boolean respectAnchorSpawnEnabled() {
        PluginConfig.SpawningConfig s = spawning();
        return s == null || s.respectAnchorSpawn();
    }

    // ---------------- events ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p == null) return;

        UUID uuid = p.getUniqueId();

        // wipe any previous plan
        plans.remove(uuid);

        if (!p.hasPermission("sorekillrtp.rtp")) {
            lastDeathEnv.remove(uuid);
            sharedSpawnCache.remove(uuid);
            return;
        }

        World w = p.getWorld();
        if (w != null) lastDeathEnv.put(uuid, w.getEnvironment());

        // Cache shared spawn asynchronously (so respawn event doesn't need Redis I/O)
        if (crossServerRespawnEnabled()) {
            cacheSharedSpawnAsync(p);
        } else {
            sharedSpawnCache.remove(uuid);
        }

        // Only prepare an RTP destination if we will actually use it on respawn
        if (randomTeleportRespawnEnabled()) {
            prepareDeathPlanAsync(p, w);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;

        UUID uuid = p.getUniqueId();

        if (!p.hasPermission("sorekillrtp.rtp")) {
            cleanup(uuid);
            return;
        }

        if (alwaysSpawnAtSpawnEnabled()) {
            cleanup(uuid);
            return;
        }

        // Respect local vanilla bed/anchor respawn depending on toggles (PER-TYPE)
        boolean bedRespawn = isBedRespawn(e);
        boolean anchorRespawn = isAnchorRespawn(e);

        if ((bedRespawn && respectBedSpawnEnabled()) || (anchorRespawn && respectAnchorSpawnEnabled())) {
            cleanup(uuid);
            return;
        }

        // If cross-server respawn is enabled, prioritize shared bed/anchor recorded in Redis (cached on death)
        if (crossServerRespawnEnabled()) {
            if (tryRouteToSharedSpawnFromCache(e, p)) {
                plans.remove(uuid);
                sharedSpawnCache.remove(uuid);
                lastDeathEnv.remove(uuid);
                return;
            }
        } else {
            sharedSpawnCache.remove(uuid);
        }

        // If random-teleport-respawn is disabled, do nothing
        if (!randomTeleportRespawnEnabled()) {
            cleanup(uuid);
            return;
        }

        // Seamless RTP:
        // - Use death-time plan if present (local: setRespawnLocation; remote: switch ASAP, never compute here)
        DeathPlan plan = plans.get(uuid);
        if (plan != null && (System.currentTimeMillis() - plan.createdAtMs) <= PLAN_TTL_MS) {

            // LOCAL: set respawn location directly (seamless)
            if (plan.kind == DeathPlanKind.LOCAL && plan.localLocation != null) {
                Location loc = plan.localLocation.clone();
                World w = loc.getWorld();
                if (w != null) {
                    e.setRespawnLocation(clampToWorld(loc, w));
                    refreshCacheAsync(plan.targetWorld);
                    cleanup(uuid);
                    return;
                }
            }

            // REMOTE: connect ASAP after respawn.
            if (plan.kind == DeathPlanKind.REMOTE && plan.remotePendingFuture != null) {
                // Mask the flash while we await (very short)
                applyRespawnMask(p);

                // Fast path: if future already done, connect next tick
                if (plan.remotePendingFuture.isDone()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!p.isOnline()) return;
                        doRemoteSwitchIfReady(p, uuid, plan);
                    });
                    return;
                }

                // Otherwise: wait up to 2s for death-time compute+pending write to finish, then switch.
                awaitThenSwitch(p, uuid, plan);
                return;
            }
        }

        // No plan (or too old): use local warm cache if possible (still seamless)
        String respawnWorld = (e.getRespawnLocation() != null && e.getRespawnLocation().getWorld() != null)
                ? e.getRespawnLocation().getWorld().getName()
                : (p.getWorld() != null ? p.getWorld().getName() : null);

        if (respawnWorld != null) {
            CachedSafe cached = safeCacheByWorld.get(respawnWorld);
            if (cached != null && (System.currentTimeMillis() - cached.atMs) <= SAFE_CACHE_TTL_MS) {
                World w = Bukkit.getWorld(respawnWorld);
                if (w != null && cached.loc != null) {
                    Location loc = cached.loc.clone();
                    loc.setWorld(w);
                    e.setRespawnLocation(clampToWorld(loc, w));
                    refreshCacheAsync(respawnWorld);
                    cleanup(uuid);
                    return;
                }
            }
        }

        // FINAL fallback (rare): choose local-only RTP AFTER respawn (not seamless, but only when we truly couldn't prep)
        // IMPORTANT CHANGE: do NOT do remote compute here (that’s what caused the “spawn then compute” feel).
        World.Environment env = lastDeathEnv.remove(uuid);
        if (env == null) env = World.Environment.NORMAL;

        boolean forceOverworld = (env == World.Environment.NETHER || env == World.Environment.THE_END);

        final String localServer = plugin.cfg().serverName();
        final String worldName = (e.getRespawnLocation() != null && e.getRespawnLocation().getWorld() != null)
                ? e.getRespawnLocation().getWorld().getName()
                : null;

        String chosenWorld;

        if (!forceOverworld && worldName != null && isWorldRtpEnabledOnServer(localServer, worldName)) {
            chosenWorld = worldName;
        } else {
            // NOTE: local-only fallback here
            if (!isOverworldRtpEnabledOnServer(localServer)) {
                cleanup(uuid);
                return;
            }
            chosenWorld = resolveOverworldWorldName(localServer);
            if (chosenWorld == null) {
                cleanup(uuid);
                return;
            }
        }

        final String targetWorld = chosenWorld;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.rtp().startRtp(p, p, localServer, targetWorld, true);
        });

        cleanup(uuid);
    }

    private void cleanup(UUID uuid) {
        if (uuid == null) return;
        lastDeathEnv.remove(uuid);
        plans.remove(uuid);
        sharedSpawnCache.remove(uuid);
    }

    // ---------------- seamless remote await/switch ----------------

    private void awaitThenSwitch(Player p, UUID uuid, DeathPlan plan) {
        if (p == null || !p.isOnline()) return;

        final long startTick = Bukkit.getCurrentTick();
        final BukkitTask[] taskRef = new BukkitTask[1];

        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (taskRef[0] != null && (plan == null || !p.isOnline())) {
                taskRef[0].cancel();
                cleanup(uuid);
                return;
            }

            long elapsed = Bukkit.getCurrentTick() - startTick;
            if (elapsed >= REMOTE_AWAIT_MAX_TICKS) {
                // give up; remove mask naturally, and fall back to local-only (don’t remote compute here)
                if (taskRef[0] != null) taskRef[0].cancel();
                cleanup(uuid);
                return;
            }

            if (plan.remotePendingFuture.isDone()) {
                if (taskRef[0] != null) taskRef[0].cancel();
                doRemoteSwitchIfReady(p, uuid, plan);
            }
        }, 1L, REMOTE_AWAIT_POLL_TICKS);
    }

    private void doRemoteSwitchIfReady(Player p, UUID uuid, DeathPlan plan) {
        if (p == null || !p.isOnline()) return;

        PendingTeleport pending;
        try {
            pending = plan.remotePendingFuture.getNow(null);
        } catch (Throwable t) {
            cleanup(uuid);
            return;
        }

        if (pending == null || pending.server() == null || pending.server().isBlank()) {
            cleanup(uuid);
            return;
        }

        String originServer = plugin.cfg().serverName();
        String pendingKey = plugin.redis().keys().pending(uuid);

        // Switch next tick for stability
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;

            boolean sent = plugin.velocity().connect(p, pending.server());
            if (!sent) {
                // cleanup pending so it doesn't trigger later
                deleteKeyAsync(pendingKey);
                SoundUtil.playConfigured(plugin, p, "sounds.teleport_unsuccessful");
                cleanup(uuid);
                return;
            }

            // fallback check if still on origin after a bit
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                if (!plugin.cfg().serverName().equalsIgnoreCase(originServer)) return;

                // Still on origin; switch likely failed. Clean pending & stop.
                deleteKeyAsync(pendingKey);
                cleanup(uuid);
            }, REMOTE_SWITCH_FALLBACK_TICKS);

            cleanup(uuid);
        });
    }

    private void applyRespawnMask(Player p) {
        if (p == null) return;
        try {
            // small “hide spawn flash” mask; harmless if server-switch succeeds
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, RESPAWN_MASK_TICKS, 0, false, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, RESPAWN_MASK_TICKS, 0, false, false, false));
        } catch (Throwable ignored) {}
    }

    // ---------------- preparation ----------------

    private void prepareDeathPlanAsync(Player p, World deathWorld) {
        if (p == null) return;

        final UUID uuid = p.getUniqueId();

        World.Environment env = (deathWorld != null) ? deathWorld.getEnvironment() : World.Environment.NORMAL;
        boolean forceOverworld = (env == World.Environment.NETHER || env == World.Environment.THE_END);

        final PluginConfig cfg = plugin.cfg();
        final String localServer = cfg.serverName();

        final String preferredWorld = (deathWorld != null) ? deathWorld.getName() : null;

        String chosenServer;
        String chosenWorld;

        if (!forceOverworld && preferredWorld != null && isWorldRtpEnabledOnServer(localServer, preferredWorld)) {
            chosenServer = localServer;
            chosenWorld = preferredWorld;
        } else {
            chosenServer = chooseServerForOverworldRtp();
            if (chosenServer == null) return;

            chosenWorld = resolveOverworldWorldName(chosenServer);
            if (chosenWorld == null) return;
        }

        // If Redis is unavailable, we cannot precompute remote destinations -> collapse to local only.
        if (!redisEnabled() && !localServer.equalsIgnoreCase(chosenServer)) {
            if (isOverworldRtpEnabledOnServer(localServer)) {
                chosenServer = localServer;
                chosenWorld = resolveOverworldWorldName(localServer);
                if (chosenWorld == null) return;
            } else {
                return;
            }
        }

        final String targetServer = chosenServer;
        final String targetWorld = chosenWorld;

        // LOCAL plan
        if (localServer.equalsIgnoreCase(targetServer)) {

            // Warm-cache immediate candidate
            CachedSafe cached = safeCacheByWorld.get(targetWorld);
            if (cached != null && (System.currentTimeMillis() - cached.atMs) <= SAFE_CACHE_TTL_MS) {
                World w = Bukkit.getWorld(targetWorld);
                if (w != null && cached.loc != null) {
                    Location loc = cached.loc.clone();
                    loc.setWorld(w);
                    plans.put(uuid, DeathPlan.local(targetWorld, clampToWorld(loc, w)));
                }
            }

            // Real compute (async chunk load) to refresh plan
            plugin.rtp().finder().findSafeAsync(targetWorld).whenComplete((loc, ex) -> {
                if (ex != null || loc == null) return;

                World w = Bukkit.getWorld(targetWorld);
                if (w == null) return;

                loc.setWorld(w);
                Location clamped = clampToWorld(loc.clone(), w);

                safeCacheByWorld.put(targetWorld, new CachedSafe(clamped.clone(), System.currentTimeMillis()));
                plans.put(uuid, DeathPlan.local(targetWorld, clamped));
            });

            return;
        }

        // REMOTE plan: compute on death AND write pending immediately once we have it
        if (!redisEnabled()) return;

        CompletableFuture<PendingTeleport> pendingFuture = new CompletableFuture<>();
        plans.put(uuid, DeathPlan.remote(targetWorld, pendingFuture));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!redisEnabled()) {
                pendingFuture.complete(null);
                return;
            }

            try {
                String requestId = UUID.randomUUID().toString();
                RedisKeys keys = plugin.redis().keys();
                Gson gson = plugin.redis().gson();

                ComputeRequest req = new ComputeRequest(
                        requestId,
                        uuid,
                        targetServer,
                        targetWorld,
                        System.currentTimeMillis()
                );

                // Publish compute request
                plugin.redis().sync().publish(keys.channelCompute(), gson.toJson(req));

                int ttl = plugin.cfg().rtp().requestTtlSeconds();

                waitForComputeResponse(keys, gson, requestId, ttl).whenComplete((resp, ex) -> {
                    if (ex != null || resp == null || !resp.ok()) {
                        pendingFuture.complete(null);
                        return;
                    }

                    PendingTeleport pending = new PendingTeleport(
                            resp.server(),
                            resp.world(),
                            resp.x(), resp.y(), resp.z(),
                            resp.yaw(), resp.pitch(),
                            System.currentTimeMillis(),
                            0
                    );

                    // Write pending RIGHT NOW (still during death screen most of the time)
                    String pendingKey = keys.pending(uuid);
                    try {
                        plugin.redis().sync().setex(
                                pendingKey,
                                plugin.cfg().rtp().requestTtlSeconds(),
                                gson.toJson(pending)
                        );
                        pendingFuture.complete(pending);
                    } catch (Exception writeFail) {
                        pendingFuture.complete(null);
                    }
                });
            } catch (Exception ignored) {
                pendingFuture.complete(null);
            }
        });
    }

    /**
     * Async polling redis for resp:<requestId> without blocking threads.
     * Deletes resp key once read.
     */
    private CompletableFuture<ComputeResponse> waitForComputeResponse(RedisKeys keys, Gson gson, String requestId, int ttlSeconds) {
        CompletableFuture<ComputeResponse> fut = new CompletableFuture<>();

        final String respKey = keys.resp(requestId);
        final long deadline = System.currentTimeMillis() + (ttlSeconds * 1000L);
        final BukkitTask[] task = new BukkitTask[1];

        long intervalTicks = plugin.cfg().rtp().responsePollIntervalTicks();
        if (intervalTicks < 1) intervalTicks = 1;
        if (intervalTicks > 40) intervalTicks = 40;

        task[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (fut.isDone()) {
                task[0].cancel();
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                task[0].cancel();
                fut.complete(null);
                return;
            }

            try {
                String raw = plugin.redis().sync().get(respKey);
                if (raw != null && !raw.isBlank()) {
                    ComputeResponse resp = gson.fromJson(raw, ComputeResponse.class);
                    try { plugin.redis().sync().del(respKey); } catch (Exception ignored) {}
                    task[0].cancel();
                    fut.complete(resp);
                }
            } catch (Exception ignored) {
                // keep polling
            }
        }, 0L, intervalTicks);

        return fut;
    }

    private void refreshCacheAsync(String worldName) {
        if (worldName == null || worldName.isBlank()) return;
        plugin.rtp().finder().findSafeAsync(worldName).whenComplete((loc, ex) -> {
            if (ex != null || loc == null) return;
            World w = Bukkit.getWorld(worldName);
            if (w == null) return;
            loc.setWorld(w);
            safeCacheByWorld.put(worldName, new CachedSafe(clampToWorld(loc.clone(), w), System.currentTimeMillis()));
        });
    }

    private static Location clampToWorld(Location loc, World w) {
        if (loc == null || w == null) return loc;

        double y = loc.getY();
        double minY = w.getMinHeight() + 1;
        double maxY = w.getMaxHeight() - 2;
        if (y < minY) y = minY;
        if (y > maxY) y = maxY;
        loc.setY(y);

        float pitch = loc.getPitch();
        if (pitch < -90f) pitch = -90f;
        if (pitch > 90f) pitch = 90f;
        loc.setPitch(pitch);

        loc.setWorld(w);
        return loc;
    }

    // ---------------- shared spawn caching ----------------

    private void cacheSharedSpawnAsync(Player p) {
        if (p == null) return;
        if (!crossServerRespawnEnabled()) return;

        final UUID uuid = p.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!crossServerRespawnEnabled()) return;

            RedisKeys keys = plugin.redis().keys();
            Gson gson = plugin.redis().gson();
            String spawnKey = keys.spawn(uuid);

            try {
                String raw = plugin.redis().sync().get(spawnKey);
                if (raw == null || raw.isBlank()) {
                    sharedSpawnCache.remove(uuid);
                    return;
                }
                SpawnPoint sp = gson.fromJson(raw, SpawnPoint.class);
                if (sp == null || sp.server() == null || sp.server().isBlank() || sp.world() == null || sp.world().isBlank()) {
                    sharedSpawnCache.remove(uuid);
                    return;
                }
                sharedSpawnCache.put(uuid, new CachedSpawnPoint(sp, System.currentTimeMillis()));
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Uses cached shared SpawnPoint (fetched on death) to avoid Redis I/O in respawn event.
     *
     * NOTE: For REMOTE shared spawn, we can’t reliably know bed vs anchor type unless SpawnPoint encodes it.
     * To avoid violating respect-* toggles, we only allow remote routing when BOTH toggles are enabled.
     */
    private boolean tryRouteToSharedSpawnFromCache(PlayerRespawnEvent e, Player p) {
        if (e == null || p == null) return false;
        if (!crossServerRespawnEnabled()) return false;

        CachedSpawnPoint cached = sharedSpawnCache.get(p.getUniqueId());
        if (cached == null) return false;

        if ((System.currentTimeMillis() - cached.atMs) > SHARED_SPAWN_CACHE_TTL_MS) {
            sharedSpawnCache.remove(p.getUniqueId());
            return false;
        }

        SpawnPoint sp = cached.sp;
        if (sp == null || sp.server() == null || sp.server().isBlank() || sp.world() == null || sp.world().isBlank()) return false;

        // LOCAL: set respawn location directly
        if (plugin.cfg().serverName().equalsIgnoreCase(sp.server())) {
            World w = Bukkit.getWorld(sp.world());
            if (w == null) return false;

            SpawnBlockInfo info = findSpawnBlockInfo(w, sp.x(), sp.y(), sp.z());
            if (info == null) {
                deleteKeyAsync(plugin.redis().keys().spawn(p.getUniqueId()));
                return false;
            }

            // Respect per-type toggles locally
            if (!respectBedSpawnEnabled() && !info.isAnchor) return false;
            if (!respectAnchorSpawnEnabled() && info.isAnchor) return false;

            Location loc = new Location(w, sp.x(), sp.y(), sp.z(), sp.yaw(), sp.pitch());
            clampToWorld(loc, w);
            e.setRespawnLocation(loc);

            if (info.isAnchor) {
                consumeAnchorChargeAndMaybeClearSpawnAsync(plugin.redis().keys().spawn(p.getUniqueId()), info.anchorBlock);
            }

            return true;
        }

        // REMOTE shared spawn: only allow if BOTH toggles are enabled
        if (!(respectBedSpawnEnabled() && respectAnchorSpawnEnabled())) {
            return false;
        }

        if (!redisEnabled()) return false;

        applyRespawnMask(p);

        final UUID uuid = p.getUniqueId();
        final RedisKeys keys = plugin.redis().keys();
        final Gson gson = plugin.redis().gson();

        final PendingTeleport pending = new PendingTeleport(
                sp.server(),
                sp.world(),
                sp.x(), sp.y(), sp.z(),
                sp.yaw(), sp.pitch(),
                System.currentTimeMillis(),
                0
        );

        final String pendingKey = keys.pending(uuid);
        final String targetServer = sp.server();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!redisEnabled()) return;

            try {
                plugin.redis().sync().setex(
                        pendingKey,
                        plugin.cfg().rtp().requestTtlSeconds(),
                        gson.toJson(pending)
                );
            } catch (Exception ignored) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;

                boolean sent = plugin.velocity().connect(p, targetServer);
                if (!sent) {
                    deleteKeyAsync(pendingKey);
                    SoundUtil.playConfigured(plugin, p, "sounds.teleport_unsuccessful");
                }
            });
        });

        return true;
    }

    // ---------------- world/server selection ----------------

    private String chooseServerForOverworldRtp() {
        PluginConfig cfg = plugin.cfg();
        PluginConfig.RtpConfig rtp = cfg.rtp();

        String local = cfg.serverName();
        if (isOverworldRtpEnabledOnServer(local)) {
            return local;
        }

        if (!redisEnabled()) return null;

        List<String> enabled = rtp.fallbackEnabledServers().stream()
                .filter(this::isOverworldRtpEnabledOnServer)
                .toList();

        if (enabled.isEmpty()) return null;

        return switch (rtp.fallbackMode()) {
            case FIRST -> enabled.get(0);
            case RANDOM -> enabled.get(random.nextInt(enabled.size()));
        };
    }

    private boolean isOverworldRtpEnabledOnServer(String server) {
        if (server == null || server.isBlank()) return false;

        PluginConfig.RtpConfig rtp = plugin.cfg().rtp();
        PluginConfig.ServerRtp srv = rtp.getServer(server).orElse(null);
        if (srv == null || !srv.enabled()) return false;

        String overworld = srv.defaultWorld();
        if (overworld == null || overworld.isBlank()) return false;

        return srv.isWorldEnabled(overworld);
    }

    private boolean isWorldRtpEnabledOnServer(String server, String world) {
        if (server == null || server.isBlank()) return false;
        if (world == null || world.isBlank()) return false;

        PluginConfig.RtpConfig rtp = plugin.cfg().rtp();
        PluginConfig.ServerRtp srv = rtp.getServer(server).orElse(null);
        if (srv == null || !srv.enabled()) return false;

        return srv.isWorldEnabled(world);
    }

    private String resolveOverworldWorldName(String server) {
        PluginConfig.RtpConfig rtp = plugin.cfg().rtp();
        PluginConfig.ServerRtp srv = rtp.getServer(server).orElse(null);
        if (srv == null) return null;

        String world = srv.defaultWorld();
        if (world == null || world.isBlank()) return null;

        if (srv.getWorld(world).isEmpty()) return null;
        if (!srv.isWorldEnabled(world)) return null;

        return world;
    }

    // ---------------- bed/anchor detection (PER-TYPE) ----------------

    private static boolean isBedRespawn(PlayerRespawnEvent e) {
        if (e == null) return false;

        if (callBool(e, "isBedSpawn")) return true;

        try {
            Location loc = e.getRespawnLocation();
            if (loc == null || loc.getWorld() == null) return false;

            World w = loc.getWorld();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();

            return Tag.BEDS.isTagged(w.getBlockAt(x, y, z).getType())
                    || Tag.BEDS.isTagged(w.getBlockAt(x, y - 1, z).getType());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAnchorRespawn(PlayerRespawnEvent e) {
        if (e == null) return false;

        if (callBool(e, "isAnchorSpawn")) return true;
        if (callBool(e, "isRespawnAnchorSpawn")) return true;
        if (callBool(e, "isAnchor")) return true;

        try {
            Location loc = e.getRespawnLocation();
            if (loc == null || loc.getWorld() == null) return false;

            World w = loc.getWorld();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();

            return isChargedAnchorBlock(w.getBlockAt(x, y, z))
                    || isChargedAnchorBlock(w.getBlockAt(x, y - 1, z));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean callBool(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            return (v instanceof Boolean b) && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isChargedAnchorBlock(Block b) {
        try {
            if (b == null) return false;
            if (b.getType() != Material.RESPAWN_ANCHOR) return false;

            BlockData data = b.getBlockData();
            if (data instanceof RespawnAnchor ra) {
                return ra.getCharges() > 0;
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------- shared spawn validation/anchor consume (LOCAL only) ----------------

    private static SpawnBlockInfo findSpawnBlockInfo(World w, double x, double y, double z) {
        if (w == null) return null;

        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);

        if (by < w.getMinHeight()) by = w.getMinHeight();
        if (by > w.getMaxHeight() - 1) by = w.getMaxHeight() - 1;

        for (int dy = -1; dy <= 1; dy++) {
            int yy = by + dy;
            if (yy < w.getMinHeight() || yy > w.getMaxHeight() - 1) continue;

            Block b = w.getBlockAt(bx, yy, bz);
            Material type = b.getType();

            if (Tag.BEDS.isTagged(type)) {
                return new SpawnBlockInfo(false, null);
            }

            if (type == Material.RESPAWN_ANCHOR) {
                BlockData data = b.getBlockData();
                if (data instanceof RespawnAnchor ra) {
                    if (ra.getCharges() <= 0) return null;
                    return new SpawnBlockInfo(true, b);
                }
                return new SpawnBlockInfo(true, b);
            }
        }

        return null;
    }

    private void consumeAnchorChargeAndMaybeClearSpawnAsync(String spawnKey, Block anchorBlock) {
        if (spawnKey == null || spawnKey.isBlank()) return;
        if (anchorBlock == null) return;

        try {
            if (anchorBlock.getType() != Material.RESPAWN_ANCHOR) return;

            BlockData bd = anchorBlock.getBlockData();
            if (!(bd instanceof RespawnAnchor ra)) return;

            int charges = ra.getCharges();
            if (charges <= 0) {
                deleteKeyAsync(spawnKey);
                return;
            }

            int next = charges - 1;
            ra.setCharges(next);
            anchorBlock.setBlockData(ra, true);

            if (next <= 0) {
                deleteKeyAsync(spawnKey);
            }
        } catch (Throwable ignored) {
        }
    }

    private void deleteKeyAsync(String key) {
        if (key == null || key.isBlank()) return;
        if (!redisEnabled()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!redisEnabled()) return;
            try { plugin.redis().sync().del(key); } catch (Exception ignored) {}
        });
    }

    // ---------------- nested types ----------------

    private enum DeathPlanKind { LOCAL, REMOTE }

    private static final class DeathPlan {
        final DeathPlanKind kind;
        final String targetWorld;
        final Location localLocation;
        final CompletableFuture<PendingTeleport> remotePendingFuture;
        final long createdAtMs;

        private DeathPlan(DeathPlanKind kind,
                        String targetWorld,
                        Location localLocation,
                        CompletableFuture<PendingTeleport> remotePendingFuture,
                        long createdAtMs) {
            this.kind = kind;
            this.targetWorld = targetWorld;
            this.localLocation = localLocation;
            this.remotePendingFuture = remotePendingFuture;
            this.createdAtMs = createdAtMs;
        }

        static DeathPlan local(String world, Location loc) {
            return new DeathPlan(
                    DeathPlanKind.LOCAL,
                    world,
                    loc,
                    null,
                    System.currentTimeMillis()
            );
        }

        static DeathPlan remote(String world, CompletableFuture<PendingTeleport> pendingFuture) {
            return new DeathPlan(
                    DeathPlanKind.REMOTE,
                    world,
                    null,
                    pendingFuture,
                    System.currentTimeMillis()
            );
        }
    }

    private static final class SpawnBlockInfo {
        final boolean isAnchor;
        final Block anchorBlock;

        SpawnBlockInfo(boolean isAnchor, Block anchorBlock) {
            this.isAnchor = isAnchor;
            this.anchorBlock = anchorBlock;
        }
    }

    private static final class CachedSafe {
        final Location loc;
        final long atMs;

        CachedSafe(Location loc, long atMs) {
            this.loc = loc;
            this.atMs = atMs;
        }
    }

    private static final class CachedSpawnPoint {
        final SpawnPoint sp;
        final long atMs;

        CachedSpawnPoint(SpawnPoint sp, long atMs) {
            this.sp = sp;
            this.atMs = atMs;
        }
    }
}
