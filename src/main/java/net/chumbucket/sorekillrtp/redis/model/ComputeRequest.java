/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.redis.model;

import java.util.UUID;

public record ComputeRequest(
        String requestId,
        UUID playerUuid,
        String targetServer,
        String world,
        long createdAtMs
) {}
