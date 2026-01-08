/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.velocity;

import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

public final class VelocityConnector {

    /**
     * Velocity supports the BungeeCord plugin message channel for backend->proxy actions.
     */
    public static final String CHANNEL = "BungeeCord";

    private final SorekillRTPPlugin plugin;

    public VelocityConnector(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends a connect request to Velocity (via BungeeCord plugin messaging channel).
     *
     * Returns true if the request was sent OR successfully scheduled to be sent on the main thread.
     * Returns false only if we cannot send/schedule at all (offline player, bad server, channel not registered, exception).
     */
    public boolean connect(Player player, String server) {
        if (player == null || !player.isOnline()) return false;
        if (server == null || server.isBlank()) return false;

        // Always send plugin messages from main thread.
        if (!Bukkit.isPrimaryThread()) {
            // Schedule once; don't recurse into connect() and don't report failure.
            Bukkit.getScheduler().runTask(plugin, () -> connectSync(player, server));
            return true;
        }

        return connectSync(player, server);
    }

    private boolean connectSync(Player player, String server) {
        if (player == null || !player.isOnline()) return false;
        if (server == null || server.isBlank()) return false;

        // If channel isn't registered, sending may silently fail; guard it where possible.
        try {
            if (!Bukkit.getMessenger().isOutgoingChannelRegistered(plugin, CHANNEL)) {
                plugin.getLogger().warning("Outgoing plugin channel '" + CHANNEL + "' is not registered.");
                return false;
            }
        } catch (Throwable ignored) {
            // Older APIs may not have isOutgoingChannelRegistered; proceed.
        }

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {

            out.writeUTF("Connect");
            out.writeUTF(server);
            out.flush();

            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
            return true;

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send Velocity connect plugin message: " + e.getMessage());
            return false;
        }
    }
}
