package link.star_dust.MinerTrack.bukkit;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit platform adapter. Will contain glue code to initialize core using Bukkit APIs.
 */
public class BukkitPlatform extends JavaPlugin {
    @Override
    public void onEnable() {
        // TODO: initialize core with Bukkit-specific adapters
        getLogger().info("BukkitPlatform adapter loaded (placeholder)");
    }

    @Override
    public void onDisable() {
        // TODO: cleanup
    }
}
