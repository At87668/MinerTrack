package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommandBridge;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.command.MinerTrackCommandCore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Bukkit command executor and tab completer for /minertrack (alias: mt, mtrack).
 * Delegates all subcommand logic to platform-agnostic MinerTrackCommandCore.
 */
public class MinerTrackCommandExecutor implements CommandExecutor, TabCompleter {

    private final BukkitAdapter adapter;
    private final DetectionBridge detectionBridge;
    private final ViolationManagerBridge vlBridge;
    private final BukkitLanguageBridge langBridge;
    private final BukkitUpdateManager updateManager;
    private final MinerTrackCommandCore core;

    public MinerTrackCommandExecutor(BukkitAdapter adapter, DetectionBridge detectionBridge,
                                     ViolationManagerBridge vlBridge, BukkitLanguageBridge langBridge,
                                     BukkitUpdateManager updateManager) {
        this.adapter = adapter;
        this.detectionBridge = detectionBridge;
        this.vlBridge = vlBridge;
        this.langBridge = langBridge;
        this.updateManager = updateManager;

        // Build interfaces for core
        core = new MinerTrackCommandCore(
            langBridge,
            vlBridge,
            new BukkitCommandBridge(null, vlBridge.getVerbosePlayers()),
            new PlayerLookupImpl(),
            new KickBridgeImpl(),
            new ConfigReloadBridgeImpl(langBridge),
            new UpdateCheckBridgeImpl(updateManager, langBridge),
            new LogViewerBridgeImpl()
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            return true;
        }

        CommandBridge cmdBridge = new BukkitCommandBridge(sender, vlBridge.getVerbosePlayers());

        // Rebuild core with fresh bridge for this sender
        MinerTrackCommandCore currentCore = new MinerTrackCommandCore(
            langBridge,
            vlBridge,
            cmdBridge,
            new PlayerLookupImpl(),
            new KickBridgeImpl(),
            new ConfigReloadBridgeImpl(langBridge),
            new UpdateCheckBridgeImpl(updateManager, langBridge),
            new LogViewerBridgeImpl()
        );

        return currentCore.onCommand(args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        CommandBridge cmdBridge = new BukkitCommandBridge(sender, vlBridge.getVerbosePlayers());
        MinerTrackCommandCore currentCore = new MinerTrackCommandCore(
            langBridge, vlBridge, cmdBridge,
            new PlayerLookupImpl(),
            new KickBridgeImpl(),
            new ConfigReloadBridgeImpl(langBridge),
            new UpdateCheckBridgeImpl(updateManager, langBridge),
            new LogViewerBridgeImpl()
        );
        return currentCore.onTabComplete(args);
    }

    // ─── PlayerLookup ───────────────────────────────────────────────────────

    private class PlayerLookupImpl implements MinerTrackCommandCore.PlayerLookup {
        @Override
        public UUID getPlayerUUID(String name) {
            Player p = Bukkit.getPlayer(name);
            return p != null ? p.getUniqueId() : null;
        }

        @Override
        public String getPlayerName(UUID uuid) {
            Player p = Bukkit.getPlayer(uuid);
            return p != null ? p.getName() : uuid.toString();
        }

        @Override
        public boolean isOnline(UUID uuid) {
            return Bukkit.getPlayer(uuid) != null;
        }

        @Override
        public List<String> getOnlinePlayerNames() {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
    }

    // ─── KickBridge ─────────────────────────────────────────────────────────

    private class KickBridgeImpl implements MinerTrackCommandCore.KickBridge {
        @Override
        public void kickPlayer(UUID playerId, String reason) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) p.kickPlayer(reason);
        }

        @Override
        public boolean isKickStrikeLightning() {
            Object cfg = vlBridge.getConfig("kick_strike_lightning");
            return cfg == null || Boolean.TRUE.equals(cfg);
        }

        @Override
        public void strikeLightningEffect(UUID playerId) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) {
                if (isFolia()) {
                    try {
                        Bukkit.getRegionScheduler().execute((org.bukkit.plugin.Plugin) adapter.getPlugin(), p.getLocation(), () -> {
                            try { p.getWorld().strikeLightningEffect(p.getLocation()); }
                            catch (Exception e) { adapter.info("Lightning effect error: " + e.getMessage()); }
                        });
                    } catch (Exception e) { e.printStackTrace(); }
                } else {
                    try { p.getWorld().strikeLightningEffect(p.getLocation()); }
                    catch (Exception e) { adapter.info("Lightning effect error: " + e.getMessage()); }
                }
            }
        }

        @Override
        public void broadcastMessage(String message) {
            Bukkit.broadcastMessage(message);
        }
    }

    // ─── ConfigReloadBridge ────────────────────────────────────────────────

    private class ConfigReloadBridgeImpl implements MinerTrackCommandCore.ConfigReloadBridge {
        private final BukkitLanguageBridge lang;

        ConfigReloadBridgeImpl(BukkitLanguageBridge lang) { this.lang = lang; }

        @Override
        public void reloadConfig() {
            // Re-load the config file from disk (clears the detection
            // bridge's 5s cache, re-runs ConfigMerger, reloads group
            // configs). Also refresh the violation manager's own copy
            // (it caches a separate CommonYaml field for the violation
            // engine + command execution).
            adapter.reloadConfig();
            detectionBridge.clearConfigCache();
            if (vlBridge instanceof BukkitViolationManager) {
                ((BukkitViolationManager) vlBridge).reloadConfig();
            }
            // Surface the current debug-flag state on every reload so
            // the operator can confirm the toggle took effect without
            // waiting for a [MinerTrack:DEBUG] line to appear (or fail
            // to appear) on the next block break. Mirrors the
            // BukkitPlatform.onEnable warning so the same hint is
            // visible from both startup and reload.
            if (adapter.isDebugEnabled()) {
                adapter.warning("[MinerTrack:DEBUG] Debug mode is ENABLED \u2014 expect a high volume of [MinerTrack:DEBUG] log lines.");
            }
        }

        @Override
        public void reloadLanguage() {
            lang.reloadLanguage();
        }
    }

    // ─── UpdateCheckBridge ─────────────────────────────────────────────────

    private class UpdateCheckBridgeImpl implements MinerTrackCommandCore.UpdateCheckBridge {
        private final BukkitUpdateManager updateManager;
        private final BukkitLanguageBridge lang;

        UpdateCheckBridgeImpl(BukkitUpdateManager updateManager, BukkitLanguageBridge lang) {
            this.updateManager = updateManager;
            this.lang = lang;
        }

        @Override
        public void checkForUpdates(CommandBridge sender) {
            // Resolve the underlying Bukkit CommandSender (the
            // CommandBridge wraps it in a platform-neutral shell). The
            // sender may be a Player (spigot().sendMessage) or the
            // console (we still want the clickable link for parity with
            // v1's behaviour, so we route through the same component
            // builder).
            Object raw = sender.getSender();
            org.bukkit.command.CommandSender bukkitSender =
                raw instanceof org.bukkit.command.CommandSender ? (org.bukkit.command.CommandSender) raw : null;
            updateManager.checkForUpdates(bukkitSender, lang);
        }
    }

    // ─── LogViewerBridge ───────────────────────────────────────────────────

    private class LogViewerBridgeImpl implements MinerTrackCommandCore.LogViewerBridge {
        @Override
        public List<String> getLogFileNames(int maxFiles) {
            File logDir = new File(adapter.getDataFolder(), "logs");
            if (!logDir.exists()) return new ArrayList<>();
            File[] files = logDir.listFiles((d, n) -> n.endsWith(".log"));
            if (files == null) return new ArrayList<>();
            Arrays.sort(files, Comparator.comparing(File::getName).reversed());
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(maxFiles, files.length); i++) names.add(files[i].getName());
            return names;
        }

        @Override
        public byte[] readLogFile(String fileName) {
            File f = new File(new File(adapter.getDataFolder(), "logs"), fileName);
            if (!f.exists() || !f.isFile()) return null;
            try { return Files.readAllBytes(f.toPath()); }
            catch (IOException e) { return null; }
        }

        @Override
        public int getLogViewerLinesPerPage() {
            Object n = vlBridge.getConfig("log-viewer-lines-per-page");
            return n instanceof Number ? ((Number) n).intValue() : 10;
        }

        @Override
        public String getLogFormat() {
            return langBridge.getLogFormat();
        }
    }

    private boolean isFolia() {
        try { Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }
}