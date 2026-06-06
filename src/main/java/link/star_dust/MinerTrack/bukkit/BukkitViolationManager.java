package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.common.CoreWebhookManager;
import link.star_dust.MinerTrack.core.violation.ViolationEngine;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Bukkit-side implementation of ViolationManagerBridge.
 * Delegates core logic to ViolationEngine; handles platform-specific I/O and scheduling.
 */
public class BukkitViolationManager implements ViolationManagerBridge {
    private final PluginAdapter adapter;
    private final ViolationEngine engine;
    private final CoreWebhookManager webhookManager;
    private CommonYaml config;
    private final Set<UUID> verbosePlayers = Collections.synchronizedSet(new HashSet<>());
    private BukkitTask decayTask;
    private String currentLogFileName;
    private final Map<UUID, BukkitTask> playerDecayTasks = new ConcurrentHashMap<>();

    public BukkitViolationManager(PluginAdapter adapter) {
        this.adapter = adapter;
        this.engine = new ViolationEngine(this);
        this.webhookManager = new CoreWebhookManager(this, new BukkitWebhookSender(adapter));
        this.currentLogFileName = generateLogFileName();

        // Use the platform-agnostic merger so missing keys are added and
        // stale user files are upgraded.
        File f = new File(adapter.getDataFolder(), "config.yml");
        this.config = link.star_dust.MinerTrack.core.config.ConfigMerger.loadAndMerge(
                f, "config.yml", adapter, new BukkitYamlLoader());
    }

    /**
     * Schedule the global VL decay task.
     * Called by BukkitPlatform after plugin enable, once the server world is ready.
     * @param decayIntervalTicks interval in ticks (converted from minutes in calling code)
     */
    public void scheduleGlobalDecayTask(long decayIntervalTicks) {
        Object p = adapter.getPlugin();
        if (!(p instanceof org.bukkit.plugin.Plugin)) return;
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) p;

