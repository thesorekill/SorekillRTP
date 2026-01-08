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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finalizes cross-server teleports after a Velocity switch by reading a PendingTeleport from Redis.
 *
 * Seamless mode:
 * - On join, if a pending teleport exists for THIS server, temporarily freeze + blind the player
 *   until chunk preload + teleport completes, then restore.
 *
 * Hardened + knob-aware:
 * - HARD GUARD when redis is disabled/unavailable -> no-op
 * - ALL Redis I/O off main thread
 * - ALL Bukkit/world/block/teleport operations on main thread
 * - Shared-spawn (bed/anchor) validation only when spawning.cross-server-respawn is enabled
 * - Shared-spawn routing respects respect-bed-spawn / respect-anchor-spawn
 */
public final class JoinFinalizeListener implements Listener {

    private final SorekillRTPPlugin plugin;

    // Freeze state per player (so we can restore even if something fails)
    private final Map<UUID, FreezeState> frozen = new ConcurrentHashMap<>();

    // How long we keep the “blind/freeze” safety net if something goes wrong
    private static final long FREEZE_FAILSAFE_TICKS = 80L; // 4s

    public JoinFinalizeListener(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- knobs ----------

    private boolean redisAvailable() {
        return plugin != null && plugin.isRedisEnabled() && plugin.redis() != null;
    }

    private PluginConfig.SpawningConfig spawning() {
        return (plugin == null || plugin.cfg() == null) ? null : plugin.cfg().spawning();
    }

    /** Whether shared-spawn routing (spawn:<uuid>) should be respected at all. */
    private boolean sharedSpawnEnabled() {
        PluginConfig.SpawningConfig s = spawning();
        return s != null && s.crossServerRespawn() && redisAvailable();
    }

    private boolean respectBedSpawn() {
        PluginConfig.SpawningConfig s = spawning();
        return s == null || s.respectBedSpawn(); // default true
    }

    private boolean respectAnchorSpawn() {
        PluginConfig.SpawningConfig s = spawning();
        return s == null || s.respectAnchorSpawn(); // default true
    }

    // ---------- event ----------

    /**
     * LOWEST so we start the finalize pipeline as early as possible on join,
     * reducing visible “spawn flash”.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (p == null) return;

        if (!redisAvailable()) return;

        final UUID uuid = p.getUniqueId();

        final RedisKeys keys;
        final Gson gson;
        try {
            keys = plugin.redis().keys();
            gson = plugin.redis().gson();
        } catch (Exception ignored) {
            return;
        }

        final String pendingKey = keys.pending(uuid);

        // Redis read async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!redisAvailable()) return;

            String raw;
            try {
                raw = plugin.redis().sync().get(pendingKey);
            } catch (Exception ignored) {
                return;
            }

            if (raw == null || raw.isBlank()) return;

            final PendingTeleport pending;
            try {
                pending = gson.fromJson(raw, PendingTeleport.class);
            } catch (Exception ex) {
                try { plugin.redis().sync().del(pendingKey); } catch (Exception ignored) {}
                return;
            }

            // Main thread finalize
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player live = Bukkit.getPlayer(uuid);
                finalizePendingStart(live, pendingKey, pending);
            });
        });
    }

    private void finalizePendingStart(Player p, String pendingKey, PendingTeleport pending) {
        if (p == null || !p.isOnline() || pending == null) return;
        if (!redisAvailable()) return;

        if (pending.server() == null || pending.server().isBlank()) return;
        if (pending.world() == null || pending.world().isBlank()) return;

        // Only finalize if this is the target server
        if (!plugin.cfg().serverName().equalsIgnoreCase(pending.server())) return;

        // Stale guard
        long atMs = pending.atMs();
        long ttlMs = plugin.cfg().rtp().requestTtlSeconds() * 1000L;
        if (atMs > 0 && (System.currentTimeMillis() - atMs) > ttlMs) {
            deleteKeyAsync(pendingKey);
            return;
        }

        World w = Bukkit.getWorld(pending.world());
        if (w == null) {
            plugin.messages().send(p, "errors.unknown-world",
                    Map.of("server", pending.server(), "world", pending.world()));
            SoundUtil.playConfigured(plugin, p, "sounds.teleport_unsuccessful");
            bumpOrDeletePendingAsync(pendingKey, pending);
            return;
        }

        // --- Seamless: freeze immediately, then proceed ---
        freezePlayer(p);

        // failsafe: if anything hangs, restore after a few seconds
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player live = Bukkit.getPlayer(p.getUniqueId());
            if (live != null && live.isOnline()) {
                unfreezePlayer(live);
            }
        }, FREEZE_FAILSAFE_TICKS);

        // If shared-spawn feature is disabled, finalize normally.
        if (!sharedSpawnEnabled()) {
            finalizeTeleport(p, pendingKey, pending, false);
            return;
        }

        // Only do shared-spawn validation if destination plausibly bed/anchor
        boolean destLooksLikeBedOrAnchor = looksLikeBedOrAnchorAt(w, pending);
        if (!destLooksLikeBedOrAnchor) {
            finalizeTeleport(p, pendingKey, pending, false);
            return;
        }

        // Determine if this pending corresponds to spawn:<uuid> (async Redis)
        final UUID uuid = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean isSharedSpawnRoute = false;

            if (sharedSpawnEnabled()) {
                try {
                    RedisKeys keys = plugin.redis().keys();
                    Gson gson = plugin.redis().gson();

                    String spawnKey = keys.spawn(uuid);
                    String raw = plugin.redis().sync().get(spawnKey);
                    if (raw != null && !raw.isBlank()) {
                        SpawnPoint sp = gson.fromJson(raw, SpawnPoint.class);
                        if (sp != null
                                && sp.server() != null
                                && sp.world() != null
                                && sp.server().equalsIgnoreCase(pending.server())
                                && sp.world().equalsIgnoreCase(pending.world())) {

                            // Tight match
                            double dx = Math.abs(sp.x() - pending.x());
                            double dy = Math.abs(sp.y() - pending.y());
                            double dz = Math.abs(sp.z() - pending.z());
                            isSharedSpawnRoute = (dx <= 0.75 && dy <= 1.75 && dz <= 0.75);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            final boolean finalShared = isSharedSpawnRoute;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player live = Bukkit.getPlayer(uuid);
                if (live == null || !live.isOnline()) return;
                finalizeTeleport(live, pendingKey, pending, finalShared);
            });
        });
    }

    private void finalizeTeleport(Player p, String pendingKey, PendingTeleport pending, boolean isSharedSpawnRoute) {
        if (p == null || !p.isOnline() || pending == null) return;

        World w = Bukkit.getWorld(pending.world());
        if (w == null) {
            plugin.messages().send(p, "errors.unknown-world",
                    Map.of("server", pending.server(), "world", pending.world()));
            SoundUtil.playConfigured(plugin, p, "sounds.teleport_unsuccessful");
            bumpOrDeletePendingAsync(pendingKey, pending);
            unfreezePlayer(p);
            return;
        }

        if (isSharedSpawnRoute) {
            // Validate spawn block + respect per-type toggles
            SpawnType type = classifyStoredSpawn(w, pending);

            if (type == SpawnType.NONE) {
                deleteKeyAsync(plugin.redis().keys().spawn(p.getUniqueId()));
                deleteKeyAsync(pendingKey);

                plugin.messages().send(p, "errors.no-safe-location");
                SoundUtil.playConfigured(plugin, p, "sounds.teleport_unsuccessful");
                unfreezePlayer(p);
                return;
            }

            if (type == SpawnType.BED && !respectBedSpawn()) {
                deleteKeyAsync(plugin.redis().keys().spawn(p.getUniqueId()));
                deleteKeyAsync(pendingKey);
                unfreezePlayer(p);
                return;
            }

            if (type == SpawnType.ANCHOR && !respectAnchorSpawn()) {
                deleteKeyAsync(plugin.redis().keys().spawn(p.getUniqueId()));
                deleteKeyAsync(pendingKey);
                unfreezePlayer(p);
                return;
            }
        }

        // Clamp Y
        double y = pending.y();
        double minY = w.getMinHeight() + 1;
        double maxY = w.getMaxHeight() - 2;
        if (y < minY) y = minY;
        if (y > maxY) y = maxY;

        // Clamp pitch
        float yaw = pending.yaw();
        float pitch = pending.pitch();
        if (pitch < -90f) pitch = -90f;
        if (pitch > 90f) pitch = 90f;

        Location loc = new Location(w, pending.x(), y, pending.z(), yaw, pitch);

        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;

        w.getChunkAtAsync(cx, cz).whenComplete((chunk, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;

            if (ex != null) {
                plugin.messages().send(p, "errors.no-safe-location");
                SoundUtil.playConfigured(plugin, p, "sounds.teleport_unsuccessful");
                bumpOrDeletePendingAsync(pendingKey, pending);
                unfreezePlayer(p);
                return;
            }

            p.teleportAsync(loc).whenComplete((ok, tex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;

                if (tex != null || ok == null || !ok) {
                    plugin.messages().send(p, "errors.no-safe-location");
                    SoundUtil.playConfigured(plugin, p, "sounds.teleport_unsuccessful");
                    bumpOrDeletePendingAsync(pendingKey, pending);
                    unfreezePlayer(p);
                    return;
                }

                // Anchor consume (shared-spawn route only)
                if (isSharedSpawnRoute && respectAnchorSpawn()) {
                    consumeAnchorChargeIfPresent(p, pending);
                }

                // Success: delete pending
                deleteKeyAsync(pendingKey);

                // Restore immediately BEFORE messages/sounds (so player can move)
                unfreezePlayer(p);

                plugin.messages().send(p, "success.teleported", Map.of("world", pending.world()));
                SoundUtil.playConfigured(plugin, p, "sounds.teleport_successful");
            }));
        }));
    }

    // ---------------- seamless freeze helpers ----------------

    private void freezePlayer(Player p) {
        if (p == null || !p.isOnline()) return;

        UUID uuid = p.getUniqueId();
        if (frozen.containsKey(uuid)) return; // already frozen

        FreezeState st = new FreezeState(
                p.getWalkSpeed(),
                p.getFlySpeed(),
                p.isFlying(),
                p.getAllowFlight()
        );
        frozen.put(uuid, st);

        try { p.setInvulnerable(true); } catch (Throwable ignored) {}
        try { p.setAllowFlight(true); } catch (Throwable ignored) {}
        try { p.setFlying(true); } catch (Throwable ignored) {}
        try { p.setWalkSpeed(0f); } catch (Throwable ignored) {}
        try { p.setFlySpeed(0f); } catch (Throwable ignored) {}

        // Heavy “hide flash”: short blindness until we unfreeze
        try {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 10, 1, true, false, false));
        } catch (Throwable ignored) {}
    }

    private void unfreezePlayer(Player p) {
        if (p == null || !p.isOnline()) return;

        UUID uuid = p.getUniqueId();
        FreezeState st = frozen.remove(uuid);
        if (st == null) return;

        try { p.removePotionEffect(PotionEffectType.BLINDNESS); } catch (Throwable ignored) {}

        try { p.setWalkSpeed(st.walkSpeed); } catch (Throwable ignored) {}
        try { p.setFlySpeed(st.flySpeed); } catch (Throwable ignored) {}
        try { p.setAllowFlight(st.allowFlight); } catch (Throwable ignored) {}
        try { p.setFlying(st.flying); } catch (Throwable ignored) {}

        try { p.setInvulnerable(false); } catch (Throwable ignored) {}
    }

    private static final class FreezeState {
        final float walkSpeed;
        final float flySpeed;
        final boolean flying;
        final boolean allowFlight;

        FreezeState(float walkSpeed, float flySpeed, boolean flying, boolean allowFlight) {
            this.walkSpeed = walkSpeed;
            this.flySpeed = flySpeed;
            this.flying = flying;
            this.allowFlight = allowFlight;
        }
    }

    // ---------------- shared-spawn detection/validation ----------------

    private static boolean looksLikeBedOrAnchorAt(World w, PendingTeleport pending) {
        if (w == null || pending == null) return false;

        int bx = (int) Math.floor(pending.x());
        int by = (int) Math.floor(pending.y());
        int bz = (int) Math.floor(pending.z());

        if (by < w.getMinHeight()) by = w.getMinHeight();
        if (by > w.getMaxHeight() - 1) by = w.getMaxHeight() - 1;

        for (int dy = -1; dy <= 1; dy++) {
            int yy = by + dy;
            if (yy < w.getMinHeight() || yy > w.getMaxHeight() - 1) continue;

            Block b = w.getBlockAt(bx, yy, bz);
            Material type = b.getType();

            if (Tag.BEDS.isTagged(type)) return true;
            if (type == Material.RESPAWN_ANCHOR) return true;
        }

        return false;
    }

    private enum SpawnType { NONE, BED, ANCHOR }

    private static SpawnType classifyStoredSpawn(World w, PendingTeleport pending) {
        if (w == null || pending == null) return SpawnType.NONE;

        int bx = (int) Math.floor(pending.x());
        int by = (int) Math.floor(pending.y());
        int bz = (int) Math.floor(pending.z());

        if (by < w.getMinHeight()) by = w.getMinHeight();
        if (by > w.getMaxHeight() - 1) by = w.getMaxHeight() - 1;

        for (int dy = -1; dy <= 1; dy++) {
            int yy = by + dy;
            if (yy < w.getMinHeight() || yy > w.getMaxHeight() - 1) continue;

            Block b = w.getBlockAt(bx, yy, bz);
            Material type = b.getType();

            if (Tag.BEDS.isTagged(type)) return SpawnType.BED;

            if (type == Material.RESPAWN_ANCHOR) {
                BlockData data = b.getBlockData();
                if (data instanceof RespawnAnchor ra) {
                    return ra.getCharges() > 0 ? SpawnType.ANCHOR : SpawnType.NONE;
                }
                return SpawnType.ANCHOR;
            }
        }

        return SpawnType.NONE;
    }

    private void consumeAnchorChargeIfPresent(Player p, PendingTeleport pending) {
        try {
            if (p == null || pending == null) return;
            if (!redisAvailable()) return;

            World w = Bukkit.getWorld(pending.world());
            if (w == null) return;

            int bx = (int) Math.floor(pending.x());
            int by = (int) Math.floor(pending.y());
            int bz = (int) Math.floor(pending.z());

            if (by < w.getMinHeight()) by = w.getMinHeight();
            if (by > w.getMaxHeight() - 1) by = w.getMaxHeight() - 1;

            Block anchor = null;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = by + dy;
                if (yy < w.getMinHeight() || yy > w.getMaxHeight() - 1) continue;
                Block b = w.getBlockAt(bx, yy, bz);
                if (b.getType() == Material.RESPAWN_ANCHOR) {
                    anchor = b;
                    break;
                }
            }
            if (anchor == null) return;

            BlockData bd = anchor.getBlockData();
            if (!(bd instanceof RespawnAnchor ra)) return;

            int charges = ra.getCharges();
            if (charges <= 0) {
                deleteKeyAsync(plugin.redis().keys().spawn(p.getUniqueId()));
                return;
            }

            int next = charges - 1;
            ra.setCharges(next);
            anchor.setBlockData(ra, true);

            if (next <= 0) {
                deleteKeyAsync(plugin.redis().keys().spawn(p.getUniqueId()));
            }
        } catch (Exception ignored) {
        }
    }

    // ----- async redis helpers -----

    private void deleteKeyAsync(String key) {
        if (key == null || key.isBlank()) return;
        if (!redisAvailable()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!redisAvailable()) return;
                plugin.redis().sync().del(key);
            } catch (Exception ignored) {
            }
        });
    }

    private void bumpOrDeletePendingAsync(String pendingKey, PendingTeleport pending) {
        if (pendingKey == null || pending == null) return;
        if (!redisAvailable()) return;

        int maxFinalizeAttempts = plugin.cfg().rtp().pendingMaxFinalizeAttempts();
        int next = Math.max(0, pending.attempts()) + 1;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!redisAvailable()) return;

                if (next >= maxFinalizeAttempts) {
                    plugin.redis().sync().del(pendingKey);
                    return;
                }

                PendingTeleport bumped = new PendingTeleport(
                        pending.server(),
                        pending.world(),
                        pending.x(), pending.y(), pending.z(),
                        pending.yaw(), pending.pitch(),
                        pending.atMs(),
                        next
                );

                Gson gson = plugin.redis().gson();
                plugin.redis().sync().setex(
                        pendingKey,
                        plugin.cfg().rtp().requestTtlSeconds(),
                        gson.toJson(bumped)
                );
            } catch (Exception ignored) {
            }
        });
    }
}
