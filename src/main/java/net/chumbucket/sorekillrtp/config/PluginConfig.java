/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

public record PluginConfig(
        String serverName,
        RedisConfig redis,
        SpawningConfig spawning,
        RtpConfig rtp
) {
    public static PluginConfig load(FileConfiguration cfg) {
        String serverName = cfg.getString("server-name", "unknown");
        serverName = serverName == null ? "unknown" : serverName.trim();

        RedisConfig redis = RedisConfig.load(cfg.getConfigurationSection("redis"));
        SpawningConfig spawning = SpawningConfig.load(cfg.getConfigurationSection("spawning"));
        RtpConfig rtp = RtpConfig.load(cfg.getConfigurationSection("rtp"));

        return new PluginConfig(serverName, redis, spawning, rtp);
    }

    // --- nested config records ---

    public record RedisConfig(
            boolean enabled,
            String host,
            int port,
            String password,
            int database,
            boolean ssl,
            int timeoutMs,
            String keyPrefix
    ) {
        public static RedisConfig load(ConfigurationSection sec) {
            if (sec == null) {
                return new RedisConfig(
                        true,
                        "127.0.0.1",
                        6379,
                        "",
                        0,
                        false,
                        5000,
                        "sorekillrtp:"
                );
            }

            boolean enabled = sec.getBoolean("enabled", true);

            String host = sec.getString("host", "127.0.0.1");
            int port = sec.getInt("port", 6379);
            String password = sec.getString("password", "");

            int database = sec.getInt("database", 0);
            boolean ssl = sec.getBoolean("ssl", false);
            int timeoutMs = sec.getInt("timeoutMs", 5000);

            String keyPrefix = sec.getString("key-prefix", "sorekillrtp:");

            // clamp sanity
            if (host == null || host.isBlank()) host = "127.0.0.1";
            if (port < 1) port = 6379;
            if (password == null) password = "";

            if (database < 0) database = 0;
            if (database > 15) database = 15; // typical Redis range; harmless clamp

            if (timeoutMs < 250) timeoutMs = 250;
            if (timeoutMs > 60000) timeoutMs = 60000;

            if (keyPrefix == null || keyPrefix.isBlank()) keyPrefix = "sorekillrtp:";

            return new RedisConfig(enabled, host, port, password, database, ssl, timeoutMs, keyPrefix);
        }
    }

    public record SpawningConfig(
            boolean crossServerRespawn,
            boolean alwaysSpawnAtSpawn,
            boolean randomTeleportRespawn,
            boolean respectBedSpawn,
            boolean respectAnchorSpawn
    ) {
        public static SpawningConfig load(ConfigurationSection sec) {
            if (sec == null) {
                return new SpawningConfig(
                        false,
                        false,
                        false,
                        true,
                        true
                );
            }

            boolean cross = sec.getBoolean("cross-server-respawn", false);
            boolean always = sec.getBoolean("always-spawn-at-spawn", false);
            boolean random = sec.getBoolean("random-teleport-respawn", false);
            boolean bed = sec.getBoolean("respect-bed-spawn", true);
            boolean anchor = sec.getBoolean("respect-anchor-spawn", true);

            return new SpawningConfig(cross, always, random, bed, anchor);
        }
    }

    public enum FallbackMode { FIRST, RANDOM }

    public record RtpConfig(
            int radius,
            int minRadius,
            int maxTries,
            int requestTtlSeconds,
            int cooldownSeconds,

            // safety spacing
            int avoidPlayersRadius,
            int avoidHostileMobsRadius,

            // perf / behavior tuning
            int pregeneratedOnlyAttempts,
            int maxUniqueChunksPerSearch,
            int responsePollIntervalTicks,
            int countdownSeconds,
            int pendingMaxFinalizeAttempts,

            List<String> fallbackEnabledServers,
            FallbackMode fallbackMode,
            Distribution distribution,
            double gaussianSigma,
            Map<String, ServerRtp> servers
    ) {

        public enum Distribution {
            SQUARE_RING_UNIFORM,
            SQUARE_RING_BIASED_OUTER,
            CIRCLE_RING_UNIFORM_AREA,
            CIRCLE_RING_UNIFORM_RADIUS,
            GAUSSIAN_CLAMPED
        }

        public static RtpConfig load(ConfigurationSection sec) {
            if (sec == null) {
                return new RtpConfig(
                        8000, 250, 30, 30, 60,
                        64, 32,
                        8,
                        10,
                        4,
                        5,
                        2,
                        List.of("smp"), FallbackMode.FIRST,
                        Distribution.SQUARE_RING_UNIFORM,
                        0.35d,
                        Map.of()
                );
            }

            int radius = sec.getInt("radius", 8000);
            int minRadius = sec.getInt("min-radius", 250);
            int maxTries = sec.getInt("max-tries", 30);
            int requestTtlSeconds = sec.getInt("request-ttl-seconds", 30);
            int cooldownSeconds = sec.getInt("cooldown-seconds", 60);

            int avoidPlayersRadius = sec.getInt("avoid-players-radius", 64);
            int avoidHostileMobsRadius = sec.getInt("avoid-hostile-mobs-radius", 32);

            int pregeneratedOnlyAttempts = sec.getInt("pregen-attempts", 8);
            int maxUniqueChunksPerSearch = sec.getInt("max-unique-chunks-per-search", 10);
            int responsePollIntervalTicks = sec.getInt("response-poll-interval-ticks", 4);
            int countdownSeconds = sec.getInt("countdown-seconds", 5);
            int pendingMaxFinalizeAttempts = sec.getInt("pending-max-finalize-attempts", 2);

            // clamp sanity
            if (radius < 0) radius = 0;
            if (minRadius < 0) minRadius = 0;
            if (minRadius > radius) minRadius = radius;

            if (maxTries < 1) maxTries = 1;
            if (requestTtlSeconds < 5) requestTtlSeconds = 5;
            if (cooldownSeconds < 0) cooldownSeconds = 0;

            if (avoidPlayersRadius < 0) avoidPlayersRadius = 0;
            if (avoidHostileMobsRadius < 0) avoidHostileMobsRadius = 0;

            if (pregeneratedOnlyAttempts < 0) pregeneratedOnlyAttempts = 0;
            if (maxUniqueChunksPerSearch < 1) maxUniqueChunksPerSearch = 1;

            if (responsePollIntervalTicks < 1) responsePollIntervalTicks = 1;
            if (responsePollIntervalTicks > 40) responsePollIntervalTicks = 40;

            if (countdownSeconds < 0) countdownSeconds = 0;
            if (countdownSeconds > 30) countdownSeconds = 30;

            if (pendingMaxFinalizeAttempts < 1) pendingMaxFinalizeAttempts = 1;
            if (pendingMaxFinalizeAttempts > 10) pendingMaxFinalizeAttempts = 10;

            List<String> fallback = sec.getStringList("fallback-enabled-servers");
            if (fallback == null) fallback = List.of();

            String modeRaw = sec.getString("fallback-mode", "FIRST");
            FallbackMode mode;
            try {
                mode = FallbackMode.valueOf(modeRaw.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                mode = FallbackMode.FIRST;
            }

            String distRaw = sec.getString("distribution", "SQUARE_RING_UNIFORM");
            Distribution dist;
            try {
                dist = Distribution.valueOf(distRaw.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                dist = Distribution.SQUARE_RING_UNIFORM;
            }

            double sigma = sec.getDouble("gaussian-sigma", 0.35d);
            if (sigma <= 0) sigma = 0.35d;
            if (sigma > 1.0d) sigma = 1.0d;

            Map<String, ServerRtp> servers = new HashMap<>();
            ConfigurationSection serversSec = sec.getConfigurationSection("servers");
            if (serversSec != null) {
                for (String serverKey : serversSec.getKeys(false)) {
                    ConfigurationSection s = serversSec.getConfigurationSection(serverKey);
                    if (s == null) continue;
                    servers.put(serverKey, ServerRtp.load(serverKey, s));
                }
            }

            return new RtpConfig(
                    radius, minRadius, maxTries, requestTtlSeconds, cooldownSeconds,
                    avoidPlayersRadius, avoidHostileMobsRadius,
                    pregeneratedOnlyAttempts, maxUniqueChunksPerSearch,
                    responsePollIntervalTicks, countdownSeconds, pendingMaxFinalizeAttempts,
                    fallback, mode, dist, sigma, servers
            );
        }

        public boolean isServerEnabled(String server) {
            if (server == null) return false;
            ServerRtp s = servers.get(server);
            return s != null && s.enabled();
        }

        public Optional<ServerRtp> getServer(String server) {
            if (server == null) return Optional.empty();
            return Optional.ofNullable(servers.get(server));
        }

        // ---- per-world overrides (fallback to global values) ----

        public int radiusFor(String server, String world) {
            int base = radius();

            ServerRtp s = servers.get(server);
            if (s == null) return base;

            WorldRtp w = s.worlds().get(world);
            if (w == null) return base;

            Integer o = w.radiusOverride();
            return (o != null && o > 0) ? o : base;
        }

        public int minRadiusFor(String server, String world) {
            int base = minRadius();

            int r = radiusFor(server, world);

            ServerRtp s = servers.get(server);
            if (s == null) return clampMin(base, r);

            WorldRtp w = s.worlds().get(world);
            if (w == null) return clampMin(base, r);

            Integer o = w.minRadiusOverride();
            int v = (o != null && o >= 0) ? o : base;

            return clampMin(v, r);
        }

        public Distribution distributionFor(String server, String world) {
            Distribution base = distribution();

            ServerRtp s = servers.get(server);
            if (s == null) return base;

            WorldRtp w = s.worlds().get(world);
            if (w == null) return base;

            Distribution o = w.distributionOverride();
            return (o != null) ? o : base;
        }

        public double gaussianSigmaFor(String server, String world) {
            double base = gaussianSigma();

            ServerRtp s = servers.get(server);
            if (s == null) return base;

            WorldRtp w = s.worlds().get(world);
            if (w == null) return base;

            Double o = w.gaussianSigmaOverride();
            if (o == null) return base;

            double v = o;
            if (v <= 0) v = base;
            if (v > 1.0d) v = 1.0d;
            return v;
        }

        private static int clampMin(int min, int radius) {
            if (radius < 0) radius = 0;
            if (min < 0) min = 0;
            if (min > radius) return radius;
            return min;
        }
    }

    public record ServerRtp(
            String name,
            boolean enabled,
            String defaultWorld,
            Map<String, WorldRtp> worlds
    ) {
        public static ServerRtp load(String name, ConfigurationSection sec) {
            boolean enabled = sec.getBoolean("enabled", false);
            String defaultWorld = sec.getString("default-world", "world");

            Map<String, WorldRtp> worlds = new HashMap<>();
            ConfigurationSection worldsSec = sec.getConfigurationSection("worlds");
            if (worldsSec != null) {
                for (String worldKey : worldsSec.getKeys(false)) {
                    ConfigurationSection w = worldsSec.getConfigurationSection(worldKey);
                    if (w == null) continue;
                    worlds.put(worldKey, WorldRtp.load(worldKey, w));
                }
            }

            return new ServerRtp(name, enabled, defaultWorld, worlds);
        }

        public boolean isWorldEnabled(String world) {
            WorldRtp w = worlds.get(world);
            return w != null && w.enabled();
        }

        public Optional<WorldRtp> getWorld(String world) {
            return Optional.ofNullable(worlds.get(world));
        }
    }

    public record WorldRtp(
            String name,
            boolean enabled,
            Integer radiusOverride,
            Integer minRadiusOverride,
            RtpConfig.Distribution distributionOverride,
            Double gaussianSigmaOverride
    ) {
        public static WorldRtp load(String name, ConfigurationSection sec) {
            boolean enabled = sec.getBoolean("enabled", false);

            Integer radius = sec.contains("radius") ? sec.getInt("radius") : null;
            Integer minRadius = sec.contains("min-radius") ? sec.getInt("min-radius") : null;

            RtpConfig.Distribution dist = null;
            if (sec.contains("distribution")) {
                String raw = sec.getString("distribution", "");
                raw = raw == null ? "" : raw.trim();
                if (!raw.isEmpty()) {
                    try {
                        dist = RtpConfig.Distribution.valueOf(raw.toUpperCase(Locale.ROOT));
                    } catch (Exception ignored) {
                        dist = null;
                    }
                }
            }

            Double sigma = sec.contains("gaussian-sigma") ? sec.getDouble("gaussian-sigma") : null;

            return new WorldRtp(name, enabled, radius, minRadius, dist, sigma);
        }
    }
}