        if (isFolia()) {
            try {
                Class<?> schedulerClass = Class.forName("org.bukkit.Bukkit");
                Object scheduler = schedulerClass.getMethod("getGlobalRegionScheduler").invoke(null);
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                scheduler.getClass().getMethod("runAtFixedRate",
                    org.bukkit.plugin.Plugin.class, consumerClass, long.class, long.class)
                    .invoke(scheduler, plugin, (Consumer<Object>) task -> {
                        try {
                            if (!plugin.isEnabled()) {
                                task.getClass().getMethod("cancel").invoke(task);
                                return;
                            }
                            engine.processDecay();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, decayIntervalTicks, decayIntervalTicks);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            decayTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> engine.processDecay(), decayIntervalTicks, decayIntervalTicks);
        }
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String generateLogFileName() {
        LocalDate date = LocalDate.now();
        String formattedDate = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        File logDir = new File(adapter.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        int index = 1;
        File logFile;
        do {
            logFile = new File(logDir, String.format("%s-%d%s.log", formattedDate, index, getOrdinalSuffix(index)));
            index++;
        } while (logFile.exists());
        return logFile.getName();
    }

    private String getOrdinalSuffix(int index) {
        if (index >= 11 && index <= 13) return "th";
        switch (index % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }

    private File getLogFile() {
        File logDir = new File(adapter.getDataFolder(), "logs");
        return new File(logDir, getLogFileName());
    }

    private String getLogFileName() {
        if (currentLogFileName == null) currentLogFileName = generateLogFileName();
        return currentLogFileName;
    }

    @Override
    public int getViolationLevel(UUID playerId) {
        return engine.getViolationLevel(playerId);
    }

    @Override
    public void increaseViolationLevel(UUID playerId, String playerName, int increment, String blockType, int count, int vein, CommonLocation location) {
        // Cancel existing per-player decay task on new violation
        cancelVLDecayTask(playerId);
        engine.increaseViolation(playerId, playerName, increment, blockType, count, vein, location);
        scheduleVLDecayTask(playerId);
    }

    @Override
    public void processVLDecay() {
        engine.processDecay();
    }

    @Override
    public void scheduleVLDecayTask(UUID playerId) {
        if (playerDecayTasks.containsKey(playerId)) return;
        Object pluginObj = adapter.getPlugin();
        if (!(pluginObj instanceof org.bukkit.plugin.Plugin)) return;
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) pluginObj;
        int interval = config.getInt("xray.decay.interval", 3) * 20 * 60;

        if (isFolia()) {
            try {
                Class<?> schedulerClass = Class.forName("org.bukkit.Bukkit");
                Object scheduler = schedulerClass.getMethod("getGlobalRegionScheduler").invoke(null);
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                Object task = scheduler.getClass().getMethod("runAtFixedRate",
                    org.bukkit.plugin.Plugin.class, consumerClass, long.class, long.class)
                    .invoke(scheduler, plugin, (Consumer<Object>) t -> {
                        try {
                            if (!plugin.isEnabled()) {
                                t.getClass().getMethod("cancel").invoke(t);
                                playerDecayTasks.remove(playerId);
                                return;
                            }
                            engine.processDecay();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, interval, interval);
                // Folia tasks don't return a BukkitTask we can cancel per-player
                // The global decay task handles all players; per-player tracking is still maintained
                // in the playerDecayTasks map for reference but we don't store a cancelable handle
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> engine.processDecay(), interval, interval);
            playerDecayTasks.put(playerId, task);
        }
    }

    @Override
    public void cancelVLDecayTask(UUID playerId) {
        if (playerId == null) return;
        BukkitTask task = playerDecayTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    @Override
    public void cancelAllVLDecayTasks() {
        playerDecayTasks.values().forEach(t -> { if (t != null) t.cancel(); });
        playerDecayTasks.clear();
    }

    @Override
    public void cancelGlobalDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    @Override
    public boolean isLogFileEnabled() {
        return config.getBoolean("log_file", false);
    }

    @Override
    public String getLogFormat() {
        return config.getString("language.log-format",
            "%year%-%month%-%day% %hour%:%minute%:%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%");
    }

    @Override
    public void appendLogLine(String line) {
        if (!isLogFileEnabled()) return;
        try (FileWriter fw = new FileWriter(getLogFile(),
                java.nio.charset.StandardCharsets.UTF_8, true)) {
            fw.write(line + "\n");
        } catch (IOException e) {
            adapter.info("Failed to write violation log: " + e.getMessage());
        }
    }

    @Override
    public void appendCommandLog(String command) {
        if (!isLogFileEnabled()) return;
        try (FileWriter fw = new FileWriter(getLogFile(),
                java.nio.charset.StandardCharsets.UTF_8, true)) {
            fw.write("Executed Command: " + command + "\n");
        } catch (IOException e) {
            adapter.info("Failed to write command log: " + e.getMessage());
        }
    }

    @Override
    public void runConsoleCommand(String command) {
        Object p = adapter.getPlugin();
        if (p instanceof org.bukkit.plugin.Plugin) {
            org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) p;
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
        } else {
            adapter.info("Console command (fallback): " + command);
        }
    }

    @Override
    public Set<UUID> getVerbosePlayers() {
        return verbosePlayers;
    }

    @Override
    public boolean isVerboseConsoleEnabled() {
        return config.getBoolean("verbose.console", false);
    }

    @Override
    public void sendMessageToPlayer(UUID playerId, String message) {
        Object obj = adapter.getPlayer(playerId);
        if (obj instanceof org.bukkit.entity.Player) {
            org.bukkit.entity.Player p = (org.bukkit.entity.Player) obj;
            p.sendMessage(adapter.applyColors(message));
        }
    }

    @Override
    public boolean isWebHookEnabled() {
        return config.getBoolean("DiscordWebHook.enable", false);
    }

    @Override
    public int getWebHookVLRequired() {
        return config.getInt("DiscordWebHook.vl-required", Integer.MAX_VALUE);
    }

    @Override
    public void sendWebhook(UUID playerId, String oreType, int minedVeins, int oreCount, CommonLocation location) {
        webhookManager.onViolationIncrease(playerId, oreType, minedVeins, oreCount, location);
    }

    @Override
    public Object getConfigSection(String path) {
        Object o = config.get(path);
        if (o instanceof Map) return o;
        return null;
    }

    @Override
    public Object getConfig(String path) {
        return config.get(path);
    }

    @Override
    public int getConfigInt(String path, int def) {
        return config.getInt(path, def);
    }

    @Override
    public boolean getConfigBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    @Override
    public double getConfigDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    @Override
    public String getPrefixedMessage(String key) {
        String prefix = config.getString("messages.prefix", "[MinerTrack] ");
        return prefix + config.getString("messages." + key, key);
    }

    @Override
    public File getDataFolder() {
        return adapter.getDataFolder();
    }

    @Override
    public void resetViolation(UUID playerId) {
        engine.resetViolation(playerId);
    }

    @Override
    public void clearPlayerState(UUID playerId) {
        cancelVLDecayTask(playerId);
        verbosePlayers.remove(playerId);
        engine.resetViolation(playerId);
    }

    @Override
    public String getPlayerName(UUID playerId) {
        Object obj = adapter.getPlayer(playerId);
        if (obj instanceof org.bukkit.entity.Player) {
            return ((org.bukkit.entity.Player) obj).getName();
        }
        return playerId.toString();
    }

    /**
     * Re-read the main config from disk and re-merge it with the JAR
     * defaults. Called by the {@code /minertrack reload} command so the
     * violation manager picks up edits to {@code config.yml} without a
     * server restart. Group configs are refreshed through the active
     * {@link BukkitDetectionBridge#loadGroupConfigs()} by
     * {@link BukkitAdapter#reloadConfig()}.
     */
    public void reloadConfig() {
        try {
            File f = new File(adapter.getDataFolder(), "config.yml");
            this.config = link.star_dust.MinerTrack.core.config.ConfigMerger.loadAndMerge(
                    f, "config.yml", adapter, new BukkitYamlLoader());
        } catch (Exception e) {
            adapter.info("Failed to reload config.yml in violation manager: " + e.getMessage());
        }
    }
}
