/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/commands/MinerTrackCommand.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack.commands;

import link.star_dust.MinerTrack.FoliaCheck;
import link.star_dust.MinerTrack.MinerTrack;
import link.star_dust.MinerTrack.managers.LanguageManager;
import link.star_dust.MinerTrack.utils.LogViewerUtils;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MinerTrackCommand implements CommandExecutor, TabCompleter {
    private final MinerTrack plugin;

    public MinerTrackCommand(MinerTrack plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) || sender instanceof ConsoleCommandSender) {
            // pass
        } else if (!sender.hasPermission("minertrack.use")) {
            sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (!sender.hasPermission("minertrack.help")) {
                sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                return true;
            }
            List<String> helpMessages = plugin.getLanguageManager().getHelpMessages();
            for (String message : helpMessages) {
                sender.sendMessage(plugin.getLanguageManager().applyColors(message));
            }
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "notify":
                if (!sender.hasPermission("minertrack.sendnotify")) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("usage-notify"));
                    return true;
                }
                String messageContent = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                plugin.getNotifier().sendNotifyMessage(messageContent);
                break;

            case "verbose":
                if (!sender.hasPermission("minertrack.verbose")) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                    return true;
                }
                plugin.toggleVerboseMode(sender);
                break;

            case "check":
                if (!sender.hasPermission("minertrack.check")) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("usage-check"));
                    return true;
                }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target != null) {
                    int violationLevel = plugin.getViolationManager().getViolationLevel(target);
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("violation-level")
                            .replace("{player}", target.getName())
                            .replace("{level}", String.valueOf(violationLevel)));
                } else {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("player-not-found")
                            .replace("{player}", args[1]));
                }
                break;

            case "reset":
                if (!sender.hasPermission("minertrack.reset")) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("usage-reset"));
                    return true;
                }
                Player targetToReset = plugin.getServer().getPlayer(args[1]);
                if (targetToReset != null) {
                    plugin.getViolationManager().resetViolationLevel(targetToReset);
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("reset-success")
                            .replace("{player}", args[1]));
                } else {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("player-not-found")
                            .replace("{player}", args[1]));
                }
                break;

            case "kick":
                if (!sender.hasPermission("minertrack.kick")) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("usage-kick"));
                    return true;
                }

                Player playerToKick = plugin.getServer().getPlayer(args[1]);
                if (playerToKick != null) {
                    String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

                    if (plugin.getConfigManager().isKickStrikeLightning()) {
                        if (FoliaCheck.isFolia()) {
                            // Use Folia's region-based scheduler to execute the task safely
                            Bukkit.getRegionScheduler().execute(plugin, playerToKick.getLocation(), () -> {
                                try {
                                    playerToKick.getWorld().strikeLightningEffect(playerToKick.getLocation());
                                } catch (Exception e) {
                                    plugin.getLogger().severe("Failed to strike lightning effect on Folia: " + e.getMessage());
                                    e.printStackTrace(); // Log the full stack trace for debugging
                                }
                            });
                        } else {
                            // Non-Folia servers can execute the task directly
                            try {
                                playerToKick.getWorld().strikeLightningEffect(playerToKick.getLocation());
                            } catch (Exception e) {
                                plugin.getLogger().severe("Failed to strike lightning effect: " + e.getMessage());
                                e.printStackTrace(); // Log the full stack trace for debugging
                            }
                        }
                    }

                    if (plugin.getLanguageManager().isKickBroadcastEnabled()) {
                        String kickMessage = plugin.getLanguageManager().getPrefixedMessage("kick-format")
                            .replace("%player%", playerToKick.getName())
                            .replace("%reason%", reason);
                        plugin.getServer().broadcastMessage(kickMessage);
                    }
                    
                    plugin.getNotifier().kickPlayer(playerToKick, reason);

                } else {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("player-not-found")
                            .replace("{player}", args[1]));
                }
                break;

            case "reload":
                if (!sender.hasPermission("minertrack.reload")) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                    return true;
                }
                plugin.getConfigManager().reloadConfig();
                plugin.getLanguageManager().reloadLanguageFile();
                sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("config-reloaded"));
                break;

            case "update":
                if (!sender.hasPermission("minertrack.checkupdate")) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                    return true;
                }
                plugin.checkForUpdates(sender);
                break;

            case "logs":
                if (!sender.hasPermission("minertrack.logs")) {
                    sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("no-permission"));
                    return true;
                }
                if (args.length == 2) {
                    String logName = args[1];
                    if (!logName.endsWith(".log")) {
                        sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("log-viewer-not-log-file"));
                        return true;
                    }
                    File logDir = new File(plugin.getDataFolder(), "logs");
                    File logFile = new File(logDir, logName);
                    if (!logFile.exists() || !logFile.isFile()) {
                        sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("log-viewer-not-found").replace("{log_file}", logName));
                        return true;
                    }
                    try {
                        List<String> lines = LogViewerUtils.readLogFile(logFile);
                        List<String> reversedLines = new ArrayList<>(lines);
                        java.util.Collections.reverse(reversedLines);
                        int perPage = plugin.getLanguageManager().getLogViewerLinesPerPage();
                        int totalPages = LogViewerUtils.getTotalPages(reversedLines.size(), perPage);
                        int page = 1;
                        LogViewerUtils.LogCache cache = new LogViewerUtils.LogCache(logName, reversedLines, totalPages, page);
                        LogViewerUtils.putCache(sender, cache);
                        sendLogPage(sender, cache, perPage);
                    } catch (IOException e) {
                        sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("log-viewer-cant-read"));
                    }
                    return true;
                } else if (args.length == 3 && args[1].equalsIgnoreCase("page")) {
                    LogViewerUtils.LogCache cache = LogViewerUtils.getCache(sender);
                    if (cache == null) {
                        sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("usage-logs"));
                        return true;
                    }
                    int page;
                    try {
                        page = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("log-viewer-page-nan"));
                        return true;
                    }
                    if (page < 1 || page > cache.totalPages) {
                        sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("log-viewer-page-invalid").replace("{max_page}", String.valueOf(cache.totalPages)));
                        return true;
                    }
                    cache.currentPage = page;
                    sendLogPage(sender, cache, plugin.getLanguageManager().getLogViewerLinesPerPage());
                    return true;
                }
                sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("usage-logs"));
                break;

            default:
                sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("unknown-command"));
                break;
        }
        return true;
    }

    private void sendLogPage(CommandSender sender, LogViewerUtils.LogCache cache, int perPage) {
        int totalLines = cache.lines.size();
        int page = cache.currentPage;
        int totalPages = cache.totalPages;
        int[] range = LogViewerUtils.getPageRange(totalLines, page, perPage);
        int start = range[0];
        int end = range[1];
        String header = plugin.getLanguageManager().getColoredMessage("log-viewer-header")
            .replace("{current_page}", String.valueOf(page))
            .replace("{max_page}", String.valueOf(totalPages))
            .replace("{log_file}", cache.logName);
        sender.sendMessage(header);
        if (start >= end) {
            sender.sendMessage(plugin.getLanguageManager().getPrefixedMessage("log-viewer-empty"));
            return;
        }
        for (int i = start; i < end; i++) {
            sender.sendMessage(plugin.getLanguageManager().getColoredMessage("log-viewer-logs-color") + cache.lines.get(i));
        }

        if (page < totalPages) {
            sender.sendMessage("");
            sender.sendMessage(plugin.getLanguageManager().getColoredMessage("log-viewer-next-page").replace("{next_page}", String.valueOf(page + 1)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.addAll(Arrays.asList("help", "notify", "verbose", "check", "reset", "kick", "reload", "update", "logs"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("kick")) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    completions.add(player.getName());
                }
            } else if (args[0].equalsIgnoreCase("logs")) {
                File logDir = new File(plugin.getDataFolder(), "logs");
                File[] files = logDir.listFiles((dir, name) -> name.endsWith(".log"));
                if (files != null) {
                    Arrays.sort(files, Comparator.comparing(File::getName).reversed());
                    int max = Math.min(10, files.length);
                    for (int i = 0; i < max; i++) {
                        completions.add(files[i].getName());
                    }
                }
            }
        }
        
        return completions;
    }

    public void clearLogCache(CommandSender sender) {
        LogViewerUtils.clearCache(sender);
    }
}