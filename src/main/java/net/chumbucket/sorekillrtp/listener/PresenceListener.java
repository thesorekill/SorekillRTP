/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.listener;

import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import net.chumbucket.sorekillrtp.redis.RedisKeys;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PresenceListener implements Listener {

    private final SorekillRTPPlugin plugin;
    private final int ttlSeconds;

    private BukkitTask heartbeatTask;

    public PresenceListener(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
        this.ttlSeconds = 90;

        if (!plugin.isRedisEnabled() || plugin.redis() == null) {
            this.heartbeatTask = null;
            return;
        }

        this.heartbeatTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::heartbeatTick,
                20L * 30,
                20L * 30
        );
    }

    /** Call from plugin onDisable/reload before unregistering listeners. */
    public void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        setPresenceAsync(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        deletePresenceAsync(p.getUniqueId());
    }

    private void heartbeatTick() {
        // If Redis is disabled/unavailable, do nothing.
        if (!plugin.isRedisEnabled() || plugin.redis() == null) return;

        // Snapshot UUIDs on main thread.
        List<UUID> uuids = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null) uuids.add(p.getUniqueId());
        }

        if (uuids.isEmpty()) return;

        // Write to Redis async.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!plugin.isRedisEnabled() || plugin.redis() == null) return;

            RedisKeys keys;
            try {
                keys = plugin.redis().keys();
            } catch (Exception ignored) {
                return;
            }

            String serverName = plugin.cfg().serverName();

            for (UUID uuid : uuids) {
                try {
                    plugin.redis().sync().setex(keys.presence(uuid), ttlSeconds, serverName);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void setPresenceAsync(UUID uuid) {
        if (uuid == null) return;
        if (!plugin.isRedisEnabled() || plugin.redis() == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!plugin.isRedisEnabled() || plugin.redis() == null) return;

            try {
                RedisKeys keys = plugin.redis().keys();
                plugin.redis().sync().setex(keys.presence(uuid), ttlSeconds, plugin.cfg().serverName());
            } catch (Exception ignored) {
            }
        });
    }

    private void deletePresenceAsync(UUID uuid) {
        if (uuid == null) return;
        if (!plugin.isRedisEnabled() || plugin.redis() == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!plugin.isRedisEnabled() || plugin.redis() == null) return;

            try {
                RedisKeys keys = plugin.redis().keys();
                plugin.redis().sync().del(keys.presence(uuid));
            } catch (Exception ignored) {
            }
        });
    }
}
