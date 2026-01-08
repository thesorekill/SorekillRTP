/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.util;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class SoundUtil {

    private SoundUtil() {}

    /**
     * Plays a Bukkit Sound enum name from config.
     *
     * Supports BOTH styles:
     *  - path: "sounds.countdown"
     *    volume at "sounds.countdown_volume" or "sounds.countdown.volume"
     *    pitch  at "sounds.countdown_pitch"  or "sounds.countdown.pitch"
     *
     * Empty/invalid sound disables. Safe clamps for volume/pitch.
     */
    public static void playConfigured(JavaPlugin plugin, Player player, String path) {
        if (plugin == null || player == null || !player.isOnline() || path == null) return;

        String raw = plugin.getConfig().getString(path, "");
        if (raw == null) return;

        raw = raw.trim();
        if (raw.isEmpty()) return;

        // Support both legacy suffix keys and nested keys.
        double volD = getDoubleCompat(plugin, path + "_volume", path + ".volume", 1.0);
        double pitD = getDoubleCompat(plugin, path + "_pitch",  path + ".pitch",  1.0);

        float volume = (float) volD;
        float pitch  = (float) pitD;

        // clamp to safe-ish ranges
        if (volume < 0f) volume = 0f;
        if (volume > 10f) volume = 10f;

        if (pitch < 0f) pitch = 0f;
        if (pitch > 2f) pitch = 2f;

        try {
            Sound sound = Sound.valueOf(raw.toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid sound at '" + path + "': " + raw);
        } catch (Exception ignored) {
            // don't break flow on sound issues
        }
    }

    private static double getDoubleCompat(JavaPlugin plugin, String keyA, String keyB, double def) {
        if (plugin.getConfig().contains(keyA)) return plugin.getConfig().getDouble(keyA, def);
        if (plugin.getConfig().contains(keyB)) return plugin.getConfig().getDouble(keyB, def);
        return def;
    }
}
