package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.Core;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import org.bstats.bukkit.Metrics;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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
    private BukkitUpdateManager updateManager;
    private Listener updateNotifyListener;
    private Metrics bStatsMetrics;
    private BukkitTask cleanupTask;

    @Override
    public void onEnable() {
        adapter = new BukkitAdapter(this);

        // Violation manager (creates ViolationEngine + CoreWebhookManager)
        violationManager = new BukkitViolationManager(adapter);

        // Detection bridge (block lookups, config, block tracking)
        detectionBridge = new BukkitDetectionBridge(adapter, adapter.getYamlLoader());

        // Eagerly load group configurations (Configuration/<world>.yml) so
        // the per-world files are copied from the JAR on a fresh install
        // and any stale version is upgraded before the first mining event.
        // Without this the group configs only get created lazily on the
        // first isWorldDetectionEnabled() call, which leaves the plugin
        // running without the per-world detection settings on a fresh
        // install and produces no "Loaded group configurations:" log
        // output at startup.
        detectionBridge.loadGroupConfigs();

        // Core mining detection orchestrator
        miningCore = new MiningCore(detectionBridge, violationManager);

        // Language bridge
        BukkitLanguageBridge langBridge = new BukkitLanguageBridge(adapter);

        // Register event listeners
        getServer().getPluginManager().registerEvents(
            new MiningListener(miningCore, detectionBridge, violationManager, detectionBridge), this);

        // Update checker + PlayerJoin notification (mirrors v1's
        // MinerTrack#onPlayerJoin behaviour). Constructed AFTER the
        // detection bridge is up so the update manager can read
        // check_update / check_update_channel from the merged config.
        updateManager = new BukkitUpdateManager(adapter, detectionBridge);
        updateNotifyListener = new Listener() {
            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                Player player = event.getPlayer();
                if (!player.hasPermission("minertrack.checkupdate")) return;
                if (!updateManager.shouldNotifyOnJoin()) return;
                net.md_5.bungee.api.chat.BaseComponent[] components =
                    updateManager.getUpdateMessageComponent(langBridge);
                if (components != null) {
                    player.spigot().sendMessage(components);
                }
            }
        };
        getServer().getPluginManager().registerEvents(updateNotifyListener, this);

        // bStats — same pluginId as v1 (project id 23790 on bStats.org).
        // The bstats-bukkit:3.0.0 constructor takes (JavaPlugin, int).
        // Failing to construct bStats is non-fatal (it's a telemetry
        // service); wrap the call so a transport error at startup does
        // not abort the rest of onEnable().
        try {
            bStatsMetrics = new Metrics(this, 23790);
        } catch (Throwable t) {
            getLogger().warning("Failed to initialise bStats metrics: " + t.getMessage());
        }

        // Register command executor
        MinerTrackCommandExecutor commandExecutor = new MinerTrackCommandExecutor(
            adapter, detectionBridge, violationManager, langBridge, updateManager);
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

        // Core. The banner lines are inlined in Core.java on purpose so
        // the enable text is a stable part of the plugin's identity
        // and never depends on the operator editing language.yml.
        new Core(adapter).printStartupBanner();

        getLogger().info("MinerTrack (BukkitPlatform) enabled.");
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) cleanupTask.cancel();
        if (violationManager != null) {
            violationManager.cancelAllVLDecayTasks();
            violationManager.cancelGlobalDecayTask();
        }
        if (updateNotifyListener != null) {
            HandlerList.unregisterAll(updateNotifyListener);
            updateNotifyListener = null;
        }
        // bStats has no explicit shutdown hook in 3.0.0 (the scheduler is
        // owned by Bukkit's task scheduler and is cancelled automatically
        // when the plugin disables). We just drop the reference.
        bStatsMetrics = null;
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
