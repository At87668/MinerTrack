package link.star_dust.MinerTrack.core.config;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Mirrors v1 ConfigManager.mergeConfigurations() behavior:
 * - Whitelisted keys are recursively merged (nested sections)
 * - All other missing keys are filled from JAR defaults
 * - Result is saved back to the user's file
 *
 * This class is platform-agnostic; YAML I/O is delegated to the
 * supplied {@link YamlLoader}.
 */
public class ConfigMerger {

    /**
     * Keys whose nested structure should be recursively merged rather than replaced.
     * Matches v1's whitelist in ConfigManager.mergeConfigurations().
     */
    private static final Set<String> WHITELIST_KEYS = Set.of(
        "check_update",
        "check_update_channel",
        "kick_strike_lightning",
        "log_file",
        "delete_time",
        "disable_bypass_permission",
        "DiscordWebHook",
        "DiscordWebHook.enable",
        "DiscordWebHook.WebHookURL",
        "DiscordWebHook.vl-required",
        "DiscordWebHook.vl-add-message",
        "DiscordWebHook.vl-add-message.color",
        "DiscordWebHook.vl-add-message.title",
        "DiscordWebHook.vl-add-message.text",
        "xray",
        "xray.enable",
        "xray.worlds",
        // Note: per-world settings (enable, max-height, rare-ores, …) live
        // in the per-group Configuration/<group>.yml files, not in the
        // main config. The xray.worlds section only maps dimension ids
        // (e.g. "minecraft:overworld", "minecraft:the_nether") to group
        // file names.
        "xray.rare-ores",
        "xray.max_path_length",
        "xray.trace_remove",
        "xray.max_vein_distance",
        "xray.veinCountThreshold",
        "xray.path-detection",
        "xray.path-detection.turn-count-threshold",
        "xray.path-detection.branch-count-threshold",
        "xray.path-detection.y-change-threshold",
        "xray.path-detection.y-change-threshold-add-required",
        "xray.natural-detection",
        "xray.natural-detection.enable",
        "xray.natural-detection.cave",
        "xray.natural-detection.cave.air-threshold",
        "xray.natural-detection.cave.CaveAirMultiplier",
        "xray.natural-detection.cave.detection-range",
        "xray.natural-detection.cave.check_skip_vl",
        "xray.natural-detection.cave.artificial-air-remove-time",
        "xray.natural-detection.cave.ignore-artificial-air",
        "xray.natural-detection.sea",
        "xray.natural-detection.sea.check-running-water",
        "xray.natural-detection.sea.water-threshold",
        "xray.natural-detection.sea.detection-range",
        "xray.natural-detection.sea.check_skip_vl",
        "xray.natural-detection.lava-sea",
        "xray.natural-detection.lava-sea.lava-threshold",
        "xray.natural-detection.lava-sea.detection-range",
        "xray.natural-detection.lava-sea.check_skip_vl",
        "xray.small_vein_detection_size",
        "xray.decay",
        "xray.decay.interval",
        "xray.decay.amount",
        "xray.decay.use_factor",
        "xray.decay.factor",
        "explosion",
        "explosion.entity-explode-check",
        "explosion.explosion_retention_time",
        "explosion.base_vl_rate",
        "explosion.suspicious_hit_rate",
        "commands"
    );

    /**
     * Load user's config file, merge with JAR defaults (whitelist-based recursive merge),
     * then save the merged result back to disk.
     *
     * @param userFile     the user's config file on disk
     * @param resourcePath the path inside the JAR (e.g. "config.yml")
     * @param adapter      the plugin adapter to access getResource()
     * @param loader       the platform-specific YAML loader
     * @return the merged CommonYaml
     */
    public static CommonYaml loadAndMerge(File userFile, String resourcePath, PluginAdapter adapter, YamlLoader loader) {
        // Create from JAR if the file doesn't exist yet
        if (!userFile.exists()) {
            adapter.saveResource(resourcePath, false);
        }
        CommonYaml defaultsConfig;
        try (InputStream defaultStream = adapter.getResource(resourcePath)) {
            if (defaultStream != null) {
                defaultsConfig = loader.loadStream(defaultStream);
            } else {
                defaultsConfig = new MapBackedYaml(new java.util.LinkedHashMap<>());
            }
        } catch (Exception e) {
            defaultsConfig = new MapBackedYaml(new java.util.LinkedHashMap<>());
        }

        CommonYaml userConfig = loader.loadFile(userFile);

        mergeConfigurations(userConfig, defaultsConfig, "");

        try {
            userConfig.save(userFile);
        } catch (Exception e) {
            adapter.info("Could not save merged config " + userFile.getName() + ": " + e.getMessage());
        }

        return userConfig;
    }

