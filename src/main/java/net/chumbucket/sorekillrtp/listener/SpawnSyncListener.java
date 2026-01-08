/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.listener;

import com.google.gson.Gson;
import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import net.chumbucket.sorekillrtp.redis.RedisKeys;
import net.chumbucket.sorekillrtp.redis.model.SpawnPoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Syncs the cross-backend "source of truth" spawnpoint to Redis.
 *
 * Knobs respected:
 * - spawning.cross-server-respawn (master enable for spawn sync feature)
 * - spawning.respect-bed-spawn
 * - spawning.respect-anchor-spawn
 * - redis.enabled (and Redis actually started)
 */
public final class SpawnSyncListener implements Listener {

    private static final int SPAWN_TTL_SECONDS = 60 * 60 * 24 * 30; // 30 days

    // How close a stored spawn must be to a block to be considered "that spawn"
    private static final double MATCH_XZ_BLOCKS = 1.25;
    private static final double MATCH_Y_BLOCKS  = 2.25;

    private final SorekillRTPPlugin plugin;

    public SpawnSyncListener(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- knobs (fully safe) ----

    /** Master enable: feature flag + Redis availability (including redis != null). */
    private boolean spawningEnabled() {
        if (plugin == null || plugin.cfg() == null) return false;
        if (plugin.cfg().spawning() == null) return false;
        if (!plugin.isRedisEnabled()) return false; // requires redis != null too
        return plugin.cfg().spawning().crossServerRespawn();
    }

    private boolean respectBedSpawn() {
        // default true if missing section
        return plugin.cfg() == null
                || plugin.cfg().spawning() == null
                || plugin.cfg().spawning().respectBedSpawn();
    }

    private boolean respectAnchorSpawn() {
        // default true if missing section
        return plugin.cfg() == null
                || plugin.cfg().spawning() == null
                || plugin.cfg().spawning().respectAnchorSpawn();
    }

    /**
     * Must be called once from onEnable (and again after reloadAll if you rebuild listeners).
     * Registers Paper/Purpur PlayerSetSpawnEvent if present, without @EventHandler(Event).
     */
    @SuppressWarnings("unchecked")
    public void registerPaperSpawnSetHook() {
        if (!spawningEnabled()) return;

        PluginManager pm = plugin.getServer().getPluginManager();

        Class<?> cls = tryClass("io.papermc.paper.event.player.PlayerSetSpawnEvent");
        if (cls == null) cls = tryClass("com.destroystokyo.paper.event.player.PlayerSetSpawnEvent");
        if (cls == null) return;

        if (!Event.class.isAssignableFrom(cls)) return;

        Class<? extends Event> eventClass = (Class<? extends Event>) cls;

        EventExecutor exec = (listener, event) -> {
            if (!eventClass.isInstance(event)) return;
            handlePlayerSetSpawnEvent(event);
        };

        pm.registerEvent(eventClass, this, EventPriority.MONITOR, exec, plugin, true);
        plugin.getLogger().info("SpawnSyncListener: hooked " + eventClass.getName());
    }

    private static Class<?> tryClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Bed spawn is usually set when the player ENTERS the bed successfully.
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        if (!spawningEnabled()) return;
        if (!respectBedSpawn()) return;
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;

        final Player p = e.getPlayer();
        if (p == null) return;

        final Block bedBlock = e.getBed();
        if (bedBlock == null || bedBlock.getWorld() == null) return;

        // capture stable primitives now (avoid relying on Event object later)
        final UUID uuid = p.getUniqueId();
        final String bedWorld = bedBlock.getWorld().getName();
        final double bedX = bedBlock.getX() + 0.5;
        final double bedY = bedBlock.getY();
        final double bedZ = bedBlock.getZ() + 0.5;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!spawningEnabled()) return;
            if (!respectBedSpawn()) return;
            if (!p.isOnline()) return;

