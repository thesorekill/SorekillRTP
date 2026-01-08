/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.redis;

import net.chumbucket.sorekillrtp.config.PluginConfig;

import java.util.UUID;

public final class RedisKeys {
    private final String p;

    public RedisKeys(PluginConfig.RedisConfig redis) {
        // Null-safe + sanitize prefix.
        String pref = (redis == null) ? null : redis.keyPrefix();

        String base = (pref == null) ? "" : pref.trim();
        if (base.isEmpty()) base = "sorekillrtp:";

        // Normalize separators:
        // - collapse repeated ":" at the end
        // - ensure exactly one trailing ":"
        while (base.endsWith("::")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.endsWith(":")) base = base + ":";

        this.p = base;
    }

    public String prefix() { return p; }

    public String channelCompute() { return p + "compute"; }
    public String resp(String requestId) { return p + "resp:" + requestId; }
    public String pending(UUID player) { return p + "pending:" + player; }
    public String cooldown(UUID player) { return p + "cooldown:" + player; }
    public String presence(UUID player) { return p + "presence:" + player; }

    // Cross-backend "source of truth" respawn point (bed/anchor)
    public String spawn(UUID player) { return p + "spawn:" + player; }
}
