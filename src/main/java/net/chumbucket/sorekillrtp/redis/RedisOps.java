/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.redis;

public interface RedisOps {
    String get(String key);
    void setex(String key, int seconds, String value);
    void del(String key);
    void publish(String channel, String message);
    long ttl(String key);
}
