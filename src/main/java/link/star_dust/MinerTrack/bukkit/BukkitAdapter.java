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
    public Object getPlugin() {
        return plugin;
    }
}
