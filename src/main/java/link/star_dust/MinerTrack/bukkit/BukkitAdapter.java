package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.PluginAdapter;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import org.bukkit.ChatColor;
import java.util.UUID;

public class BukkitAdapter implements PluginAdapter {
    private final JavaPlugin plugin;

    public BukkitAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public File getDataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public void saveResource(String resourcePath, boolean replace) {
        plugin.saveResource(resourcePath, replace);
    }

    @Override
    public String getVersion() {
        try {
            return plugin.getDescription().getVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public void info(String msg) {
        plugin.getLogger().info(msg);
    }

    @Override
    public String applyColors(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public void sendConsoleMessage(String message) {
        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    @Override
    public Object getPlayer(UUID uuid) {
        return plugin.getServer().getPlayer(uuid);
    }

    @Override
    public java.io.InputStream getResource(String resourcePath) {
        return plugin.getResource(resourcePath);
    }

    @Override
    public void reloadConfig() {
        plugin.reloadConfig();
        // After Bukkit reloads the config, ensure missing keys are filled from defaults
        try {
            java.io.File cfg = new java.io.File(plugin.getDataFolder(), "config.yml");
            ConfigMerger.loadAndMerge(cfg, "config.yml", this);
        } catch (Exception ignored) {}
        // Also merge group configs via DetectionBridge so they are refreshed on every reload.
        try {
            link.star_dust.MinerTrack.common.DetectionBridge bridge =
                    (link.star_dust.MinerTrack.common.DetectionBridge)
                    ((org.bukkit.plugin.java.JavaPlugin) getPlugin()).getServer().getServicesManager()
                    .load(link.star_dust.MinerTrack.common.DetectionBridge.class);
            if (bridge != null) bridge.mergeGroupConfigs(this);
        } catch (Exception ignored) {}
    }

    @Override
    public Object getPlugin() {
        return plugin;
    }
}
