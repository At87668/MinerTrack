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
        "xray.decay.factor"
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
            adapter.info("[Merger] Created " + userFile.getName() + " from default");
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

        // Version check: if the user's file is missing _config-version or
        // has an older version than the JAR default, bump the user's
        // _config-version up to the latest. We do NOT replace the entire
        // file with the JAR default (that would destroy any custom keys
        // the user added, e.g. an extra entry in `xray.worlds` like
        // `dim7`). The subsequent merge step is responsible for filling
        // in any keys that are missing relative to the JAR default, and
        // it preserves the user's existing customisations.
        //
        // A timestamped backup is still produced before the bump, so
        // admins can recover the previous layout if they ever need to.
        if (needsUpgrade(userConfig, defaultsConfig)) {
            int currentVersion = userConfig.getInt("_config-version", 0);
            int defaultVersion = defaultsConfig.getInt("_config-version", 0);
            adapter.info("Config " + userFile.getName() + " version " + currentVersion
                    + " is outdated (latest: " + defaultVersion + "). Bumping in place; "
                    + "the merge step will add any keys added since the user's version.");
            String stamp = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            File backupFile = new File(userFile.getParentFile(),
                    stripExtension(userFile.getName()) + "-" + stamp + ".yml.bak");
            try {
                java.nio.file.Files.copy(userFile.toPath(), backupFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                adapter.info("Backed up " + userFile.getName() + " to " + backupFile.getName());
            } catch (Exception e) {
                adapter.info("Failed to backup " + userFile.getName() + ": " + e.getMessage());
            }
            // Bump the version in the in-memory copy. The merge + save
            // below will persist it to disk alongside any new keys.
            userConfig.set("_config-version", defaultVersion);
        }

        int added = mergeConfigurations(userConfig, defaultsConfig, "");

        if (added > 0) {
            adapter.info("[Merger] " + userFile.getName() + ": filled " + added
                    + " missing key(s) from defaults");
        }

        try {
            userConfig.save(userFile);
        } catch (Exception e) {
            adapter.info("Could not save merged config " + userFile.getName() + ": " + e.getMessage());
        }

        // Reload from disk so the returned config reflects the just-saved
        // state. Without this, callers that read nested paths via
        // Bukkit's `YamlConfiguration#get(String)` would see the in-memory
        // state left by `set(String, Map)` during the merge (where
        // Bukkit stores the Map as a single value, breaking subsequent
        // `get("xray.worlds")` lookups). A save+reload round-trip
        // forces Bukkit to normalise the structure back into nested
        // MemorySections that the rest of the plugin can descend into.
        try {
            userConfig = loader.loadFile(userFile);
        } catch (Exception e) {
            adapter.info("Failed to reload " + userFile.getName()
                    + " after merge; using the in-memory copy instead: " + e.getMessage());
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
     *
     * @return the number of leaf keys that were filled in from defaults
     *         during this merge (zero means the user file is already
     *         complete). Sections recursively merged through a whitelisted
     *         path are recursed into without contributing to the count
     *         themselves; only their missing leaf keys are counted.
     */
    private static int mergeConfigurations(CommonYaml currentConfig,
                                            CommonYaml defaultConfig,
                                            String currentPath) {
        if (currentConfig == null || defaultConfig == null) return 0;

        int added = 0;
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
                    added += mergeConfigurations(cur, def, fullKeyPath);
                    // Write merged map back into parent
                    currentConfig.set(key, curMap);
                }
                // else: keep user's value
            } else {
                // Add missing key from defaults
                currentConfig.set(key, defaultConfig.get(key));
                added++;
            }
        }
        return added;
    }

    private static boolean isSection(Object v) {
        return v instanceof java.util.Map;
    }

    /**
     * Return {@code true} if {@code userConfig} is missing
     * {@code _config-version} or its version is older than the version
     * embedded in {@code defaultConfig}. Returns {@code false} (no upgrade
     * needed) if the default has no version key (e.g. an unversioned
     * resource).
     */
    private static boolean needsUpgrade(CommonYaml userConfig, CommonYaml defaultConfig) {
        if (defaultConfig == null) return false;
        int defaultVersion = defaultConfig.getInt("_config-version", 0);
        if (defaultVersion <= 0) return false;
        if (userConfig == null) return true;
        int currentVersion = userConfig.getInt("_config-version", 0);
        return currentVersion < defaultVersion;
    }

    private static String stripExtension(String name) {
        return name.toLowerCase().endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
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