            Location pl = p.getLocation();
            writeSpawnAsync(uuid, bedWorld, bedX, bedY, bedZ, pl.getYaw(), pl.getPitch());
        });
    }

    /**
     * Immediate respawn anchor sync & clear-on-zero.
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnchorInteract(PlayerInteractEvent e) {
        if (!spawningEnabled()) return;
        if (!respectAnchorSpawn()) return;

        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != null && e.getHand() != EquipmentSlot.HAND) return;

        final Block clicked = e.getClickedBlock();
        if (clicked == null) return;
        if (clicked.getType() != Material.RESPAWN_ANCHOR) return;

        final Player p = e.getPlayer();
        if (p == null) return;

        final UUID uuid = p.getUniqueId();
        final String worldName = (clicked.getWorld() == null) ? null : clicked.getWorld().getName();
        if (worldName == null) return;

        final int ax = clicked.getX();
        final int ay = clicked.getY();
        final int az = clicked.getZ();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!spawningEnabled()) return;
            if (!respectAnchorSpawn()) return;
            if (!p.isOnline()) return;

            World w = Bukkit.getWorld(worldName);
            if (w == null) return;

            Block anchor = w.getBlockAt(ax, ay, az);

            // Anchor removed -> clear if it matches what we stored
            if (anchor.getType() != Material.RESPAWN_ANCHOR) {
                clearSpawnIfMatchesAsync(uuid, worldName, ax + 0.5, ay + 1.0, az + 0.5);
                return;
            }

            int charges = getAnchorCharges(anchor);
            double sx = ax + 0.5;
            double sy = ay + 1.0; // spawn stored above anchor
            double sz = az + 0.5;

            if (charges <= 0) {
                clearSpawnIfMatchesAsync(uuid, worldName, sx, sy, sz);
                return;
            }

            Location pl = p.getLocation();
            writeSpawnAsync(uuid, worldName, sx, sy, sz, pl.getYaw(), pl.getPitch());
        });
    }

    /**
     * Clear stored spawn if player breaks THEIR OWN bed/anchor (best-effort by matching location).
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!spawningEnabled()) return;

        Player p = e.getPlayer();
        if (p == null) return;

        Block b = e.getBlock();
        if (b == null) return;

        Material type = b.getType();
        boolean bed = Tag.BEDS.isTagged(type);
        boolean anchor = (type == Material.RESPAWN_ANCHOR);

        if (!bed && !anchor) return;

        if (bed && !respectBedSpawn()) return;
        if (anchor && !respectAnchorSpawn()) return;

        Location target = bed
                ? b.getLocation().add(0.5, 0.0, 0.5)
                : b.getLocation().add(0.5, 1.0, 0.5);

        if (target.getWorld() == null) return;

        clearSpawnIfMatchesAsync(
                p.getUniqueId(),
                target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ()
        );
    }

    /**
     * Fallback: sync when Bukkit actually respawns them at a bed/respawn anchor.
     * Also clears anchor spawn if this respawn consumes the last charge (goes to 0).
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!spawningEnabled()) return;

        boolean isBed = isBedRespawn(e);
        boolean isAnchor = isAnchorRespawn(e);

        if (isBed && !respectBedSpawn()) return;
        if (isAnchor && !respectAnchorSpawn()) return;

        if (!isBed && !isAnchor) return;

        Location loc = e.getRespawnLocation();
        if (loc == null || loc.getWorld() == null) return;

        Player p = e.getPlayer();
        if (p == null) return;

        writeSpawnAsync(
                p.getUniqueId(),
                loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch()
        );

        if (isAnchor) {
            final UUID uuid = p.getUniqueId();
            final String worldName = loc.getWorld().getName();
            final int rx = loc.getBlockX();
            final int ry = loc.getBlockY();
            final int rz = loc.getBlockZ();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!spawningEnabled()) return;
                if (!respectAnchorSpawn()) return;
                if (!p.isOnline()) return;

                World w = Bukkit.getWorld(worldName);
                if (w == null) return;

                Block b1 = w.getBlockAt(rx, ry, rz);
                Block b2 = w.getBlockAt(rx, ry - 1, rz);

                Block anchorBlock = null;
                if (b1.getType() == Material.RESPAWN_ANCHOR) anchorBlock = b1;
                else if (b2.getType() == Material.RESPAWN_ANCHOR) anchorBlock = b2;

                if (anchorBlock == null) return;

                int charges = getAnchorCharges(anchorBlock);
                if (charges <= 0) {
                    clearSpawnIfMatchesAsync(
                            uuid,
                            worldName,
                            anchorBlock.getX() + 0.5, anchorBlock.getY() + 1.0, anchorBlock.getZ() + 0.5
                    );
                }
            });
        }
    }

    /**
     * Paper/Purpur spawn-set hook. We only write if we can infer bed/anchor AND the knob allows it.
     */
    private void handlePlayerSetSpawnEvent(Event event) {
        if (!spawningEnabled()) return;

        try {
            Player player = (Player) callNoArg(event, "getPlayer");
            if (player == null) return;

            Object cause = tryCallNoArg(event, "getCause");
            if (cause != null) {
                String c = String.valueOf(cause).toUpperCase();

                boolean bed = c.contains("BED");
                boolean anchor = c.contains("ANCHOR") || c.contains("RESPAWN");

                if (bed && !respectBedSpawn()) return;
                if (anchor && !respectAnchorSpawn()) return;
                if (!bed && !anchor) return;
            }

            Location loc = null;

            Object o1 = tryCallNoArg(event, "getNewSpawnLocation");
            if (o1 instanceof Location l) loc = l;

            if (loc == null) {
                Object o2 = tryCallNoArg(event, "getNewSpawn");
                if (o2 instanceof Location l) loc = l;
            }

            if (loc == null) {
                Object o3 = tryCallNoArg(event, "getLocation");
                if (o3 instanceof Location l) loc = l;
            }

            if (loc == null || loc.getWorld() == null) return;

            writeSpawnAsync(
                    player.getUniqueId(),
                    loc.getWorld().getName(),
                    loc.getX(), loc.getY(), loc.getZ(),
                    loc.getYaw(), loc.getPitch()
            );
        } catch (Throwable ignored) {
        }
    }

    // ---------------- core redis helpers ----------------

    /**
     * IMPORTANT: never do Redis I/O on the main thread.
     */
    private void writeSpawnAsync(UUID uuid, String world, double x, double y, double z, float yaw, float pitch) {
        if (uuid == null) return;
        if (world == null || world.isBlank()) return;
        if (!spawningEnabled()) return;

        final String serverName = plugin.cfg().serverName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Re-check: reload can null redis while this task is queued/running
            if (!spawningEnabled()) return;
            if (plugin.redis() == null) return;

            final RedisKeys keys;
            final Gson gson;
            try {
                keys = plugin.redis().keys();
                gson = plugin.redis().gson();
            } catch (Exception ignored) {
                return;
            }

            SpawnPoint record = new SpawnPoint(
                    serverName,
                    world,
                    x, y, z,
                    yaw, pitch,
                    System.currentTimeMillis()
            );

            try {
                plugin.redis().sync().setex(keys.spawn(uuid), SPAWN_TTL_SECONDS, gson.toJson(record));
            } catch (Exception ignored) {}
        });
    }

    private void clearSpawnIfMatchesAsync(UUID uuid, String world, double x, double y, double z) {
        if (uuid == null) return;
        if (world == null || world.isBlank()) return;
        if (!spawningEnabled()) return;

        final String localServer = plugin.cfg().serverName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!spawningEnabled()) return;
            if (plugin.redis() == null) return;

            final RedisKeys keys;
            final Gson gson;
            try {
                keys = plugin.redis().keys();
                gson = plugin.redis().gson();
            } catch (Exception ignored) {
                return;
            }

            final String spawnKey = keys.spawn(uuid);

            try {
                String raw = plugin.redis().sync().get(spawnKey);
                if (raw == null || raw.isBlank()) return;

                SpawnPoint sp;
                try {
                    sp = gson.fromJson(raw, SpawnPoint.class);
                } catch (Exception ex) {
                    try { plugin.redis().sync().del(spawnKey); } catch (Exception ignored2) {}
                    return;
                }

                if (sp == null) return;
                if (sp.server() == null || sp.world() == null) return;

                // only clear if this backend is the one that wrote it
                if (!localServer.equalsIgnoreCase(sp.server())) return;
                if (!world.equalsIgnoreCase(sp.world())) return;

                double dx = Math.abs(sp.x() - x);
                double dy = Math.abs(sp.y() - y);
                double dz = Math.abs(sp.z() - z);

                if (dx <= MATCH_XZ_BLOCKS && dz <= MATCH_XZ_BLOCKS && dy <= MATCH_Y_BLOCKS) {
                    plugin.redis().sync().del(spawnKey);
                }
            } catch (Exception ignored) {}
        });
    }

    // ---------------- detection helpers ----------------

    private static int getAnchorCharges(Block b) {
        try {
            if (b == null || b.getType() != Material.RESPAWN_ANCHOR) return 0;
            BlockData data = b.getBlockData();
            if (data instanceof RespawnAnchor ra) {
                return ra.getCharges();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

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

    private static Object callNoArg(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object tryCallNoArg(Object target, String name) {
        try {
            return callNoArg(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
