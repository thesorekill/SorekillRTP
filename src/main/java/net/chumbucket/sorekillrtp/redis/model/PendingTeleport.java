/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.redis.model;

public final class PendingTeleport {

    private final String server;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final long atMs;

    // NEW: finalize retry counter
    private final int attempts;

    public PendingTeleport(String server, String world,
                           double x, double y, double z,
                           float yaw, float pitch,
                           long atMs,
                           int attempts) {
        this.server = server;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.atMs = atMs;
        this.attempts = attempts;
    }

    // Back-compat convenience (if you call the old constructor anywhere)
    public PendingTeleport(String server, String world,
                           double x, double y, double z,
                           float yaw, float pitch,
                           long atMs) {
        this(server, world, x, y, z, yaw, pitch, atMs, 0);
    }

    public String server() { return server; }
    public String world() { return world; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public long atMs() { return atMs; }

    public int attempts() { return attempts; }
}
