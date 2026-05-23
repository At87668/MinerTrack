package link.star_dust.MinerTrack.common;

import java.io.File;

public interface PluginAdapter {
    File getDataFolder();
    void saveResource(String resourcePath, boolean replace);
    String getVersion();
    void info(String msg);
}
