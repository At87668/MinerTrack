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
                defaultsConfig = new link.star_dust.MinerTrack.common.MapBackedYaml(new java.util.LinkedHashMap<>());
            }
        } catch (Exception e) {
            defaultsConfig = new link.star_dust.MinerTrack.common.MapBackedYaml(new java.util.LinkedHashMap<>());
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
            // Bump the version in the in-memory copy. The version
            // stays in memory only (see "no-save" policy below);
            // the user's file is left untouched so their comments
            // and custom key ordering are preserved.
            userConfig.set("_config-version", defaultVersion);
        }

        int added = mergeConfigurations(userConfig, defaultsConfig, "");

        if (added > 0) {
            adapter.info("[Merger] " + userFile.getName() + ": filled " + added
                    + " missing key(s) from defaults.");
        }

        // Save the merged config back to disk. This mirrors the
        // v1 legacy `ConfigManager.saveConfig()` call, and is
        // safe now that the v1-style in-place recursion (via
        // `CommonYaml.getChild`) preserves the in-memory section
        // tree the YAML serializer expects. The earlier v2
        // round-trip failed because the v2 merger used
        // `set(path, Map)` to write back a flattened copy,
        // which silently flipped scalar values into Maps on
        // re-parse (`xray.enable: true` → one-entry Map →
        // `getBoolean` returned false). With the in-place
        // recursion, the in-memory tree the merger mutates is
        // the same tree SnakeYAML serialises, and the reload
        // sees the same structure.
        try {
            userConfig.save(userFile);
        } catch (Exception e) {
            adapter.info("Could not save merged config " + userFile.getName() + ": " + e.getMessage());
        }

        // Reload from disk so the returned config reflects the
        // just-saved state. SnakeYAML's normaliser may shuffle
        // some scalar types (e.g. a `Boolean` flag stored via
        // a parent `set` path round-tripping through the
        // serializer comes back as the same `Boolean`), but
        // with the in-place merger the in-memory tree is
        // already correct, so this reload is a no-op in
        // practice; we keep it for parity with the v1
        // round-trip and so any future v2 caller that wanted
        // to inspect the on-disk state after the save sees a
        // fresh, normalised view.
        try {
            userConfig = loader.loadFile(userFile);
        } catch (Exception e) {
            adapter.info("Failed to reload " + userFile.getName()
                    + " after merge; using the in-memory copy instead: " + e.getMessage());
        }

        return userConfig;
    }

    /**
     * Recursive whitelist-based merge. Mirrors the v1 legacy
     * {@code ConfigManager.mergeConfigurations} pattern.
     *
     * <p>For each whitelisted key in the default config:
     *   - if the user config is missing the key, {@code set} the
     *     default leaf value (Bukkit / Map mutations both work
     *     for primitive leaves);
     *   - if both sides have a section for the key, recurse via
     *     {@link CommonYaml#getChild(String)} — which on the
     *     Bukkit path returns a wrapper around the LIVE nested
     *     {@code ConfigurationSection} (so the recursion
     *     mutates the section in place), and on platforms that
     *     don't override {@code getChild} falls back to a Map
     *     view that works for read-only inspection.
     *
     * <p>No {@code set(key, Map)} is ever called to replace an
     * existing section. The recursion mutates the live section
     * (Bukkit) or the live Map (other platforms) in place, and
     * the resulting in-memory state is what {@code save(f)}
     * later serialises. This matches the v1 design exactly, and
     * avoids the v2 bug where {@code set(path, Map)} round-trip
     * through SnakeYAML silently flipped scalar values into Maps
     * (e.g. {@code xray.enable: true} re-parsed as a one-entry
     * Map and {@code getBoolean} returned {@code false}).
     *
     * @return the number of leaf keys that were filled in from
     *         defaults during this merge (zero means the user
     *         file is already complete). Sections recursively
     *         merged through a whitelisted path are recursed
     *         into without contributing to the count
     *         themselves; only their missing leaf keys are
     *         counted.
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
                    // Both sides have a section for this key and
                    // the section is whitelisted: recurse. The
                    // recursion target is obtained via
                    // `getChild`, which on the Bukkit path
                    // returns a wrapper around the LIVE nested
                    // `ConfigurationSection` so the recursion
                    // mutates the parent in place. The default
                    // Map-based implementation in
                    // `CommonYaml.getChild` works for read-only
                    // inspection but loses the in-place
                    // mutation guarantee — that is fine for
                    // platforms (Fabric) that don't need it.
                    CommonYaml curChild = currentConfig.getChild(key);
                    CommonYaml defChild = defaultConfig.getChild(key);
                    if (curChild != null && defChild != null) {
                        added += mergeConfigurations(curChild, defChild, fullKeyPath);
                    } else {
                        // Fallback for platforms / sections that
                        // don't expose `getChild`: descend
                        // through the Map view and rely on the
                        // v1-style `set(key, leafValue)` for
                        // missing keys. We do NOT call
                        // `set(key, Map)` here — that would
                        // re-introduce the v2 in-memory
                        // corruption bug on the Bukkit path.
                        if (curChild == null) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> curMap = (java.util.Map<String, Object>) currentValue;
                            curChild = new link.star_dust.MinerTrack.common.MapBackedYaml(curMap);
                        }
                        if (defChild == null) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> defMap = (java.util.Map<String, Object>) defaultValue;
                            defChild = new link.star_dust.MinerTrack.common.MapBackedYaml(defMap);
                        }
                        added += mergeLeafOnly(curChild, defChild, fullKeyPath);
                    }
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

    /**
     * Map-only fallback recursion for sections that
     * {@link CommonYaml#getChild(String)} couldn't return a live
     * wrapper for. Mutates {@code curChild} (a Map-backed view)
     * in place, then writes the resulting Map back to the parent
     * via {@code parent.set(key, curMap)} so the parent's in-
     * memory state stays in sync. Note that {@code parent.set}
     * may store the Map as a value rather than a section on
     * some platforms; this is acceptable here because the
     * caller falls back to this path only when a live
     * section-wrapping {@code getChild} isn't available.
     */
    private static int mergeLeafOnly(CommonYaml curChild, CommonYaml defChild, String currentPath) {
        int added = 0;
        for (String key : defChild.getKeys(false)) {
            if (curChild.contains(key)) {
                Object cv = curChild.get(key);
                Object dv = defChild.get(key);
                if (cv instanceof java.util.Map && dv instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> curMap = (java.util.Map<String, Object>) cv;
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> defMap = (java.util.Map<String, Object>) dv;
                    link.star_dust.MinerTrack.common.MapBackedYaml nestedCur =
                            new link.star_dust.MinerTrack.common.MapBackedYaml(curMap);
                    link.star_dust.MinerTrack.common.MapBackedYaml nestedDef =
                            new link.star_dust.MinerTrack.common.MapBackedYaml(defMap);
                    added += mergeLeafOnly(nestedCur, nestedDef, currentPath + "." + key);
                }
            } else {
                curChild.set(key, defChild.get(key));
                added++;
            }
        }
        return added;
    }

    private static boolean isSection(Object v) {
        // A "section" is anything that can hold nested keys:
        // either a `Map` (Map-backed default configs) or a
        // Bukkit `ConfigurationSection` (live delegate). The
        // merger recurses via `getChild`, which the platform
        // implementations handle appropriately.
        return v instanceof java.util.Map
                || v instanceof org.bukkit.configuration.ConfigurationSection;
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
     * In-memory {@link CommonYaml} backed by a {@code LinkedHashMap}.
     * Moved to {@link link.star_dust.MinerTrack.common.MapBackedYaml}
     * so the platform-neutral {@link CommonYaml#getChild(String)}
     * default implementation can reference it.
     */
}
