package link.star_dust.MinerTrack.core.command;

import link.star_dust.MinerTrack.common.CommandBridge;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Platform-agnostic command handler for all /minertrack subcommands.
 * Platform implementations provide LanguageBridge (messages) and
 * ViolationManagerBridge (VL operations) and CommandBridge (dispatch/send).
 */
public class MinerTrackCommandCore {

    public interface PlayerLookup {
        UUID getPlayerUUID(String name);
        String getPlayerName(UUID uuid);
        boolean isOnline(UUID uuid);
        List<String> getOnlinePlayerNames();
    }

    public interface KickBridge {
        void kickPlayer(UUID playerId, String reason);
        boolean isKickStrikeLightning();
        void strikeLightningEffect(UUID playerId);
        void broadcastMessage(String message);
    }

    public interface ConfigReloadBridge {
        void reloadConfig();
        void reloadLanguage();
    }

    public interface UpdateCheckBridge {
        void checkForUpdates(CommandBridge sender);
    }

    public interface LogViewerBridge {
        List<String> getLogFileNames(int maxFiles);
        byte[] readLogFile(String fileName);
        int getLogViewerLinesPerPage();
        String getLogFormat();
    }

    private final LanguageBridge lang;
    private final ViolationManagerBridge vl;
    private final CommandBridge cmd;
    private final PlayerLookup playerLookup;
    private final KickBridge kickBridge;
    private final ConfigReloadBridge configReload;
    private final UpdateCheckBridge updateCheck;
    private final LogViewerBridge logViewer;

    public MinerTrackCommandCore(LanguageBridge lang, ViolationManagerBridge vl,
                                  CommandBridge cmd, PlayerLookup playerLookup,
                                  KickBridge kickBridge, ConfigReloadBridge configReload,
                                  UpdateCheckBridge updateCheck, LogViewerBridge logViewer) {
        this.lang = lang;
        this.vl = vl;
        this.cmd = cmd;
        this.playerLookup = playerLookup;
        this.kickBridge = kickBridge;
        this.configReload = configReload;
        this.updateCheck = updateCheck;
        this.logViewer = logViewer;
    }

