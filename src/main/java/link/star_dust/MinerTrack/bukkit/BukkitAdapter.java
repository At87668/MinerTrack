package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import org.bukkit.ChatColor;
import java.util.UUID;

public class BukkitAdapter implements PluginAdapter {
    private final JavaPlugin plugin;
    private final YamlLoader yamlLoader = new BukkitYamlLoader();

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
        // After Bukkit reloads the config, ensure missing keys are filled
        // from defaults. We log (not swallow) any I/O / parse failure so
        // admins can diagnose a broken config instead of silently running
        // with the previous in-memory copy.
        try {
            java.io.File cfg = new java.io.File(plugin.getDataFolder(), "config.yml");
            link.star_dust.MinerTrack.core.config.ConfigMerger.loadAndMerge(cfg, "config.yml", this, yamlLoader);
        } catch (Exception e) {
            info("Failed to reload config.yml: " + e.getMessage());
        }
        // Also reload group configs via the active DetectionBridge so they
        // are refreshed on every reload. Clear the per-bridge config
        // cache first so the merge step reads the just-saved file from
        // disk instead of serving the previous 5-second-cached copy.
        try {
            BukkitDetectionBridge active = BukkitDetectionBridge.getActive();
            if (active != null) {
                active.clearConfigCache();
                active.loadGroupConfigs();
            }
        } catch (Exception e) {
            info("Failed to reload group configs: " + e.getMessage());
        }
    }

    @Override
    public Object getPlugin() {
        return plugin;
    }

    /** Platform-specific YAML loader exposed to the core config layer. */
    public YamlLoader getYamlLoader() {
        return yamlLoader;
    }
}
