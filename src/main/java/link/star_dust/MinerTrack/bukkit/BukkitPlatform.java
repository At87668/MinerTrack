package link.star_dust.MinerTrack.bukkit;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit platform adapter. Initializes core components and registers listeners.
 */
public class BukkitPlatform extends JavaPlugin {
    private BukkitViolationManager vlManager;

    @Override
    public void onEnable() {
        getLogger().info("Initializing BukkitPlatform... (replacing legacy)");

        BukkitAdapter adapter = new BukkitAdapter(this);
        // instantiate the violation manager (which creates ViolationEngine and CoreWebhookManager)
        vlManager = new BukkitViolationManager(adapter);

        // initialize detection components
        BukkitDetectionBridge dbridge = new BukkitDetectionBridge(adapter);
        org.bukkit.plugin.Plugin plugin = this;
        link.star_dust.MinerTrack.core.detection.DetectionEngine detectionEngine = new link.star_dust.MinerTrack.core.detection.DetectionEngine(dbridge);

        // register mining listener
        getServer().getPluginManager().registerEvents(new MiningListener(detectionEngine, dbridge, vlManager), this);

        getLogger().info("BukkitPlatform initialized and listeners registered.");
    }

    @Override
    public void onDisable() {
        getLogger().info("BukkitPlatform shutting down.");
        if (vlManager != null) {
            // cancel tasks if any
            vlManager.cancelVLDecayTask(null);
        }
    }
}