    public boolean onCommand(String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (!hasPermission("minertrack.help")) return true;
            for (String msg : lang.getHelpMessages()) {
                cmd.sendMessage(msg);
            }
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "notify": {
                if (!hasPermission("minertrack.sendnotify")) { cmd.sendFailure(lang.getPrefixedMessage("no-permission")); return true; }
                if (args.length < 2) { cmd.sendFailure(lang.getPrefixedMessage("usage-notify")); return true; }
                String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                // Translate the &-color codes in the user-supplied message
                // (e.g. "&cVL: 2" coming from the xray.commands config)
                // so the formatted notify actually renders colors in chat,
                // matching v1 behaviour (where Notifier.sendNotifyMessage
                // ran ChatColor.translateAlternateColorCodes on both the
                // prefix and the body).
                String coloredMsg = lang.applyColors(msg);
                // Send to all players with notify permission and console
                for (String pName : playerLookup.getOnlinePlayerNames()) {
                    UUID pu = playerLookup.getPlayerUUID(pName);
                    if (pu != null && hasPermissionForPlayer(pu, "minertrack.notify")) {
                        cmd.sendMessageToPlayer(pu, lang.getPrefix() + " " + coloredMsg);
                    }
                }
                cmd.sendMessageToConsole(lang.getPrefix() + " " + coloredMsg);
                break;
            }

            case "verbose": {
                if (!hasPermission("minertrack.verbose")) { cmd.sendFailure(lang.getPrefixedMessage("no-permission")); return true; }
                // toggleVerbose returns the new state so we can pick
                // the correct language-file key for the feedback
                // message (verbose-enable / verbose-disable). This
                // keeps the wording in language.yml where admins can
                // localise it, instead of being hard-coded in the
                // platform CommandBridge.
                boolean nowEnabled = cmd.toggleVerbose();
                if (nowEnabled) {
                    cmd.sendMessage(lang.getPrefixedMessage("verbose-enable"));
                } else {
                    cmd.sendMessage(lang.getPrefixedMessage("verbose-disable"));
                }
                break;
            }

            case "check": {
                if (!hasPermission("minertrack.check")) { cmd.sendFailure(lang.getPrefixedMessage("no-permission")); return true; }
                if (args.length < 2) { cmd.sendFailure(lang.getPrefixedMessage("usage-check")); return true; }
                UUID target = playerLookup.getPlayerUUID(args[1]);
                if (target != null) {
                    int level = vl.getViolationLevel(target);
                    cmd.sendSuccess(lang.getPrefixedMessage("violation-level")
                        .replace("{player}", args[1])
                        .replace("{level}", String.valueOf(level)));
                } else {
                    cmd.sendFailure(lang.getPrefixedMessage("player-not-found").replace("{player}", args[1]));
                }
                break;
            }

            case "reset": {
                if (!hasPermission("minertrack.reset")) { cmd.sendFailure(lang.getPrefixedMessage("no-permission")); return true; }
                if (args.length < 2) { cmd.sendFailure(lang.getPrefixedMessage("usage-reset")); return true; }
                UUID target = playerLookup.getPlayerUUID(args[1]);
                if (target != null) {
                    vl.resetViolation(target);
                    cmd.sendSuccess(lang.getPrefixedMessage("reset-success").replace("{player}", args[1]));
                } else {
                    cmd.sendFailure(lang.getPrefixedMessage("player-not-found").replace("{player}", args[1]));
                }
                break;
            }

            case "kick": {
                if (!hasPermission("minertrack.kick")) { cmd.sendFailure(lang.getPrefixedMessage("no-permission")); return true; }
                if (args.length < 3) { cmd.sendFailure(lang.getPrefixedMessage("usage-kick")); return true; }
                UUID target = playerLookup.getPlayerUUID(args[1]);
                if (target != null) {
                    String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    if (kickBridge.isKickStrikeLightning()) {
                        kickBridge.strikeLightningEffect(target);
                    }
                    if (lang.isKickBroadcastEnabled()) {
                        String broadcastMsg = lang.getKickFormat()
                            .replace("%player%", args[1])
                            .replace("%reason%", reason);
                        kickBridge.broadcastMessage(broadcastMsg);
                    }
                    kickBridge.kickPlayer(target, reason);
                } else {
                    cmd.sendFailure(lang.getPrefixedMessage("player-not-found").replace("{player}", args[1]));
                }
                break;
            }

            case "reload": {
                if (!hasPermission("minertrack.reload")) { cmd.sendFailure(lang.getPrefixedMessage("no-permission")); return true; }
                configReload.reloadConfig();
                configReload.reloadLanguage();
                cmd.sendSuccess(lang.getPrefixedMessage("config-reloaded"));
                break;
            }

            case "update": {
                if (!hasPermission("minertrack.checkupdate")) { cmd.sendFailure(lang.getPrefixedMessage("no-permission")); return true; }
                updateCheck.checkForUpdates(cmd);
                break;
            }

            case "logs": {
                if (!hasPermission("minertrack.logs")) { cmd.sendFailure(lang.getPrefixedMessage("no-permission")); return true; }
                if (args.length == 2) {
                    String logName = args[1];
                    if (!logName.endsWith(".log")) {
                        cmd.sendFailure(lang.getPrefixedMessage("log-viewer-not-log-file"));
                        return true;
                    }
                    byte[] data = logViewer.readLogFile(logName);
                    if (data == null) {
                        cmd.sendFailure(lang.getPrefixedMessage("log-viewer-not-found").replace("{log_file}", logName));
                        return true;
                    }
                    List<String> lines = new ArrayList<>(Arrays.asList(new String(data).split("\n")));
                    java.util.Collections.reverse(lines);
                    int perPage = logViewer.getLogViewerLinesPerPage();
                    int totalPages = (lines.size() + perPage - 1) / perPage;
                    int page = 1;
                    int start = (page - 1) * perPage;
                    int end = Math.min(start + perPage, lines.size());
                    cmd.sendMessage(lang.getColoredMessage("log-viewer-header")
                        .replace("{current_page}", String.valueOf(page))
                        .replace("{max_page}", String.valueOf(totalPages))
                        .replace("{log_file}", logName));
                    if (start >= end) {
                        cmd.sendMessage(lang.getPrefixedMessage("log-viewer-empty"));
                        return true;
                    }
                    for (int i = start; i < end; i++) {
                        cmd.sendMessage(lang.getColoredMessage("log-viewer-logs-color") + lines.get(i));
                    }
                    if (page < totalPages) {
                        cmd.sendMessage("");
                        cmd.sendMessage(lang.getColoredMessage("log-viewer-next-page").replace("{next_page}", String.valueOf(page + 1)));
                    }
                    return true;
                }
                cmd.sendMessage(lang.getPrefixedMessage("usage-logs"));
                break;
            }

            default: {
                cmd.sendMessage(lang.getPrefixedMessage("unknown-command"));
                break;
            }
        }
        return true;
    }

    public List<String> onTabComplete(String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.addAll(Arrays.asList("help", "notify", "verbose", "check", "reset", "kick", "reload", "update", "logs"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("kick")) {
                completions.addAll(playerLookup.getOnlinePlayerNames());
            } else if (args[0].equalsIgnoreCase("logs")) {
                completions.addAll(logViewer.getLogFileNames(10));
            } else if (args[0].equalsIgnoreCase("notify")) {
                // Filter to only online players who have the notify permission
                for (String pName : playerLookup.getOnlinePlayerNames()) {
                    UUID pu = playerLookup.getPlayerUUID(pName);
                    if (pu != null && hasPermissionForPlayer(pu, "minertrack.notify")) {
                        completions.add(pName);
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("logs") && args[1].equalsIgnoreCase("page")) {
            // No completions for page number — user must type it
        }
        return completions;
    }

    private boolean hasPermission(String node) {
        return cmd.hasPermission(node);
    }

    private boolean hasPermissionForPlayer(UUID playerId, String node) {
        return cmd.hasPermissionForPlayer(playerId, node);
    }
}