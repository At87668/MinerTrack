package link.star_dust.MinerTrack.common;

import java.io.File;

public interface PluginAdapter {
    File getDataFolder();
    void saveResource(String resourcePath, boolean replace);
    String getVersion();
    void info(String msg);
    String applyColors(String message);
    void sendConsoleMessage(String message);
    Object getPlayer(java.util.UUID uuid);
    // Platform-specific plugin instance (may be null for non-Bukkit implementations)
    Object getPlugin();
}
