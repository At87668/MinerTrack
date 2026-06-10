package link.star_dust.MinerTrack.common;

import java.util.*;
import java.util.regex.Pattern;

/**
 * CoreConfig encapsulates world-aware lookup logic extracted from ConfigManager.
 * It is intentionally lightweight: it operates on provided in-memory structures
 * exposed through the platform-agnostic {@link CommonYaml} interface.
 *
 * Block identifiers returned from the "rare-ores" list (and any other
 * block-name configuration value) are normalised to the canonical
 * Minecraft namespace format ({@code minecraft:xxx}) on read. This keeps
 * the config schema platform-neutral: the user writes
 * {@code minecraft:diamond_ore} in {@code config.yml}, and the same config
 * file works on both Bukkit and Fabric.
 */
public class CoreConfig {
    private CommonYaml mainConfig;
    private Map<String, CommonYaml> groupConfigs = new HashMap<>();
    private Map<String, String> worldToGroup = new HashMap<>();
    private Map<String, List<Pattern>> groupWorldPatterns = new HashMap<>();
    private String defaultUnnamedGroupKey;

    public CoreConfig() {}

    public CoreConfig(CommonYaml mainConfig,
                      Map<String, CommonYaml> groupConfigs,
                      Map<String, String> worldToGroup,
                      Map<String, List<Pattern>> groupWorldPatterns,
                      String defaultUnnamedGroupKey) {
        this.mainConfig = mainConfig;
        this.groupConfigs = groupConfigs;
        this.worldToGroup = worldToGroup;
        this.groupWorldPatterns = groupWorldPatterns;
        this.defaultUnnamedGroupKey = defaultUnnamedGroupKey;
    }

    public void setMainConfig(CommonYaml mainConfig) { this.mainConfig = mainConfig; }
    public void setGroupConfigs(Map<String, CommonYaml> m) { this.groupConfigs = m; }
    public void setWorldToGroup(Map<String, String> m) { this.worldToGroup = m; }
    public void setGroupWorldPatterns(Map<String, List<Pattern>> m) { this.groupWorldPatterns = m; }
    public void setDefaultUnnamedGroupKey(String k) { this.defaultUnnamedGroupKey = k; }
    public CommonYaml getMainConfig() { return mainConfig; }
    public Map<String, CommonYaml> getGroupConfigs() { return groupConfigs; }
    public Map<String, String> getWorldToGroup() { return worldToGroup; }
    public Map<String, List<Pattern>> getGroupWorldPatterns() { return groupWorldPatterns; }
    public String getDefaultUnnamedGroupKey() { return defaultUnnamedGroupKey; }

