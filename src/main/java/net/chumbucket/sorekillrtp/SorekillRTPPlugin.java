/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp;

import net.chumbucket.sorekillrtp.command.RtpCommand;
import net.chumbucket.sorekillrtp.config.PluginConfig;
import net.chumbucket.sorekillrtp.listener.DeathRtpListener;
import net.chumbucket.sorekillrtp.listener.JoinFinalizeListener;
import net.chumbucket.sorekillrtp.listener.PresenceListener;
import net.chumbucket.sorekillrtp.listener.SpawnSyncListener;
import net.chumbucket.sorekillrtp.msg.MessageService;
import net.chumbucket.sorekillrtp.redis.RedisManager;
import net.chumbucket.sorekillrtp.rtp.RtpService;
import net.chumbucket.sorekillrtp.velocity.VelocityConnector;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SorekillRTPPlugin extends JavaPlugin {

    private PluginConfig cfg;
    private MessageService messages;
    private VelocityConnector velocity;

    private RedisManager redis; // nullable if redis disabled or failed
    private RtpService rtpService;

    // Keep listener instances so we can stop any background work on reload
    private JoinFinalizeListener joinFinalizeListener;
    private PresenceListener presenceListener;
    private SpawnSyncListener spawnSyncListener;
    private DeathRtpListener deathRtpListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");

        // Load config + services
        this.cfg = PluginConfig.load(getConfig());
        this.messages = MessageService.load(this);
        this.velocity = new VelocityConnector(this);

        // Register outgoing plugin channel (needed for Velocity connect)
        registerVelocityChannel();

        // Optional Redis
        restartRedisFromConfig(true);

        // RTP service (works even if Redis is down; remote features will no-op)
        this.rtpService = new RtpService(this);

        // Listeners
        registerListenersFresh();

        // Command
        PluginCommand rtp = getCommand("rtp");
        if (rtp == null) {
            getLogger().severe("Command 'rtp' missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RtpCommand handler = new RtpCommand(this);
        rtp.setExecutor(handler);
        rtp.setTabCompleter(handler);

        getLogger().info("SorekillRTP enabled (" + cfg.serverName() + ")");
    }

    @Override
    public void onDisable() {
        try {
            // clear bossbars / cancel message tasks (if implemented)
            if (messages != null) messages.shutdown();
        } catch (Exception ignored) {}

        try {
            // stop background tasks/threads first
            stopListenerBackgroundWork();
        } catch (Exception ignored) {}

        try {
            // unregister ALL handlers from this plugin, including dynamic ones
            HandlerList.unregisterAll(this);
        } catch (Exception ignored) {}

        try {
            unregisterVelocityChannel();
        } catch (Exception ignored) {}

        try {
            if (redis != null) redis.stop();
        } catch (Exception ignored) {}

        redis = null;
        joinFinalizeListener = null;
        presenceListener = null;
        spawnSyncListener = null;
        deathRtpListener = null;

        getLogger().info("SorekillRTP disabled");
    }

    /**
     * Reloads config.yml + messages.yml, and restarts Redis so connection changes apply.
     * Safe to call from command handlers.
     */
    public void reloadAll() {
        // stop bossbars / message state BEFORE replacing messages
        try {
            if (this.messages != null) this.messages.shutdown();
        } catch (Exception ignored) {}

        // Stop background threads before unregistering
        stopListenerBackgroundWork();

        // Unregister EVERYTHING from this plugin (including dynamic Paper hook)
        HandlerList.unregisterAll(this);

        // Reload config + messages
        reloadConfig();
        this.cfg = PluginConfig.load(getConfig());

        saveResourceIfMissing("messages.yml");
        this.messages = MessageService.load(this);

        // Re-register messaging channel
        unregisterVelocityChannel();
        registerVelocityChannel();

        // Restart redis based on new config (don't disable plugin if it fails)
        restartRedisFromConfig(false);

        // Rebuild RTP service (finder caches etc)
        this.rtpService = new RtpService(this);

        // Re-register listeners and dynamic Paper hook
        registerListenersFresh();

        getLogger().info("SorekillRTP reloaded (" + cfg.serverName() + ")");
    }

    private void registerListenersFresh() {
        PluginManager pm = getServer().getPluginManager();

        this.joinFinalizeListener = new JoinFinalizeListener(this);
        this.presenceListener = new PresenceListener(this);
        this.spawnSyncListener = new SpawnSyncListener(this);
        this.deathRtpListener = new DeathRtpListener(this);

        pm.registerEvents(this.joinFinalizeListener, this);
        pm.registerEvents(this.presenceListener, this);
        pm.registerEvents(this.spawnSyncListener, this);
        pm.registerEvents(this.deathRtpListener, this);

        // dynamic Paper spawn-set hook (must be re-run after reload)
        try {
            this.spawnSyncListener.registerPaperSpawnSetHook();
        } catch (Exception e) {
            getLogger().warning("SpawnSyncListener Paper hook failed: " + e.getMessage());
        }
    }

    private void stopListenerBackgroundWork() {
        if (presenceListener != null) {
            try { presenceListener.stop(); } catch (Exception ignored) {}
        }
    }

    private void registerVelocityChannel() {
        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, VelocityConnector.CHANNEL);
        } catch (Exception e) {
            // Don't hard-disable plugin; remote switching will simply fail gracefully.
            getLogger().warning("Failed to register Velocity plugin channel: " + e.getMessage());
        }
    }

    private void unregisterVelocityChannel() {
        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, VelocityConnector.CHANNEL);
        } catch (Exception ignored) {}
    }

    /**
     * Start/stop Redis manager based on config.
     * If `failHard` is true, plugin disables itself if Redis was requested but can't start.
     */
    private void restartRedisFromConfig(boolean failHard) {
        // stop old one
        try {
            if (this.redis != null) this.redis.stop();
        } catch (Exception ignored) {}
        this.redis = null;

        if (cfg == null || cfg.redis() == null || !cfg.redis().enabled()) {
            getLogger().warning("Redis disabled in config (redis.enabled=false). Cross-server RTP will be unavailable.");
            return;
        }

        RedisManager mgr = new RedisManager(this);
        try {
            mgr.start();
            this.redis = mgr;
            getLogger().info("Redis enabled (cross-server RTP available)");
        } catch (Exception e) {
            this.redis = null;
            getLogger().severe("Failed to start Redis: " + e.getMessage());
            if (failHard) {
                getServer().getPluginManager().disablePlugin(this);
            }
        }
    }

    public PluginConfig cfg() { return cfg; }
    public MessageService messages() { return messages; }
    public VelocityConnector velocity() { return velocity; }

    /** May be null if redis.enabled=false or Redis failed to start. */
    public RedisManager redis() { return redis; }

    public RtpService rtp() { return rtpService; }

    /**
     * True only when Redis is both enabled in config AND actually started.
     */
    public boolean isRedisEnabled() {
        if (cfg == null || cfg.redis() == null || !cfg.redis().enabled()) return false;
        if (redis == null) return false;
        return redis.isRunning(); // now compiles because we added it
    }

    private void saveResourceIfMissing(String resourceName) {
        if (getResource(resourceName) == null) {
            getLogger().warning("Resource not found in jar: " + resourceName);
            return;
        }
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File out = new File(getDataFolder(), resourceName);
        if (!out.exists()) saveResource(resourceName, false);
    }
}
