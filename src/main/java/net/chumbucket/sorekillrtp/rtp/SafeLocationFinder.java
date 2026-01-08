/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.rtp;

import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import net.chumbucket.sorekillrtp.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class SafeLocationFinder {

    private final SorekillRTPPlugin plugin;
    private final Random random = new Random();

    // Nether scan bounds (roof is at 128, so cap well below it)
    private static final int NETHER_MIN_Y = 20;
    private static final int NETHER_MAX_Y = 112;

    // End: avoid super-low Y void-adjacent spawns
    private static final int END_MIN_Y = 35;

    public SafeLocationFinder(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Location> findSafeAsync(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return CompletableFuture.completedFuture(null);

        PluginConfig.RtpConfig rtp = plugin.cfg().rtp();
        int maxTries = rtp.maxTries();
        if (maxTries <= 0) return CompletableFuture.completedFuture(null);

        CompletableFuture<Location> out = new CompletableFuture<>();
        Set<Long> visitedChunks = new HashSet<>();

        // Ensure we begin on main thread (world checks below are main-thread Bukkit API)
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> attempt(world, maxTries, 0, visitedChunks, out));
        } else {
            attempt(world, maxTries, 0, visitedChunks, out);
        }

        return out;
    }

    private void attempt(World world,
                         int triesLeft,
                         int attemptsMade,
                         Set<Long> visitedChunks,
                         CompletableFuture<Location> out) {

        if (out.isDone()) return;

        if (triesLeft <= 0) {
            out.complete(null);
            return;
        }

        PluginConfig.RtpConfig rtp = plugin.cfg().rtp();

        final int pregenAttempts = rtp.pregeneratedOnlyAttempts();
        final int maxUniqueChunks = rtp.maxUniqueChunksPerSearch();

        // We are in "pregen-only" mode until we've made N attempts.
        final boolean pregenOnlyNow = attemptsMade < pregenAttempts;

        // Hard cap on distinct chunk loads this search may trigger.
        if (visitedChunks.size() >= maxUniqueChunks) {
            out.complete(null);
            return;
        }

        String server = plugin.cfg().serverName();
        String worldName = world.getName();

        int radius = rtp.radiusFor(server, worldName);
        int minRadius = rtp.minRadiusFor(server, worldName);

        PluginConfig.RtpConfig.Distribution dist = rtp.distributionFor(server, worldName);
        double sigma = rtp.gaussianSigmaFor(server, worldName);

        int[] off = pickOffset(dist, minRadius, radius, sigma);
        int x = off[0];
        int z = off[1];

        WorldBorder wb = world.getWorldBorder();
        Location center = wb.getCenter();
        Location test = new Location(
                world,
                center.getX() + x + 0.5,
                world.getSeaLevel(),
                center.getZ() + z + 0.5
        );

        if (!wb.isInside(test)) {
            retryNextTick(world, triesLeft - 1, attemptsMade + 1, visitedChunks, out);
            return;
        }

        int cx = ((int) Math.floor(test.getX())) >> 4;
        int cz = ((int) Math.floor(test.getZ())) >> 4;

        long key = packChunk(cx, cz);
        if (visitedChunks.contains(key)) {
            retryNextTick(world, triesLeft - 1, attemptsMade + 1, visitedChunks, out);
            return;
        }

        // Phase 1: only use already-generated chunks (if API available).
        if (pregenOnlyNow && !isChunkGeneratedCompat(world, cx, cz)) {
            retryNextTick(world, triesLeft - 1, attemptsMade + 1, visitedChunks, out);
            return;
        }

        // Commit this chunk as one we are willing to load.
        visitedChunks.add(key);

        // Cross-platform async chunk load (Paper async; Spigot fallback to sync load on main thread)
        getChunkAtAsyncCompat(world, cx, cz).whenComplete((chunk, ex) -> {
            if (out.isDone()) return;

            if (ex != null || chunk == null) {
                retryNextTick(world, triesLeft - 1, attemptsMade + 1, visitedChunks, out);
                return;
            }

            // Now do block/entity safety checks on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (out.isDone()) return;

                int bx = (int) Math.floor(test.getX());
                int bz = (int) Math.floor(test.getZ());

                Location found;
                World.Environment env = world.getEnvironment();

                if (env == World.Environment.NETHER) {
                    found = findInNether(world, bx, bz);
                } else if (env == World.Environment.THE_END) {
                    found = findInEnd(world, bx, bz);
                } else {
                    found = findInOverworld(world, bx, bz);
                }

                if (found != null) {
                    out.complete(found);
                } else {
                    retryNextTick(world, triesLeft - 1, attemptsMade + 1, visitedChunks, out);
                }
            });
        });
    }

    private void retryNextTick(World world,
                               int triesLeft,
                               int attemptsMade,
                               Set<Long> visitedChunks,
                               CompletableFuture<Location> out) {
        if (out.isDone()) return;

        // Avoid deep recursion; schedule the next attempt.
        Bukkit.getScheduler().runTask(plugin, () -> attempt(world, triesLeft, attemptsMade, visitedChunks, out));
    }

    private static long packChunk(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * Uses Paper's World#isChunkGenerated(x,z) if present.
     * If not present, we assume "true" (can't safely pregen-check on that platform).
     */
    private boolean isChunkGeneratedCompat(World world, int cx, int cz) {
        try {
            Method m = world.getClass().getMethod("isChunkGenerated", int.class, int.class);
            Object res = m.invoke(world, cx, cz);
            if (res instanceof Boolean b) return b;
        } catch (NoSuchMethodException ignored) {
            // Not Paper / older API — can't check
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * Paper has World#getChunkAtAsync(int,int). Spigot does not.
     * We use reflection to call it when available; otherwise we sync-load on the main thread.
     */
    private CompletableFuture<Chunk> getChunkAtAsyncCompat(World world, int cx, int cz) {
        try {
            Method m = world.getClass().getMethod("getChunkAtAsync", int.class, int.class);
            Object res = m.invoke(world, cx, cz);
            if (res instanceof CompletableFuture<?> f) {
                @SuppressWarnings("unchecked")
                CompletableFuture<Chunk> cast = (CompletableFuture<Chunk>) f;
                return cast;
            }
        } catch (NoSuchMethodException ignored) {
            // Spigot: no async chunk API
        } catch (Throwable ignored) {
            // Fall through to sync load
        }

        // Fallback: load chunk synchronously on main thread, wrapped in a future
        CompletableFuture<Chunk> fut = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                fut.complete(world.getChunkAt(cx, cz));
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });
        return fut;
    }

    private int[] pickOffset(PluginConfig.RtpConfig.Distribution dist, int minR, int maxR, double gaussianSigma) {
        return switch (dist) {
            case SQUARE_RING_UNIFORM -> randInSquareRing(minR, maxR);
            case SQUARE_RING_BIASED_OUTER -> randSquareRingBiasedOuter(minR, maxR);
            case CIRCLE_RING_UNIFORM_AREA -> randInCircleAnnulusUniformArea(minR, maxR);
            case CIRCLE_RING_UNIFORM_RADIUS -> randInCircleAnnulusUniformRadius(minR, maxR);
            case GAUSSIAN_CLAMPED -> randGaussianClamped(minR, maxR, gaussianSigma);
        };
    }

    private int[] randInSquareRing(int minR, int maxR) {
        if (maxR < 0) throw new IllegalArgumentException("maxR must be >= 0");
        if (minR < 0) minR = 0;
        if (minR > maxR) minR = maxR;

        if (maxR == 0) return new int[]{0, 0};

        while (true) {
            int x = random.nextInt(maxR * 2 + 1) - maxR;
            int z = random.nextInt(maxR * 2 + 1) - maxR;

            if (Math.abs(x) < minR && Math.abs(z) < minR) continue;
            return new int[]{x, z};
        }
    }

    private int[] randSquareRingBiasedOuter(int minR, int maxR) {
        int x = randOutsideMinBiased(minR, maxR);
        int z = randOutsideMinBiased(minR, maxR);
        return new int[]{x, z};
    }

    private int randOutsideMinBiased(int minR, int maxR) {
        if (maxR < 0) throw new IllegalArgumentException("maxR must be >= 0");
        if (minR < 0) minR = 0;
        if (minR > maxR) minR = maxR;

        if (maxR == 0) return 0;

        int v = random.nextInt(maxR + 1);
        if (v < minR) v = minR + random.nextInt(Math.max(1, maxR - minR + 1));
        return random.nextBoolean() ? v : -v;
    }

    private int[] randInCircleAnnulusUniformArea(int minR, int maxR) {
        if (maxR < 0) throw new IllegalArgumentException("maxR must be >= 0");
        if (minR < 0) minR = 0;
        if (minR > maxR) minR = maxR;

        if (maxR == 0) return new int[]{0, 0};

        double u = random.nextDouble();
        double r = Math.sqrt(u * ((double) maxR * maxR - (double) minR * minR) + (double) minR * minR);
        double theta = random.nextDouble() * (Math.PI * 2.0);

        int x = (int) Math.round(r * Math.cos(theta));
        int z = (int) Math.round(r * Math.sin(theta));

        return clampToAnnulus(x, z, minR, maxR);
    }

    private int[] randInCircleAnnulusUniformRadius(int minR, int maxR) {
        if (maxR < 0) throw new IllegalArgumentException("maxR must be >= 0");
        if (minR < 0) minR = 0;
        if (minR > maxR) minR = maxR;

        if (maxR == 0) return new int[]{0, 0};

        double r = minR + random.nextDouble() * (maxR - minR);
        double theta = random.nextDouble() * (Math.PI * 2.0);

        int x = (int) Math.round(r * Math.cos(theta));
        int z = (int) Math.round(r * Math.sin(theta));

        return clampToAnnulus(x, z, minR, maxR);
    }

    private int[] randGaussianClamped(int minR, int maxR, double sigmaFrac) {
        if (maxR < 0) throw new IllegalArgumentException("maxR must be >= 0");
        if (minR < 0) minR = 0;
        if (minR > maxR) minR = maxR;

        if (maxR == 0) return new int[]{0, 0};

        double sigma = Math.max(1.0, sigmaFrac * maxR);

        int min2 = minR * minR;
        int max2 = maxR * maxR;

        for (int i = 0; i < 32; i++) {
            int x = (int) Math.round(random.nextGaussian() * sigma);
            int z = (int) Math.round(random.nextGaussian() * sigma);

            x = Math.max(-maxR, Math.min(maxR, x));
            z = Math.max(-maxR, Math.min(maxR, z));

            int d2 = x * x + z * z;
            if (d2 < min2 || d2 > max2) continue;

            return new int[]{x, z};
        }

        return randInCircleAnnulusUniformArea(minR, maxR);
    }

    private int[] clampToAnnulus(int x, int z, int minR, int maxR) {
        int min2 = minR * minR;
        int max2 = maxR * maxR;

        int d2 = x * x + z * z;
        if (d2 >= min2 && d2 <= max2) return new int[]{x, z};

        for (int i = 0; i < 32; i++) {
            int rx = random.nextInt(maxR * 2 + 1) - maxR;
            int rz = random.nextInt(maxR * 2 + 1) - maxR;
            int rd2 = rx * rx + rz * rz;
            if (rd2 < min2 || rd2 > max2) continue;
            return new int[]{rx, rz};
        }

        if (x == 0 && z == 0) return new int[]{minR, 0};
        double len = Math.sqrt(Math.max(1.0, (double) d2));
        double target = (d2 > max2) ? maxR : minR;
        double scale = target / len;

        int sx = (int) Math.round(x * scale);
        int sz = (int) Math.round(z * scale);
        return new int[]{sx, sz};
    }

    private Location findInOverworld(World world, int bx, int bz) {
        int top = world.getHighestBlockYAt(bx, bz);
        Location feet = new Location(world, bx + 0.5, top + 1, bz + 0.5);
        feet.setYaw(random.nextFloat() * 360f);
        feet.setPitch(0f);

        if (!SafetyChecks.isSafeStandingStrict(feet)) return null;
        if (!isAreaClearOfPlayers(feet)) return null;
        if (!isAreaClearOfHostilesNearby(feet)) return null;

        return feet;
    }

    private Location findInNether(World world, int bx, int bz) {
        int maxY = Math.min(NETHER_MAX_Y, world.getMaxHeight() - 2);
        int minY = Math.max(NETHER_MIN_Y, world.getMinHeight() + 2);

        for (int y = maxY; y >= minY; y--) {
            Location feet = new Location(world, bx + 0.5, y, bz + 0.5);
            feet.setYaw(random.nextFloat() * 360f);
            feet.setPitch(0f);

            Block floor = world.getBlockAt(bx, y - 1, bz);
            if (floor.getType() == Material.BEDROCK) continue;

            if (!SafetyChecks.isSafeStandingStrict(feet)) continue;
            if (!isAreaClearOfPlayers(feet)) continue;
            if (!isAreaClearOfHostilesNearby(feet)) continue;

            return feet;
        }

        return null;
    }

    private Location findInEnd(World world, int bx, int bz) {
        int top = world.getHighestBlockYAt(bx, bz);
        int maxY = Math.min(top + 1, world.getMaxHeight() - 2);
        int minY = Math.max(END_MIN_Y, world.getMinHeight() + 2);

        for (int y = maxY; y >= minY; y--) {
            Location feet = new Location(world, bx + 0.5, y, bz + 0.5);
            feet.setYaw(random.nextFloat() * 360f);
            feet.setPitch(0f);

            if (!SafetyChecks.isSafeStandingStrict(feet)) continue;
            if (!isAreaClearOfPlayers(feet)) continue;
            if (!isAreaClearOfHostilesNearby(feet)) continue;

            return feet;
        }

        return null;
    }

    private boolean isAreaClearOfPlayers(Location candidate) {
        int r = plugin.cfg().rtp().avoidPlayersRadius();
        if (r <= 0) return true;

        World cw = candidate.getWorld();
        if (cw == null) return true;

        double r2 = (double) r * (double) r;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;

            Location pl = p.getLocation();
            if (pl == null || pl.getWorld() == null) continue;

            // Only compare distance if same world
            if (pl.getWorld().getUID().equals(cw.getUID())) {
                if (pl.distanceSquared(candidate) <= r2) return false;
            }
        }

        return true;
    }

    private boolean isAreaClearOfHostilesNearby(Location candidate) {
        int r = plugin.cfg().rtp().avoidHostileMobsRadius();
        if (r <= 0) return true;

        World w = candidate.getWorld();
        if (w == null) return true;

        double r2 = (double) r * (double) r;

        for (Entity ent : w.getNearbyEntities(candidate, r, r, r)) {
            if (!(ent instanceof Enemy)) continue;
            if (!(ent instanceof LivingEntity le)) continue;
            if (le.isDead()) continue;

            Location el = le.getLocation();
            if (el.distanceSquared(candidate) <= r2) return false;
        }

        return true;
    }
}
