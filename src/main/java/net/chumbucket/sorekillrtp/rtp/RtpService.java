/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.rtp;

import com.google.gson.Gson;
import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import net.chumbucket.sorekillrtp.redis.RedisKeys;
import net.chumbucket.sorekillrtp.redis.model.ComputeRequest;
import net.chumbucket.sorekillrtp.redis.model.ComputeResponse;
import net.chumbucket.sorekillrtp.redis.model.PendingTeleport;
import net.chumbucket.sorekillrtp.util.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RtpService {

    private final SorekillRTPPlugin plugin;
    private final SafeLocationFinder finder;

    // one active attempt per player
    private final ConcurrentHashMap<UUID, Attempt> attempts = new ConcurrentHashMap<>();

    public RtpService(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
        this.finder = new SafeLocationFinder(plugin);
    }

    public SafeLocationFinder finder() {
        return finder;
    }

    public void startRtp(Player target, CommandSender feedbackTo, String server, String world, boolean admin) {
        if (target == null || !target.isOnline()) return;

        boolean cooldownBypass = target.hasPermission("sorekillrtp.cooldown.bypass");
        boolean timerBypass = admin || target.hasPermission("sorekillrtp.tptimer.bypass");

        // Cancel any existing attempt for this player
        Attempt prev = attempts.remove(target.getUniqueId());
        if (prev != null) prev.cancelSilently();

        Attempt attempt = new Attempt(plugin, target);
        attempts.put(target.getUniqueId(), attempt);

        // Movement cancel monitor:
        // IMPORTANT: we DO NOT cancel while "searching". We only enable cancellation during the countdown.
        attempt.startMonitor(() -> {
            plugin.messages().send(target, "errors.teleport-cancelled-moved");
            SoundUtil.playConfigured(plugin, target, "sounds.teleport_cancelled");
            attempts.remove(target.getUniqueId(), attempt);
        });

        // Cooldown gate (async if needed), then continue
        CompletableFuture<Boolean> cooldownOk;
        if (!admin && !cooldownBypass) cooldownOk = checkAndSetCooldownAsync(target, feedbackTo);
        else cooldownOk = CompletableFuture.completedFuture(true);

        cooldownOk.whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (attempt.isCancelled()) return;
            if (ex != null || ok == null || !ok) {
                attempt.finish();
                attempts.remove(target.getUniqueId(), attempt);
                return;
            }

            // LOCAL RTP
            if (plugin.cfg().serverName().equalsIgnoreCase(server)) {
                plugin.messages().send(feedbackTo, "status.searching-local");

                finder.findSafeAsync(world).whenComplete((loc, findEx) -> {
                    if (attempt.isCancelled()) return;

                    if (findEx != null || loc == null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (attempt.isCancelled()) return;
                            attempt.finish();
                            attempts.remove(target.getUniqueId(), attempt);

                            plugin.messages().send(feedbackTo, "errors.no-safe-location");
                            SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                        });
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (attempt.isCancelled()) return;

                        Runnable doTeleport = () -> {
                            if (attempt.isCancelled() || !target.isOnline()) return;

                            attempt.finish();
                            attempts.remove(target.getUniqueId(), attempt);

                            preloadThenTeleport(
                                    target,
                                    loc,
                                    () -> {
                                        if (!target.isOnline()) return;

                                        plugin.messages().send(target, "success.teleported", Map.of("world", world));
                                        SoundUtil.playConfigured(plugin, target, "sounds.teleport_successful");

                                        if (admin && feedbackTo != target) {
                                            plugin.messages().send(
                                                    feedbackTo,
                                                    "success.teleported-other",
                                                    Map.of("player", target.getName(), "server", server, "world", world)
                                            );
                                        }
                                    },
                                    () -> {
                                        if (!target.isOnline()) return;
                                        plugin.messages().send(feedbackTo, "errors.no-safe-location");
                                        SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                                    }
                            );
                        };

                        if (timerBypass) doTeleport.run();
                        else countdownWithMoveCancel(attempt, target, doTeleport);
                    });
                });

                return;
            }

            // REMOTE RTP requires Redis
            if (!plugin.isRedisEnabled() || plugin.redis() == null) {
                attempt.finish();
                attempts.remove(target.getUniqueId(), attempt);

                plugin.messages().send(feedbackTo, "errors.compute-timeout");
                SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                return;
            }

            plugin.messages().send(feedbackTo, "status.searching-remote", Map.of("server", server));

            String requestId = UUID.randomUUID().toString();
            RedisKeys keys = plugin.redis().keys();
            Gson gson = plugin.redis().gson();

            ComputeRequest req = new ComputeRequest(
                    requestId,
                    target.getUniqueId(),
                    server,
                    world,
                    System.currentTimeMillis()
            );

            // publish request async
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (attempt.isCancelled()) return;
                if (!plugin.isRedisEnabled() || plugin.redis() == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (attempt.isCancelled()) return;
                        attempt.finish();
                        attempts.remove(target.getUniqueId(), attempt);

                        plugin.messages().send(feedbackTo, "errors.compute-timeout");
                        SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                    });
                    return;
                }

                try {
                    plugin.redis().sync().publish(keys.channelCompute(), gson.toJson(req));
                } catch (Exception e) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (attempt.isCancelled()) return;
                        attempt.finish();
                        attempts.remove(target.getUniqueId(), attempt);

                        plugin.messages().send(feedbackTo, "errors.compute-timeout");
                        SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                    });
                    return;
                }

                int ttl = plugin.cfg().rtp().requestTtlSeconds();

                waitForComputeResponse(requestId, ttl, attempt).whenComplete((finalResp, waitEx) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (attempt.isCancelled()) return;

                        if (waitEx != null || finalResp == null || !finalResp.ok()) {
                            attempt.finish();
                            attempts.remove(target.getUniqueId(), attempt);

                            plugin.messages().send(feedbackTo, "errors.no-safe-location");
                            SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                            return;
                        }

                        Runnable doSwitch = () -> {
                            if (attempt.isCancelled() || !target.isOnline()) return;

                            PendingTeleport pending = new PendingTeleport(
                                    finalResp.server(),
                                    finalResp.world(),
                                    finalResp.x(), finalResp.y(), finalResp.z(),
                                    finalResp.yaw(), finalResp.pitch(),
                                    System.currentTimeMillis(),
                                    0 // attempts
                            );

                            final String pendingKey = keys.pending(target.getUniqueId());
                            final int pendingTtl = plugin.cfg().rtp().requestTtlSeconds();
                            final String pendingJson = gson.toJson(pending);

                            // ✅ write pending async (NO redis on main)
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                if (!plugin.isRedisEnabled() || plugin.redis() == null) {
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (attempt.isCancelled()) return;
                                        attempt.finish();
                                        attempts.remove(target.getUniqueId(), attempt);

                                        plugin.messages().send(feedbackTo, "errors.compute-timeout");
                                        SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                                    });
                                    return;
                                }

                                try {
                                    plugin.redis().sync().setex(pendingKey, pendingTtl, pendingJson);
                                } catch (Exception e2) {
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (attempt.isCancelled()) return;
                                        attempt.finish();
                                        attempts.remove(target.getUniqueId(), attempt);

                                        plugin.messages().send(feedbackTo, "errors.compute-timeout");
                                        SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                                    });
                                    return;
                                }

                                // ✅ now do the messaging + connect on main thread
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (attempt.isCancelled() || !target.isOnline()) {
                                        // best-effort cleanup
                                        deleteKeyAsync(pendingKey);
                                        return;
                                    }

                                    attempt.finish();
                                    attempts.remove(target.getUniqueId(), attempt);

                                    if (admin && feedbackTo != target) {
                                        plugin.messages().send(
                                                feedbackTo,
                                                "status.switching-other",
                                                Map.of("player", target.getName(), "server", server)
                                        );
                                    } else {
                                        plugin.messages().send(target, "status.switching", Map.of("server", server));
                                    }

                                    // success sound ONLY on destination (JoinFinalizeListener)
                                    boolean sent = plugin.velocity().connect(target, server);
                                    if (!sent) {
                                        // clean up pending so it doesn't trigger later (async)
                                        deleteKeyAsync(pendingKey);

                                        SoundUtil.playConfigured(plugin, target, "sounds.teleport_unsuccessful");
                                        plugin.messages().send(target, "errors.compute-timeout");
                                        plugin.getLogger().warning("Velocity connect plugin message failed to send (channel not registered or player offline?).");
                                    }
                                });
                            });
                        };

                        if (timerBypass) doSwitch.run();
                        else countdownWithMoveCancel(attempt, target, doSwitch);
                    });
                });
            });
        }));
    }

    /**
     * Async cooldown check+set. Never does Redis I/O on main thread.
     * Fail-open if Redis is unavailable so local RTP still works.
     */
    private CompletableFuture<Boolean> checkAndSetCooldownAsync(Player target, CommandSender feedbackTo) {
        int cd = plugin.cfg().rtp().cooldownSeconds();
        if (cd <= 0) return CompletableFuture.completedFuture(true);

        if (!plugin.isRedisEnabled() || plugin.redis() == null) return CompletableFuture.completedFuture(true);

        UUID uuid = target.getUniqueId();

        CompletableFuture<Boolean> fut = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!plugin.isRedisEnabled() || plugin.redis() == null) {
                fut.complete(true); // fail-open
                return;
            }

            RedisKeys keys = plugin.redis().keys();
            String key = keys.cooldown(uuid);

            try {
                String existing = plugin.redis().sync().get(key);
                if (existing != null) {
                    long ttl = plugin.redis().sync().ttl(key);
                    long remaining = (ttl >= 0) ? ttl : cd;

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.messages().send(feedbackTo, "cooldown.active", Map.of("time", remaining + "s"));
                    });

                    fut.complete(false);
                    return;
                }

                plugin.redis().sync().setex(key, cd, "1");
                fut.complete(true);
            } catch (Exception e) {
                fut.complete(true); // fail-open
            }
        });

        return fut;
    }

    /**
     * Polls redis for resp:<requestId> using a repeating async task (no Thread.sleep).
     * Also deletes the resp key once read, to avoid stale buildup.
     */
    private CompletableFuture<ComputeResponse> waitForComputeResponse(String requestId, int ttlSeconds, Attempt attempt) {
        CompletableFuture<ComputeResponse> fut = new CompletableFuture<>();

        if (!plugin.isRedisEnabled() || plugin.redis() == null) {
            fut.complete(null);
            return fut;
        }

        RedisKeys keys = plugin.redis().keys();
        Gson gson = plugin.redis().gson();

        final String respKey = keys.resp(requestId);
        final long deadline = System.currentTimeMillis() + (ttlSeconds * 1000L);
        final BukkitTask[] task = new BukkitTask[1];

        long intervalTicks = plugin.cfg().rtp().responsePollIntervalTicks();
        if (intervalTicks < 1) intervalTicks = 1;
        if (intervalTicks > 40) intervalTicks = 40;

        task[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (fut.isDone()) {
                task[0].cancel();
                return;
            }
            if (attempt.isCancelled()) {
                task[0].cancel();
                fut.complete(null);
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                task[0].cancel();
                fut.complete(null);
                return;
            }

            if (!plugin.isRedisEnabled() || plugin.redis() == null) {
                task[0].cancel();
                fut.complete(null);
                return;
            }

            try {
                String raw = plugin.redis().sync().get(respKey);
                if (raw != null && !raw.isBlank()) {
                    ComputeResponse resp = gson.fromJson(raw, ComputeResponse.class);

                    try { plugin.redis().sync().del(respKey); } catch (Exception ignored) {}

                    task[0].cancel();
                    fut.complete(resp);
                }
            } catch (Exception ignored) {
            }
        }, 0L, intervalTicks);

        return fut;
    }

    /**
     * Countdown that can be cancelled by Attempt's movement monitor.
     */
    private void countdownWithMoveCancel(Attempt attempt, Player player, Runnable onDone) {
        int delaySeconds = plugin.cfg().rtp().countdownSeconds();

        if (delaySeconds <= 0) {
            onDone.run();
            return;
        }

        if (player == null || !player.isOnline()) return;
        if (attempt.isCancelled()) return;

        attempt.enableCancelOnMove();

        final BukkitTask[] msgTaskRef = new BukkitTask[1];
        final BukkitTask[] doneTaskRef = new BukkitTask[1];

        final int[] remaining = { delaySeconds };

        msgTaskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (attempt.isCancelled() || !player.isOnline()) {
                if (msgTaskRef[0] != null) msgTaskRef[0].cancel();
                if (doneTaskRef[0] != null) doneTaskRef[0].cancel();
                return;
            }

            int sec = remaining[0];
            if (sec <= 0) {
                if (msgTaskRef[0] != null) msgTaskRef[0].cancel();
                return;
            }

            plugin.messages().send(player, "status.teleporting-in", Map.of("seconds", String.valueOf(sec)));
            SoundUtil.playConfigured(plugin, player, "sounds.countdown");

            remaining[0]--;
        }, 0L, 20L);

        doneTaskRef[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (attempt.isCancelled() || !player.isOnline()) {
                if (msgTaskRef[0] != null) msgTaskRef[0].cancel();
                return;
            }
            if (msgTaskRef[0] != null) msgTaskRef[0].cancel();
            onDone.run();
        }, delaySeconds * 20L);
    }

    /**
     * Preloads the destination chunk before teleporting.
     * - Preload runs async (Paper).
     * - Teleport is executed on main thread via teleportAsync.
     */
    private void preloadThenTeleport(Player player,
                                     Location loc,
                                     Runnable afterTeleportSync,
                                     Runnable onFailSync) {
        if (player == null || !player.isOnline()) {
            if (onFailSync != null) onFailSync.run();
            return;
        }
        if (loc == null) {
            if (onFailSync != null) onFailSync.run();
            return;
        }

        World w = loc.getWorld();
        if (w == null) {
            if (onFailSync != null) onFailSync.run();
            return;
        }

        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;

        w.getChunkAtAsync(cx, cz).whenComplete((chunk, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                if (onFailSync != null) onFailSync.run();
                return;
            }

            if (ex != null) {
                if (onFailSync != null) onFailSync.run();
                return;
            }

            player.teleportAsync(loc).whenComplete((ok, tex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    if (onFailSync != null) onFailSync.run();
                    return;
                }

                if (tex != null || ok == null || !ok) {
                    if (onFailSync != null) onFailSync.run();
                    return;
                }

                if (afterTeleportSync != null) afterTeleportSync.run();
            }));
        }));
    }

    private void deleteKeyAsync(String key) {
        if (key == null || key.isBlank()) return;
        if (!plugin.isRedisEnabled() || plugin.redis() == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!plugin.isRedisEnabled() || plugin.redis() == null) return;
                plugin.redis().sync().del(key);
            } catch (Exception ignored) {}
        });
    }

    private static final class Attempt {
        private final SorekillRTPPlugin plugin;
        private final Player player;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private BukkitTask monitorTask;

        // Monitor runs every 4 ticks.
        // We require ~1.0s of stillness before arming a baseline to reduce “too quick” cancels.
        private static final int REQUIRED_STABLE_SAMPLES = 5; // 5 samples * 4 ticks = 20 ticks = 1s
        private static final long MONITOR_PERIOD_TICKS = 4L;
        private static final long MONITOR_INITIAL_DELAY_TICKS = 4L;

        // We only cancel-on-move once countdown begins.
        private volatile boolean cancelOnMoveEnabled = false;

        // baseline once armed
        private volatile boolean armed = false;
        private volatile String baseWorld;
        private volatile int baseBx;
        private volatile int baseBy;
        private volatile int baseBz;

        // NEW: exact baseline Y so jumping cancels even if blockY doesn't change
        private volatile double baseY;

        // last observed (for stability tracking)
        private volatile String lastWorld;
        private volatile int lastBx;
        private volatile int lastBy;
        private volatile int lastBz;
        private volatile int stableSamples = 0;

        Attempt(SorekillRTPPlugin plugin, Player player) {
            this.plugin = plugin;
            this.player = player;

            Location start = player.getLocation();
            String w = start.getWorld() == null ? "" : start.getWorld().getName();

            this.lastWorld = w;
            this.lastBx = start.getBlockX();
            this.lastBy = start.getBlockY();
            this.lastBz = start.getBlockZ();
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void enableCancelOnMove() {
            this.cancelOnMoveEnabled = true;
        }

        void startMonitor(Runnable onCancelMainThread) {
            armed = false;
            stableSamples = 0;
            cancelOnMoveEnabled = false;

            Location now0 = player.getLocation();
            String w0 = now0.getWorld() == null ? "" : now0.getWorld().getName();
            lastWorld = w0;
            lastBx = now0.getBlockX();
            lastBy = now0.getBlockY();
            lastBz = now0.getBlockZ();

            if (monitorTask != null) {
                monitorTask.cancel();
                monitorTask = null;
            }

            this.monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (cancelled.get()) {
                    if (monitorTask != null) monitorTask.cancel();
                    return;
                }
                if (player == null || !player.isOnline()) {
                    cancelSilently();
                    return;
                }

                Location now = player.getLocation();
                String nowWorld = now.getWorld() == null ? "" : now.getWorld().getName();
                int bx = now.getBlockX();
                int by = now.getBlockY();
                int bz = now.getBlockZ();

                // Build a stable baseline (does NOT cancel yet)
                if (!armed) {
                    boolean sameAsLast =
                            nowWorld.equals(lastWorld) &&
                                    bx == lastBx && by == lastBy && bz == lastBz;

                    if (sameAsLast) {
                        stableSamples++;
                    } else {
                        stableSamples = 0;
                        lastWorld = nowWorld;
                        lastBx = bx;
                        lastBy = by;
                        lastBz = bz;
                    }

                    if (stableSamples >= REQUIRED_STABLE_SAMPLES) {
                        armed = true;
                        baseWorld = nowWorld;
                        baseBx = bx;
                        baseBy = by;
                        baseBz = bz;

                        // NEW: capture exact Y baseline for jump detection
                        baseY = now.getY();
                    }
                    return;
                }

                if (!cancelOnMoveEnabled) return;

                boolean movedWorld = !nowWorld.equals(baseWorld);
                boolean movedBlock = bx != baseBx || by != baseBy || bz != baseBz;

                // NEW: jumping cancels (upward Y movement beyond a small threshold)
                boolean jumpedUp = now.getY() > baseY + 0.20;

                if (movedWorld || movedBlock || jumpedUp) {
                    cancelled.set(true);
                    if (monitorTask != null) monitorTask.cancel();
                    onCancelMainThread.run();
                }
            }, MONITOR_INITIAL_DELAY_TICKS, MONITOR_PERIOD_TICKS);
        }

        void finish() {
            cancelled.set(true);
            if (monitorTask != null) {
                monitorTask.cancel();
                monitorTask = null;
            }
        }

        void cancelSilently() {
            cancelled.set(true);
            if (monitorTask != null) {
                monitorTask.cancel();
                monitorTask = null;
            }
        }
    }
}
