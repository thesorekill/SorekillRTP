/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.redis.model;

public record ComputeResponse(
        String requestId,
        boolean ok,
        String server,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String error
) {}