    /**
     * Recursive whitelist-based merge. Matches v1 behavior exactly.
     *
     * Section detection: a value is considered a "section" (recurse) when
     * it is a {@link java.util.Map}. This is platform-neutral — Bukkit's
     * {@code YamlConfiguration} returns a Map for sections, and any other
     * YAML loader will do the same.
     */
    private static void mergeConfigurations(CommonYaml currentConfig,
                                             CommonYaml defaultConfig,
                                             String currentPath) {
        if (currentConfig == null || defaultConfig == null) return;

        for (String key : defaultConfig.getKeys(false)) {
            String fullKeyPath = (currentPath.isEmpty() ? "" : currentPath + ".") + key;

            if (currentConfig.contains(key)) {
                Object currentValue = currentConfig.get(key);
                Object defaultValue = defaultConfig.get(key);

                if (isSection(currentValue) && isSection(defaultValue) && WHITELIST_KEYS.contains(fullKeyPath)) {
                    // Both sides are sections and the section is whitelisted:
                    //   recurse via the in-memory Map representation.
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> curMap = (java.util.Map<String, Object>) currentValue;
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> defMap = (java.util.Map<String, Object>) defaultValue;
                    MapBackedYaml cur = new MapBackedYaml(curMap);
                    MapBackedYaml def = new MapBackedYaml(defMap);
                    mergeConfigurations(cur, def, fullKeyPath);
                    // Write merged map back into parent
                    currentConfig.set(key, curMap);
                }
                // else: keep user's value
            } else {
                // Add missing key from defaults
                currentConfig.set(key, defaultConfig.get(key));
            }
        }
    }

    private static boolean isSection(Object v) {
        return v instanceof java.util.Map;
    }

    /**
     * In-memory {@link CommonYaml} backed by a {@code LinkedHashMap}. Used to
     * recurse into nested sections during the merge without depending on a
     * platform YAML library.
     */
    static final class MapBackedYaml implements CommonYaml {
        private final java.util.Map<String, Object> map;

        MapBackedYaml(java.util.Map<String, Object> map) {
            this.map = map;
        }

        @Override public Object get(String path) { return deepGet(path); }
        @Override public Object get(String path, Object def) { Object v = deepGet(path); return v == null ? def : v; }
        @Override public int getInt(String path, int def) { Object v = deepGet(path); return v instanceof Number ? ((Number) v).intValue() : def; }
        @Override public boolean getBoolean(String path, boolean def) { Object v = deepGet(path); return v instanceof Boolean ? (Boolean) v : def; }
        @Override public double getDouble(String path, double def) { Object v = deepGet(path); return v instanceof Number ? ((Number) v).doubleValue() : def; }
        @Override public String getString(String path, String def) { Object v = deepGet(path); return v == null ? def : v.toString(); }
        @Override public List<String> getStringList(String path) {
            Object v = deepGet(path);
            if (v instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> raw = (List<Object>) v;
                java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
                for (Object o : raw) out.add(o == null ? null : o.toString());
                return out;
            }
            return java.util.Collections.emptyList();
        }
        @Override public boolean contains(String path) { return deepGet(path) != null; }
        @Override public Set<String> getKeys(boolean deep) { return map.keySet(); }
        @Override public void set(String path, Object value) {
            String[] parts = path.split("\\.");
            java.util.Map<String, Object> cur = map;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = cur.get(parts[i]);
                if (!(next instanceof java.util.Map)) {
                    java.util.LinkedHashMap<String, Object> created = new java.util.LinkedHashMap<>();
                    cur.put(parts[i], created);
                    cur = created;
                } else {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> nm = (java.util.Map<String, Object>) next;
                    cur = nm;
                }
            }
            cur.put(parts[parts.length - 1], value);
        }
        @Override public void save(File file) { /* not used in the merger */ }

        private Object deepGet(String path) {
            String[] parts = path.split("\\.");
            Object cur = map;
            for (String p : parts) {
                if (!(cur instanceof java.util.Map)) return null;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> m = (java.util.Map<String, Object>) cur;
                cur = m.get(p);
                if (cur == null) return null;
            }
            return cur;
        }
    }
}
