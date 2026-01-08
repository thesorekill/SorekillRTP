/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.msg;

import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MessageService {

    private static final LegacyComponentSerializer LEGACY_AMP =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .build();

    private final SorekillRTPPlugin plugin;
    private final FileConfiguration messages;

    private final boolean chatEnabled;
    private final boolean actionbarEnabled;
    private final boolean bossbarEnabled;
    private final boolean titleEnabled;
    private final boolean toastEnabled;

    private final Title.Times titleTimes;

    // ---- BossBar config ----
    private final int bossbarSeconds;
    private final float bossbarProgress;
    private final BossBar.Color bossbarColor;
    private final BossBar.Overlay bossbarOverlay;

    // ---- BossBar state (per-player) ----
    private final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> bossbarHideTasks = new ConcurrentHashMap<>();

    private MessageService(SorekillRTPPlugin plugin,
                           FileConfiguration messages,
                           boolean chatEnabled,
                           boolean actionbarEnabled,
                           boolean bossbarEnabled,
                           boolean titleEnabled,
                           boolean toastEnabled,
                           Title.Times titleTimes,
                           int bossbarSeconds,
                           float bossbarProgress,
                           BossBar.Color bossbarColor,
                           BossBar.Overlay bossbarOverlay) {
        this.plugin = plugin;
        this.messages = messages;
        this.chatEnabled = chatEnabled;
        this.actionbarEnabled = actionbarEnabled;
        this.bossbarEnabled = bossbarEnabled;
        this.titleEnabled = titleEnabled;
        this.toastEnabled = toastEnabled;
        this.titleTimes = titleTimes;

        this.bossbarSeconds = bossbarSeconds;
        this.bossbarProgress = bossbarProgress;
        this.bossbarColor = bossbarColor;
        this.bossbarOverlay = bossbarOverlay;
    }

    public static MessageService load(SorekillRTPPlugin plugin) {
        File msgFile = new File(plugin.getDataFolder(), "messages.yml");
        FileConfiguration msgCfg = YamlConfiguration.loadConfiguration(msgFile);

        boolean chat = plugin.getConfig().getBoolean("messages.chat", true);
        boolean actionbar = plugin.getConfig().getBoolean("messages.actionbar", false);
        boolean bossbar = plugin.getConfig().getBoolean("messages.bossbar", false);

        boolean title = plugin.getConfig().getBoolean("messages.title", false);
        boolean toast = plugin.getConfig().getBoolean("messages.toast", false);

        int fadeInTicks = Math.max(0, plugin.getConfig().getInt("messages.title_fade_in_ticks", 10));
        int stayTicks   = Math.max(0, plugin.getConfig().getInt("messages.title_stay_ticks", 40));
        int fadeOutTicks = Math.max(0, plugin.getConfig().getInt("messages.title_fade_out_ticks", 10));

        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeInTicks * 50L),
                Duration.ofMillis(stayTicks * 50L),
                Duration.ofMillis(fadeOutTicks * 50L)
        );

        // Bossbar knobs (safe defaults)
        int bbSeconds = plugin.getConfig().getInt("messages.bossbar_seconds", 5);
        if (bbSeconds < 0) bbSeconds = 0;

        double bbProgD = plugin.getConfig().getDouble("messages.bossbar_progress", 1.0);
        float bbProg = (float) Math.max(0.0, Math.min(1.0, bbProgD));

        String bbColorRaw = plugin.getConfig().getString("messages.bossbar_color", "BLUE");
        BossBar.Color bbColor = parseBossBarColor(bbColorRaw, BossBar.Color.BLUE);

        String bbOverlayRaw = plugin.getConfig().getString("messages.bossbar_overlay", "PROGRESS");
        BossBar.Overlay bbOverlay = parseBossBarOverlay(bbOverlayRaw, BossBar.Overlay.PROGRESS);

        return new MessageService(
                plugin,
                msgCfg,
                chat,
                actionbar,
                bossbar,
                title,
                toast,
                times,
                bbSeconds,
                bbProg,
                bbColor,
                bbOverlay
        );
    }

    public void send(CommandSender to, String path) {
        send(to, path, null);
    }

    public void send(CommandSender to, String path, Map<String, String> placeholders) {
        if (to == null || path == null || path.isBlank()) return;

        List<String> lines = resolveLines(path);
        if (lines.isEmpty()) return;

        Map<String, String> ph = (placeholders == null) ? Collections.emptyMap() : placeholders;

        // Build multi-line chat component
        Component chatComponent = Component.empty();
        boolean first = true;

        for (String rawLine : lines) {
            String line = applyPlaceholders(rawLine, ph);
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty()) continue;

            Component c = LEGACY_AMP.deserialize(line);
            if (first) {
                chatComponent = c;
                first = false;
            } else {
                chatComponent = chatComponent.append(Component.newline()).append(c);
            }
        }

        if (first) return; // everything became blank

        Audience audience = (to instanceof Audience a) ? a : null;

        // Fallback path without ChatColor (no deprecations)
        if (audience == null) {
            if (chatEnabled) {
                for (String rawLine : lines) {
                    String line = applyPlaceholders(rawLine, ph);
                    if (line == null) continue;
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    Component c = LEGACY_AMP.deserialize(line);
                    String legacy = LEGACY_AMP.serialize(c);
                    to.sendMessage(legacy);
                }
            }
            return;
        }

        if (chatEnabled) {
            audience.sendMessage(chatComponent);
        }

        Component single = null;

        if ((actionbarEnabled || bossbarEnabled || titleEnabled || toastEnabled) && to instanceof Player) {
            single = firstNonEmptyLineComponent(lines, ph);
        }

        if (actionbarEnabled && to instanceof Player p) {
            if (single != null) p.sendActionBar(single);
        }

        if (bossbarEnabled && to instanceof Player p) {
            if (single != null) showBossBar(p, single);
        }

        if (titleEnabled && to instanceof Player) {
            if (single != null) {
                Title title = Title.title(single, Component.empty(), titleTimes);
                audience.showTitle(title);
            }
        }

        if (toastEnabled && to instanceof Player p) {
            if (single != null) showToastBestEffort(p, single);
        }
    }

    private List<String> resolveLines(String path) {
        Object o = messages.get(path);

        if (o instanceof String s) {
            s = (s == null) ? "" : s.trim();
            if (s.isEmpty()) return List.of();
            return List.of(s);
        }

        if (o instanceof List<?> list) {
            return list.stream()
                    .map(x -> x == null ? "" : String.valueOf(x))
                    .toList();
        }

        return List.of();
    }

    private static String applyPlaceholders(String raw, Map<String, String> placeholders) {
        if (raw == null) return null;
        String msg = raw;

        if (!placeholders.isEmpty()) {
            for (var e : placeholders.entrySet()) {
                String key = e.getKey();
                if (key == null || key.isBlank()) continue;
                String val = (e.getValue() == null) ? "" : e.getValue();
                msg = msg.replace("{" + key + "}", val);
            }
        }
        return msg;
    }

    private Component firstNonEmptyLineComponent(List<String> lines, Map<String, String> placeholders) {
        for (String rawLine : lines) {
            String line = applyPlaceholders(rawLine, placeholders);
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty()) continue;
            return LEGACY_AMP.deserialize(line);
        }
        return null;
    }

    // ---------------- BossBar implementation ----------------

    private void showBossBar(Player player, Component name) {
        if (player == null || !player.isOnline()) return;

        // If "0 seconds", treat as disabled (don’t show)
        if (bossbarSeconds <= 0) return;

        UUID uuid = player.getUniqueId();

        Runnable doShow = () -> {
            if (!player.isOnline()) return;

            // Cancel any pending hide
            BukkitTask oldHide = bossbarHideTasks.remove(uuid);
            if (oldHide != null) oldHide.cancel();

            BossBar bar = activeBossBars.get(uuid);
            if (bar == null) {
                bar = BossBar.bossBar(name, bossbarProgress, bossbarColor, bossbarOverlay);
                activeBossBars.put(uuid, bar);

                // Show
                player.showBossBar(bar);
            } else {
                // Update existing
                bar.name(name);
                bar.progress(bossbarProgress);
                bar.color(bossbarColor);
                bar.overlay(bossbarOverlay);

                // Ensure it’s visible (safe even if already visible)
                player.showBossBar(bar);
            }

            // Schedule auto-hide
            BukkitTask hideTask = Bukkit.getScheduler().runTaskLater(plugin, () -> hideBossBar(player), bossbarSeconds * 20L);
            bossbarHideTasks.put(uuid, hideTask);
        };

        // BossBar show/hide should be on main thread
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, doShow);
        } else {
            doShow.run();
        }
    }

    private void hideBossBar(Player player) {
        if (player == null) return;

        UUID uuid = player.getUniqueId();

        // Cancel any pending hide (we're executing it now anyway)
        BukkitTask t = bossbarHideTasks.remove(uuid);
        if (t != null) t.cancel();

        BossBar bar = activeBossBars.remove(uuid);
        if (bar == null) return;

        if (!player.isOnline()) return;

        // Must be main thread
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.hideBossBar(bar);
            });
            return;
        }

        player.hideBossBar(bar);
    }

    /**
     * Optional: call this from plugin.onDisable() if you want to force-clear bossbars.
     * Not required, but nice if you reload a lot.
     */
    public void shutdown() {
        // Must be main thread to hide bars on players safely
        Runnable r = () -> {
            for (var e : bossbarHideTasks.entrySet()) {
                try { e.getValue().cancel(); } catch (Exception ignored) {}
            }
            bossbarHideTasks.clear();

            for (var e : activeBossBars.entrySet()) {
                UUID uuid = e.getKey();
                BossBar bar = e.getValue();
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    try { p.hideBossBar(bar); } catch (Exception ignored) {}
                }
            }
            activeBossBars.clear();
        };

        if (!Bukkit.isPrimaryThread()) Bukkit.getScheduler().runTask(plugin, r);
        else r.run();
    }

    private static BossBar.Color parseBossBarColor(String raw, BossBar.Color def) {
        if (raw == null) return def;
        try {
            return BossBar.Color.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static BossBar.Overlay parseBossBarOverlay(String raw, BossBar.Overlay def) {
        if (raw == null) return def;
        try {
            return BossBar.Overlay.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return def;
        }
    }

    // ---------------- Toast ----------------

    private void showToastBestEffort(Player player, Component component) {
        try {
            try {
                Method m = player.getClass().getMethod("sendToast", Component.class);
                m.invoke(player, component);
                return;
            } catch (NoSuchMethodException ignored) {}

            try {
                Method m = player.getClass().getMethod("showToast", Component.class);
                m.invoke(player, component);
            } catch (NoSuchMethodException ignored) {}

        } catch (Throwable t) {
            plugin.getLogger().fine("Toast failed (ignored): " + t.getMessage());
        }
    }
}
