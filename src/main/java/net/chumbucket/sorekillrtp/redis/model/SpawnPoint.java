/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.redis.model;

import java.util.Locale;

public final class SpawnPoint {

    // "BED", "ANCHOR", or "UNKNOWN"
    private final String type;

    private final String server;
    private final String world;

    private final double x;
    private final double y;
    private final double z;

    private final float yaw;
    private final float pitch;

    private final long atMs;

    // Backwards-compatible constructor (old data had no "type")
    public SpawnPoint(String server, String world,
                      double x, double y, double z,
                      float yaw, float pitch,
                      long atMs) {
        this("UNKNOWN", server, world, x, y, z, yaw, pitch, atMs);
    }

    public SpawnPoint(String type,
                      String server, String world,
                      double x, double y, double z,
                      float yaw, float pitch,
                      long atMs) {
        this.type = normalizeType(type);
        this.server = server == null ? "" : server;
        this.world = world == null ? "" : world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.atMs = atMs > 0 ? atMs : System.currentTimeMillis();
    }

    public String typeRaw() { return type; }

    public boolean isBed() { return "BED".equals(type); }

    public boolean isAnchor() { return "ANCHOR".equals(type); }

    public String server() { return server; }
    public String world() { return world; }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    public long atMs() { return atMs; }

    private static String normalizeType(String t) {
        if (t == null) return "UNKNOWN";
        String v = t.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "BED" -> "BED";
            case "ANCHOR", "RESPAWN_ANCHOR" -> "ANCHOR";
            default -> "UNKNOWN";
        };
    }
}
