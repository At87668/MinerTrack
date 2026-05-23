package link.star_dust.MinerTrack.common;

import java.io.File;
import java.util.List;

public interface ConfigBridge {
    Object get(String path);
    int getInt(String path, int def);
    boolean getBoolean(String path, boolean def);
    double getDouble(String path, double def);
    List<String> getStringList(String path);

    void saveConfig();
    void reloadConfig();
    File getDataFolder();
}
