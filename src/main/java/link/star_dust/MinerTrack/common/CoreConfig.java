package link.star_dust.MinerTrack.common;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;
import java.util.regex.Pattern;

/**
 * CoreConfig encapsulates world-aware lookup logic extracted from ConfigManager.
 * It is intentionally lightweight: it operates on provided in-memory structures.
 */
public class CoreConfig {
    private final YamlConfiguration mainConfig;
    private final Map<String, YamlConfiguration> groupConfigs;
    private final Map<String, String> worldToGroup;
    private final Map<String, List<Pattern>> groupWorldPatterns;
    private final String defaultUnnamedGroupKey;

    public CoreConfig(YamlConfiguration mainConfig,
                      Map<String, YamlConfiguration> groupConfigs,
                      Map<String, String> worldToGroup,
                      Map<String, List<Pattern>> groupWorldPatterns,
                      String defaultUnnamedGroupKey) {
        this.mainConfig = mainConfig;
        this.groupConfigs = groupConfigs;
        this.worldToGroup = worldToGroup;
        this.groupWorldPatterns = groupWorldPatterns;
        this.defaultUnnamedGroupKey = defaultUnnamedGroupKey;
    }

    public YamlConfiguration getGroupConfigForWorld(String worldName) {
        if (worldName == null) return null;
        if (worldToGroup.containsKey(worldName)) {
            String k = worldToGroup.get(worldName);
            return groupConfigs.get(k);
        }
        // Check patterns (wildcards)
        for (Map.Entry<String, List<Pattern>> e : groupWorldPatterns.entrySet()) {
            for (Pattern p : e.getValue()) {
                if (p.matcher(worldName).matches()) return groupConfigs.get(e.getKey());
            }
        }

        // Scan groups for explicit "worlds" list (exact matches)
        for (Map.Entry<String, YamlConfiguration> e : groupConfigs.entrySet()) {
            List<String> worlds = e.getValue().getStringList("worlds");
            if (worlds != null && worlds.contains(worldName)) return e.getValue();
        }

        // Respect xray.worlds mapping in main config
        if (mainConfig != null) {
            ConfigurationSection worldsSection = mainConfig.getConfigurationSection("xray.worlds");
            if (worldsSection != null) {
                for (String fileKey : worldsSection.getKeys(false)) {
                    try {
                        List<String> list = worldsSection.getStringList(fileKey);
                        if (list == null) continue;
                        String k = fileKey;
                        if (k.toLowerCase().endsWith(".yml")) k = k.substring(0, k.length() - 4);
                        for (String w : list) {
                            if (w == null) continue;
                            if (w.equalsIgnoreCase("all_unnamed_world")) {
                                // handled later
                            } else if (w.equals(worldName)) {
                                if (groupConfigs.containsKey(k)) return groupConfigs.get(k);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (defaultUnnamedGroupKey != null && groupConfigs.containsKey(defaultUnnamedGroupKey)) return groupConfigs.get(defaultUnnamedGroupKey);
        return null;
    }

    public int getIntForWorld(String worldName, String path, int def) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null) {
            if (yc.contains(path)) return yc.getInt(path, def);
            String subPath = path.startsWith("xray.") ? path.substring(5) : path;
            if (yc.contains(subPath)) return yc.getInt(subPath, def);
        }
        return mainConfig.getInt(path, def);
    }

    public boolean getBooleanForWorld(String worldName, String path, boolean def) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null) {
            if (yc.contains(path)) return yc.getBoolean(path, def);
            String subPath = path.startsWith("xray.") ? path.substring(5) : path;
            if (yc.contains(subPath)) return yc.getBoolean(subPath, def);
        }
        return mainConfig.getBoolean(path, def);
    }

    public List<String> getStringListForWorld(String worldName, String path) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null) {
            if (yc.contains(path)) return yc.getStringList(path);
            String subPath = path.startsWith("xray.") ? path.substring(5) : path;
            if (yc.contains(subPath)) return yc.getStringList(subPath);
        }
        return mainConfig.getStringList(path);
    }

    public double getDoubleForWorld(String worldName, String path, double def) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null) {
            if (yc.contains(path)) return yc.getDouble(path, def);
            String subPath = path.startsWith("xray.") ? path.substring(5) : path;
            if (yc.contains(subPath)) return yc.getDouble(subPath, def);
        }
        return mainConfig.getDouble(path, def);
    }
}
