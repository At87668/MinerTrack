package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Consumer;

/**
 * Bukkit platform entry point.
 * Wires together all core components and registers listeners + periodic tasks.
 */
public class BukkitPlatform extends JavaPlugin {
    private BukkitAdapter adapter;
    private BukkitDetectionBridge detectionBridge;
    private BukkitViolationManager violationManager;
    private MiningCore miningCore;
    private BukkitTask cleanupTask;

    @Override
    public void onEnable() {
        adapter = new BukkitAdapter(this);

        // Save default config files if they don't exist (matching v1 behavior)
        saveResource("config.yml", false);
        saveResource("Configuration/overworld.yml", false);
        saveResource("Configuration/nether.yml", false);
        saveResource("Configuration/end.yml", false);

        // Violation manager (creates ViolationEngine + CoreWebhookManager)
        violationManager = new BukkitViolationManager(adapter);

        // Detection bridge (block lookups, config, block tracking)
        detectionBridge = new BukkitDetectionBridge(adapter);

        // Core mining detection orchestrator
        miningCore = new MiningCore(detectionBridge, violationManager);

        // Language bridge
        BukkitLanguageBridge langBridge = new BukkitLanguageBridge(adapter);

        // Register event listeners
        getServer().getPluginManager().registerEvents(
            new MiningListener(miningCore, detectionBridge, violationManager, detectionBridge), this);

        // Register command executor
        MinerTrackCommandExecutor commandExecutor = new MinerTrackCommandExecutor(adapter, detectionBridge, violationManager, langBridge);
        getCommand("minertrack").setExecutor(commandExecutor);
        getCommand("minertrack").setTabCompleter(commandExecutor);
        // Aliases mt and mtrack
        getCommand("mt").setExecutor(commandExecutor);
        getCommand("mt").setTabCompleter(commandExecutor);
        getCommand("mtrack").setExecutor(commandExecutor);
        getCommand("mtrack").setTabCompleter(commandExecutor);

        // Periodic cleanup task (same interval as legacy: 20 ticks * 60 seconds = 1200 ticks)
        scheduleCleanupTasks();

        // VL decay task — same 60-second interval, Folia-compatible
        int decayInterval = 20 * 60;
        violationManager.scheduleGlobalDecayTask(decayInterval);

        getLogger().info("MinerTrack (BukkitPlatform) enabled.");
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) cleanupTask.cancel();
        if (violationManager != null) {
            violationManager.cancelAllVLDecayTasks();
            violationManager.cancelGlobalDecayTask();
        }
        getLogger().info("MinerTrack (BukkitPlatform) disabled.");
    }

    private void scheduleCleanupTasks() {
        int interval = 20 * 60; // ticks

        if (isFolia()) {
            try {
                Class<?> schedulerClass = Class.forName("org.bukkit.Bukkit");
                Object scheduler = schedulerClass.getMethod("getGlobalRegionScheduler").invoke(null);
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                scheduler.getClass().getMethod("runAtFixedRate",
                    org.bukkit.plugin.Plugin.class,
                    consumerClass,
                    long.class,
                    long.class
                ).invoke(scheduler, this, (Consumer<Object>) task -> {
                    try {
                        if (!isEnabled()) {
                            task.getClass().getMethod("cancel").invoke(task);
                            return;
                        }
                        miningCore.cleanupExpiredPaths();
                        miningCore.cleanupExpiredPlacedBlocks();
                        miningCore.cleanupExpiredBrokenAir();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, interval, interval);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            getServer().getScheduler().runTaskTimer(this, () -> {
                if (!isEnabled()) return;
                miningCore.cleanupExpiredPaths();
                miningCore.cleanupExpiredPlacedBlocks();
                miningCore.cleanupExpiredBrokenAir();
            }, interval, interval);
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
}
