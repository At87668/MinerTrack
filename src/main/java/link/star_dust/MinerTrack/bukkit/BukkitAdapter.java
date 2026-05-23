package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.PluginAdapter;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;

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
}
