/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.command;

import net.chumbucket.sorekillrtp.SorekillRTPPlugin;
import net.chumbucket.sorekillrtp.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class RtpCommand implements CommandExecutor, TabCompleter {

    private final SorekillRTPPlugin plugin;
    private final Random random = new Random();

    public RtpCommand(SorekillRTPPlugin plugin) {
        this.plugin = plugin;
    }

    // Aliases users can type
    private static final List<String> WORLD_ALIASES = List.of("overworld", "nether", "end");

    // Bukkit folder names that are covered by aliases (don't show them in tab complete)
    private static final Set<String> ALIASED_WORLD_NAMES = Set.of(
            "world",
            "world_nether",
            "world_the_end"
    );

    private boolean isWorldAlias(String s) {
        if (s == null) return false;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "overworld", "nether", "end" -> true;
            default -> false;
        };
    }

    private boolean canReload(CommandSender sender) {
        return sender.hasPermission("sorekillrtp.reload") || sender.hasPermission("sorekillrtp.admin");
    }

    /**
     * Maps friendly tokens -> actual Bukkit world folder names.
     */
    private String mapWorldAlias(String worldToken) {
        if (worldToken == null) return null;
        String s = worldToken.trim();
        if (s.isEmpty()) return null;

        return switch (s.toLowerCase(Locale.ROOT)) {
            case "overworld" -> "world";
            case "nether" -> "world_nether";
            case "end" -> "world_the_end";
            default -> s;
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /rtp reload  (console or player)
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!canReload(sender)) {
                plugin.messages().send(sender, "errors.no-permission");
                return true;
            }

            try {
                plugin.reloadAll();
                plugin.messages().send(sender, "admin.reloaded");
            } catch (Exception e) {
                plugin.getLogger().severe("Reload failed: " + e.getMessage());
                e.printStackTrace();
                plugin.messages().send(sender, "errors.reload-failed");
            }
            return true;
        }

        // Everything below here requires a player
        if (!(sender instanceof Player senderPlayer)) {
            plugin.messages().send(sender, "errors.player-only");
            return true;
        }

        PluginConfig cfg = plugin.cfg();
        PluginConfig.RtpConfig rtp = cfg.rtp();

        // Admin form: /rtp <player> [server|worldAlias] [world|worldAlias]
        boolean isAdminSender = sender.hasPermission("sorekillrtp.admin");
        Player maybeTarget = (args.length >= 1 && isAdminSender) ? Bukkit.getPlayerExact(args[0]) : null;

        if (maybeTarget != null) {
            Player target = maybeTarget;

            String server = null;
            String world = null;

            if (args.length >= 2) {
                String arg2 = args[1];

                boolean arg2IsServer = rtp.getServer(arg2).isPresent();
                boolean arg2IsAlias = isWorldAlias(arg2);

                if (arg2IsServer) {
                    // /rtp <player> <server> [world]
                    server = arg2;
                    world = (args.length >= 3) ? args[2] : null;
                } else if (arg2IsAlias) {
                    // /rtp <player> nether|end|overworld (world shortcut on default server)
                    server = chooseDefaultServerForTarget();
                    if (server == null) {
                        plugin.messages().send(sender, "errors.no-enabled-backends");
                        return true;
                    }
                    world = arg2;
                } else {
                    plugin.messages().send(sender, "errors.unknown-server", Map.of("server", arg2));
                    return true;
                }
            }

            // Choose server if omitted
            if (server == null || server.isBlank()) {
                server = chooseDefaultServerForTarget();
                if (server == null) {
                    plugin.messages().send(sender, "errors.no-enabled-backends");
                    return true;
                }
            } else {
                if (!validateServer(server, sender)) return true;
            }

            // Resolve world (apply alias mapping first)
            world = mapWorldAlias(world);
            world = resolveWorldOrDefault(server, world, sender);
            if (world == null) return true;

            plugin.rtp().startRtp(target, sender, server, world, true);
            return true;
        }

        // SELF MODE
        if (args.length == 0) {
            String server = chooseDefaultServerForTarget();
            if (server == null) {
                plugin.messages().send(senderPlayer, "errors.no-enabled-backends");
                return true;
            }

            String world = resolveWorldOrDefault(server, null, senderPlayer);
            if (world == null) return true;

            plugin.rtp().startRtp(senderPlayer, senderPlayer, server, world, false);
            return true;
        }

        if (args.length == 1) {
            String arg1 = args[0];

            // If arg is a real server name, treat it as server
            if (rtp.getServer(arg1).isPresent()) {
                String server = arg1;
                if (!validateServer(server, senderPlayer)) return true;

                String world = resolveWorldOrDefault(server, null, senderPlayer);
                if (world == null) return true;

                plugin.rtp().startRtp(senderPlayer, senderPlayer, server, world, false);
                return true;
            }

            // Otherwise allow /rtp nether|end|overworld as world shortcut on default server
            if (isWorldAlias(arg1)) {
                String server = chooseDefaultServerForTarget();
                if (server == null) {
                    plugin.messages().send(senderPlayer, "errors.no-enabled-backends");
                    return true;
                }
                if (!validateServer(server, senderPlayer)) return true;

                String world = mapWorldAlias(arg1);
                world = resolveWorldOrDefault(server, world, senderPlayer);
                if (world == null) return true;

                plugin.rtp().startRtp(senderPlayer, senderPlayer, server, world, false);
                return true;
            }

            plugin.messages().send(senderPlayer, "errors.unknown-server", Map.of("server", arg1));
            return true;
        }

        if (args.length >= 2) {
            String server = args[0];
            String world = mapWorldAlias(args[1]);

            if (!validateServer(server, senderPlayer)) return true;

            world = resolveWorldOrDefault(server, world, senderPlayer);
            if (world == null) return true;

            plugin.rtp().startRtp(senderPlayer, senderPlayer, server, world, false);
            return true;
        }

        return false;
    }

    private String chooseDefaultServerForTarget() {
        PluginConfig cfg = plugin.cfg();
        PluginConfig.RtpConfig rtp = cfg.rtp();

        // If local is enabled, always prefer it.
        if (rtp.isServerEnabled(cfg.serverName())) {
            return cfg.serverName();
        }

        // If Redis is not available, we cannot RTP cross-server.
        if (!plugin.isRedisEnabled()) {
            return null;
        }

        List<String> enabled = rtp.fallbackEnabledServers().stream()
                .filter(rtp::isServerEnabled)
                .toList();

        if (enabled.isEmpty()) return null;

        return switch (rtp.fallbackMode()) {
            case FIRST -> enabled.get(0);
            case RANDOM -> enabled.get(random.nextInt(enabled.size()));
        };
    }

    private boolean validateServer(String server, CommandSender feedbackTo) {
        PluginConfig.RtpConfig rtp = plugin.cfg().rtp();

        if (rtp.getServer(server).isEmpty()) {
            plugin.messages().send(feedbackTo, "errors.unknown-server", Map.of("server", server));
            return false;
        }
        if (!rtp.isServerEnabled(server)) {
            plugin.messages().send(feedbackTo, "errors.server-disabled", Map.of("server", server));
            return false;
        }
        if (!plugin.cfg().serverName().equalsIgnoreCase(server) && !plugin.isRedisEnabled()) {
            plugin.messages().send(feedbackTo, "errors.no-enabled-backends"); // or errors.redis-disabled if you add it
            return false;
        }
        return true;
    }

    private String resolveWorldOrDefault(String server, String world, CommandSender feedbackTo) {
        PluginConfig.RtpConfig rtp = plugin.cfg().rtp();
        PluginConfig.ServerRtp srv = rtp.getServer(server).orElse(null);
        if (srv == null) {
            plugin.messages().send(feedbackTo, "errors.unknown-server", Map.of("server", server));
            return null;
        }

        String resolved = (world == null || world.isBlank()) ? srv.defaultWorld() : world;

        if (srv.getWorld(resolved).isEmpty()) {
            plugin.messages().send(feedbackTo, "errors.unknown-world", Map.of("server", server, "world", resolved));
            return null;
        }
        if (!srv.isWorldEnabled(resolved)) {
            plugin.messages().send(feedbackTo, "errors.world-disabled", Map.of("server", server, "world", resolved));
            return null;
        }

        return resolved;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        PluginConfig cfg = plugin.cfg();
        PluginConfig.RtpConfig rtp = cfg.rtp();

        if (args.length == 1) {
            List<String> out = new ArrayList<>();

            // show reload for admins/ops that have permission
            if (canReload(sender)) out.add("reload");

            if (sender.hasPermission("sorekillrtp.admin")) {
                out.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            }

            out.addAll(rtp.servers().keySet().stream().filter(rtp::isServerEnabled).toList());
            out.addAll(WORLD_ALIASES);

            return prefixFilter(out, args[0]);
        }

        if (args.length == 2) {
            // Could be:
            // - /rtp <server> <world>
            // - /rtp <player> <server|alias> (admin)
            if (sender.hasPermission("sorekillrtp.admin") && Bukkit.getPlayerExact(args[0]) != null) {
                List<String> out = new ArrayList<>();
                out.addAll(rtp.servers().keySet().stream().filter(rtp::isServerEnabled).toList());
                out.addAll(WORLD_ALIASES);
                return prefixFilter(out, args[1]);
            }

            // completing world after server (include aliases; hide aliased world folder names)
            return rtp.getServer(args[0])
                    .map(srv -> {
                        List<String> out = new ArrayList<>();

                        // Add ONLY non-aliased world ids from config
                        for (String w : srv.worlds().keySet()) {
                            if (!isAliasedWorldName(w)) out.add(w);
                        }

                        // Add friendly aliases
                        out.addAll(WORLD_ALIASES);

                        return prefixFilter(out, args[1]);
                    })
                    .orElse(List.of());
        }

        if (args.length == 3) {
            // admin: /rtp <player> <server> <world>
            if (sender.hasPermission("sorekillrtp.admin") && Bukkit.getPlayerExact(args[0]) != null) {
                return rtp.getServer(args[1])
                        .map(srv -> {
                            List<String> out = new ArrayList<>();

                            for (String w : srv.worlds().keySet()) {
                                if (!isAliasedWorldName(w)) out.add(w);
                            }

                            out.addAll(WORLD_ALIASES);

                            return prefixFilter(out, args[2]);
                        })
                        .orElse(List.of());
            }
        }

        return List.of();
    }

    private boolean isAliasedWorldName(String worldName) {
        if (worldName == null) return false;
        return ALIASED_WORLD_NAMES.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static List<String> prefixFilter(List<String> items, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return items.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}