    public CommonYaml getGroupConfigForWorld(String worldName) {
        if (worldName == null) return null;
        // Normalise incoming name to a canonical dimension id. We accept
        // either the canonical form (minecraft:overworld) or a Bukkit
        // folder name (world) here, since the caller may not always have
        // resolved the world through the bridge.
        String normalisedName = DimensionId.normalize(worldName);
        if (normalisedName == null) normalisedName = worldName;

        if (worldToGroup.containsKey(normalisedName)) {
            String k = worldToGroup.get(normalisedName);
            return groupConfigs.get(k);
        }
        if (worldToGroup.containsKey(worldName)) {
            // Legacy callers that pass an un-normalised folder name.
            String k = worldToGroup.get(worldName);
            return groupConfigs.get(k);
        }
        // Check patterns (wildcards)
        for (Map.Entry<String, List<Pattern>> e : groupWorldPatterns.entrySet()) {
            for (Pattern p : e.getValue()) {
                if (p.matcher(worldName).matches() || p.matcher(normalisedName).matches()) {
                    return groupConfigs.get(e.getKey());
                }
            }
        }

        // Scan groups for explicit "worlds" list (legacy, exact matches).
        // Values in the per-group list are normalised, so we compare
        // against the normalised name.
        for (Map.Entry<String, CommonYaml> e : groupConfigs.entrySet()) {
            List<String> worlds = e.getValue().getStringList("worlds");
            if (worlds == null || worlds.isEmpty()) continue;
            for (String w : worlds) {
                String wn = DimensionId.normalize(w);
                if (normalisedName.equals(wn) || worldName.equals(w)
                        || normalisedName.equals(w) || worldName.equals(wn)) {
                    return e.getValue();
                }
            }
        }

        // Respect xray.worlds mapping in main config. Values here are
        // already canonical dimension ids (see config.yml); the equality
        // check is correct.
        if (mainConfig != null) {
            Object worldsSection = mainConfig.get("xray.worlds");
            // The v2 design uses a platform-neutral `CommonYaml`
            // wrapper that on the Bukkit path returns a live
            // `ConfigurationSection` (or, on Map-backed configs,
            // a plain `Map`). Both are iterable as key→value
            // pairs: ConfigurationSection via `getKeys(false)`
            // and `get(key)`, Map via `entrySet()`. We
            // normalise both to the same loop by walking
            // `getKeys(false)` and calling `get(key)` for the
            // value.
            java.util.Set<String> worldKeys;
            if (worldsSection instanceof Map) {
                worldKeys = ((Map<String, Object>) worldsSection).keySet();
            } else if (worldsSection instanceof org.bukkit.configuration.ConfigurationSection) {
                worldKeys = ((org.bukkit.configuration.ConfigurationSection) worldsSection).getKeys(false);
            } else {
                worldKeys = java.util.Collections.emptySet();
            }
            for (String fileKey : worldKeys) {
                Object v = (worldsSection instanceof Map)
                        ? ((Map<String, Object>) worldsSection).get(fileKey)
                        : ((org.bukkit.configuration.ConfigurationSection) worldsSection).get(fileKey);
                if (v instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) v;
                    String k = fileKey;
                    if (k.toLowerCase().endsWith(".yml")) k = k.substring(0, k.length() - 4);
                    for (Object wo : list) {
                        if (wo == null) continue;
                        String w = wo.toString();
                        if (w.equalsIgnoreCase("all_unnamed_world")) {
                            // handled below
                        } else if (w.equals(worldName) || w.equals(normalisedName)) {
                            if (groupConfigs.containsKey(k)) return groupConfigs.get(k);
                        }
                    }
                }
            }
        }

        if (defaultUnnamedGroupKey != null && groupConfigs.containsKey(defaultUnnamedGroupKey)) {
            return groupConfigs.get(defaultUnnamedGroupKey);
        }
        return null;
    }

    public int getIntForWorld(String worldName, String path, int def) {
        CommonYaml yc = getGroupConfigForWorld(worldName);
        if (yc != null) {
            if (yc.contains(path)) return yc.getInt(path, def);
            String subPath = path.startsWith("xray.") ? path.substring(5) : path;
            if (yc.contains(subPath)) return yc.getInt(subPath, def);
        }
        return mainConfig.getInt(path, def);
    }

    public boolean getBooleanForWorld(String worldName, String path, boolean def) {
        CommonYaml yc = getGroupConfigForWorld(worldName);
        if (yc != null) {
            if (yc.contains(path)) return yc.getBoolean(path, def);
            String subPath = path.startsWith("xray.") ? path.substring(5) : path;
            if (yc.contains(subPath)) return yc.getBoolean(subPath, def);
        }
        return mainConfig.getBoolean(path, def);
    }

    /**
     * Returns the rare-ore list for a world with all entries normalised to
     * canonical {@code minecraft:xxx} ids, so downstream code never has to
     * care whether the user wrote {@code DIAMOND_ORE} or
     * {@code minecraft:diamond_ore}.
     */
    public List<String> getStringListForWorld(String worldName, String path) {
        CommonYaml yc = getGroupConfigForWorld(worldName);
        List<String> raw;
        if (yc != null) {
            if (yc.contains(path)) raw = yc.getStringList(path);
            else {
                String subPath = path.startsWith("xray.") ? path.substring(5) : path;
                raw = yc.contains(subPath) ? yc.getStringList(subPath) : mainConfig.getStringList(path);
            }
        } else {
            raw = mainConfig.getStringList(path);
        }
        if (raw == null) return null;
        java.util.ArrayList<String> normalised = new java.util.ArrayList<>(raw.size());
        for (String s : raw) {
            String n = BlockId.normalize(s);
            normalised.add(n != null ? n : s);
        }
        return normalised;
    }

    public double getDoubleForWorld(String worldName, String path, double def) {
        CommonYaml yc = getGroupConfigForWorld(worldName);
        if (yc != null) {
            if (yc.contains(path)) return yc.getDouble(path, def);
            String subPath = path.startsWith("xray.") ? path.substring(5) : path;
            if (yc.contains(subPath)) return yc.getDouble(subPath, def);
        }
        return mainConfig.getDouble(path, def);
    }
}
