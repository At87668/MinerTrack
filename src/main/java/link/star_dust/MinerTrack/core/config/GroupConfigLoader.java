package link.star_dust.MinerTrack.core.config;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Pattern;

public class GroupConfigLoader {

    /**
     * Result of loading group configurations: holds the fully-populated maps
     * needed by {@link link.star_dust.MinerTrack.common.CoreConfig}.
     */
    public static class GroupLoadResult {
        public final Map<String, CommonYaml> groupConfigs = new HashMap<>();
        public final Map<String, String> worldToGroup = new HashMap<>();
        public final Map<String, List<Pattern>> groupWorldPatterns = new HashMap<>();
        public String defaultUnnamedGroupKey;
    }

    private final PluginAdapter adapter;
    private final CommonYaml mainConfig;
    private final YamlLoader loader;

    public GroupConfigLoader(PluginAdapter adapter, CommonYaml mainConfig, YamlLoader loader) {
        this.adapter = adapter;
        this.mainConfig = mainConfig;
        this.loader = loader;
    }

    public GroupLoadResult load() {
        File configDir = new File(adapter.getDataFolder(), "Configuration");
        if (!configDir.exists()) configDir.mkdirs();

        ensureGroupFilesExist(configDir);

        File[] files = configDir.listFiles((d, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null) {
            adapter.info("No group configurations found in " + configDir.getAbsolutePath());
            return new GroupLoadResult();
        }

        GroupLoadResult result = new GroupLoadResult();

        for (File f : files) {
            try {
                CommonYaml yc = loader.loadFile(f);
                String key = stripExtension(f.getName());
                result.groupConfigs.put(key, yc);
                adapter.info("Loaded group configuration: " + key + " (" + f.getName() + ")");

                // Per-group `worlds` list (legacy feature). Values are
                // normalised to canonical Minecraft dimension ids so a
                // group file that lists `world` still matches a runtime
                // `minecraft:overworld` worldName.
                List<String> worlds = yc.getStringList("worlds");
                if (worlds != null && !worlds.isEmpty()) {
                    for (String w : worlds) {
                        applyWorldEntry(w, key, result);
                    }
                }
            } catch (Exception e) {
                adapter.info("Failed to load group config " + f.getName() + ": " + e.getMessage());
            }
        }

        // Read "xray.worlds" mapping from main config (platform-agnostic Map access).
        // Values are expected to be canonical Minecraft dimension ids
        // (minecraft:overworld / minecraft:the_nether / minecraft:the_end);
        // we normalise defensively so a stray Bukkit folder name still works.
        Object worldsObj = mainConfig.get("xray.worlds");
        if (worldsObj == null) {
            adapter.info("config.yml has no 'xray.worlds' section; no per-world detection will be active. "
                    + "Add a `xray.worlds` block mapping group file names to dimension ids (e.g. "
                    + "`'overworld': [minecraft:overworld]`) to enable detection.");
        } else if (!(worldsObj instanceof Map)
                && !link.star_dust.MinerTrack.common.PlatformTypes.isConfigurationSection(worldsObj)) {
            adapter.info("config.yml 'xray.worlds' is not a map (got "
                    + worldsObj.getClass().getSimpleName()
                    + "); expected a map of group name -> [dimension id]. "
                    + "Check the YAML structure under xray.worlds in config.yml.");
        } else {
            // The v2 design lets `mainConfig.get("xray.worlds")`
            // return either a `Map` (Map-backed configs / the
            // flat default-stream view) or a Bukkit
            // `ConfigurationSection` (the live delegate the
            // v1-style in-place merger produced). Normalise
            // both to a `Map<String, Object>` view via the
            // platform-neutral {@link
            // link.star_dust.MinerTrack.common.PlatformTypes}
            // helper (which uses reflection to read
            // {@code getValues(false)} on a Bukkit section and
            // falls back to a direct Map cast on every other
            // platform).
            java.util.Map<String, Object> worldsSection;
            if (worldsObj instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> raw = (java.util.Map<String, Object>) worldsObj;
                worldsSection = raw;
            } else {
                worldsSection = link.star_dust.MinerTrack.common.PlatformTypes.getValues(worldsObj);
            }
            for (Map.Entry<String, Object> entry : worldsSection.entrySet()) {
                try {
                    String fileKey = entry.getKey();
                    Object v = entry.getValue();
                    List<String> list = asStringList(v);
                    if (list == null) {
                        adapter.info("xray.worlds." + fileKey + " is not a list of dimension ids (got "
                                + (v == null ? "null" : v.getClass().getSimpleName())
                                + "); expected e.g. `[" + fileKey + ": [minecraft:overworld]]`.");
                        continue;
                    }
                    String k = stripExtension(fileKey);
                    for (String w : list) {
                        if (w == null) continue;
                        if (w.equalsIgnoreCase("all_unnamed_world")) {
                            result.defaultUnnamedGroupKey = k;
                        } else {
                            String norm = link.star_dust.MinerTrack.common.DimensionId.normalize(w);
                            String resolved = norm != null ? norm : w;
                            if (result.groupConfigs.containsKey(k)) {
                                result.worldToGroup.put(resolved, k);
                            } else {
                                adapter.info("xray.worlds references group '" + k + "' but no such group file was loaded; skipping mapping for world '" + w + "'");
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            adapter.info("Built world-to-group mapping from xray.worlds: " + result.worldToGroup
                    + (result.defaultUnnamedGroupKey != null
                        ? " (default unnamed -> " + result.defaultUnnamedGroupKey + ")"
                        : ""));
        }

        for (File f : files) {
            // Version check: if the user file's _config-version is older
            // than the JAR default's, back it up and bump in place.
            // Mirrors v1's main-config `checkAndUpgradeConfig()`.
            checkAndUpgradeGroupConfig(f, result);

            try (InputStream defaultStream = adapter.getResource("Configuration/" + f.getName())) {
                if (defaultStream != null) {
                    CommonYaml defaultGroup = loader.loadStream(defaultStream);
                    String curKey = stripExtension(f.getName());
                    CommonYaml current = result.groupConfigs.get(curKey);
                    if (current != null) {
                        int added = mergeGroupConfigurations(current, defaultGroup);
                        if (added > 0) {
                            adapter.info("[Merger] Configuration/" + f.getName() + ": filled "
                                    + added + " missing key(s) from defaults.");
                        }
                        // Save back. v1-equivalent: the merge's
                        // in-place recursion (via
                        // `CommonYaml.getChild`) preserved the
                        // live `ConfigurationSection` tree, so
                        // the YAML serializer sees the same
                        // structure the runtime reads from.
                        // SnakeYAML will still re-serialise the
                        // file (stripping comments, reordering
                        // keys), but unlike the v2
                        // `set(path, Map)`-based merger it will
                        // NOT flip scalar values into Maps.
                        try {
                            current.save(f);
                        } catch (Exception e) {
                            adapter.info("Failed to save merged group config "
                                    + f.getName() + ": " + e.getMessage());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        List<String> toUnload = new ArrayList<>();
        for (String groupKey : new ArrayList<>(result.groupConfigs.keySet())) {
            boolean assigned = false;
            for (String mapped : result.worldToGroup.values()) {
                if (groupKey.equals(mapped)) { assigned = true; break; }
            }
            if (!assigned && result.groupWorldPatterns.containsKey(groupKey) && !result.groupWorldPatterns.get(groupKey).isEmpty()) {
                assigned = true;
            }
            if (!assigned && result.defaultUnnamedGroupKey != null && result.defaultUnnamedGroupKey.equals(groupKey)) {
                assigned = true;
            }
            if (!assigned) toUnload.add(groupKey);
        }
        for (String ung : toUnload) {
            result.groupConfigs.remove(ung);
            result.groupWorldPatterns.remove(ung);
        }

        // Clean up worldToGroup entries pointing to missing group keys
        for (Iterator<Map.Entry<String, String>> it = result.worldToGroup.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> e = it.next();
            if (!result.groupConfigs.containsKey(e.getValue())) it.remove();
        }

        // Log mapping relationships. The header line shows the count of
        // groups that survived the cleanup (the ones the rest of the
        // plugin will actually use). The body lists each surviving
        // group with its world-to-group mapping. Any group that the
        // cleanup loop unloaded (no world mapping at all) is reported
        // separately at the end so admins can see why a file they
        // dropped into Configuration/ ended up being ignored.
        try {
            adapter.info("Loaded group configurations (" + result.groupConfigs.size() + " active):");
            for (String key : result.groupConfigs.keySet()) {
                List<String> exactWorlds = new ArrayList<>();
                for (Map.Entry<String, String> e : result.worldToGroup.entrySet()) {
                    if (e.getValue().equals(key)) exactWorlds.add(e.getKey());
                }
                List<String> patternStrings = new ArrayList<>();
                List<Pattern> pats = result.groupWorldPatterns.get(key);
                if (pats != null) {
                    for (Pattern p : pats) patternStrings.add(p.pattern());
                }
                adapter.info(" - " + key + ": worlds=" + exactWorlds + ", patterns=" + patternStrings);
            }
            if (result.defaultUnnamedGroupKey != null) {
                adapter.info("Default unnamed group (all_unnamed_world): " + result.defaultUnnamedGroupKey);
            }
            if (!toUnload.isEmpty()) {
                adapter.info("Unloaded group configs (no world mapping): " + toUnload);
            }
        } catch (Exception ignored) {}

        return result;
    }

    /**
     * Ensure every group key referenced in xray.worlds has a corresponding file.
     * Creates from JAR resource first, falls back to copying overworld.yml.
     */
    private void ensureGroupFilesExist(File configDir) {
        Object worldsObj = mainConfig.get("xray.worlds");
        // v2 returns either a `Map` (Map-backed configs) or a
        // Bukkit `ConfigurationSection` (live delegate). Handle
        // both: we only need to iterate the keys here. The
        // Bukkit branch is reached via the platform-neutral
        // {@link PlatformTypes} helper, which uses reflection
        // to invoke {@code getKeys(false)} without ever
        // referencing the Bukkit class literal (the shadow
        // JAR excludes {@code org/bukkit/} and a class literal
        // here would fail to verify on Fabric).
        java.util.Set<String> fileKeys;
        if (worldsObj instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> raw = (java.util.Map<String, Object>) worldsObj;
            fileKeys = raw.keySet();
        } else if (link.star_dust.MinerTrack.common.PlatformTypes.isConfigurationSection(worldsObj)) {
            fileKeys = link.star_dust.MinerTrack.common.PlatformTypes.getKeys(worldsObj);
        } else {
            return;
        }

        for (String fileKey : fileKeys) {
            String groupKey = stripExtension(fileKey);
            String filename = groupKey + ".yml";
            File out = new File(configDir, filename);
            if (!out.exists()) {
                try {
                    adapter.saveResource("Configuration/" + filename, false);
                    adapter.info("Created missing group config from resource: " + filename);
                } catch (Exception e) {
                    // Fallback: copy overworld.yml
                    try (InputStream is = adapter.getResource("Configuration/overworld.yml")) {
                        if (is != null) {
                            try (OutputStream os = new FileOutputStream(out)) {
                                byte[] buf = new byte[8192];
                                int r;
                                while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                            }
                            adapter.info("Created missing group config " + filename + " from default overworld.yml");
                        } else {
                            adapter.info("Resource overworld.yml not found inside jar; cannot create " + filename);
                        }
                    } catch (Exception ioe) {
                        adapter.info("Failed to create missing group config " + filename + ": " + ioe.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Check the {@code _config-version} of a per-world group config file
     * against the matching JAR default. If the user's file is missing the
     * version key or its version is older than the JAR's, back up the
     * user's file (e.g. {@code overworld-2026-06-06.yml.bak}) and bump
     * the in-memory {@code _config-version} up to the latest. We do NOT
     * replace the entire file with the JAR default — that would destroy
     * any per-group customisations the admin made. The subsequent merge
     * step (driven by the same JAR default) will fill in any keys the
     * user is missing relative to the latest default.
     *
     * <p>Platform-agnostic — all I/O goes through {@link PluginAdapter}
     * and {@link YamlLoader}.
     *
     * <p>No-op when the user's version is already up to date, when the
     * file has no matching JAR resource, or when the JAR resource itself
     * carries no {@code _config-version} key.
     */
    private void checkAndUpgradeGroupConfig(File f, GroupLoadResult result) {
        String resourcePath = "Configuration/" + f.getName();
        try (InputStream defaultStream = adapter.getResource(resourcePath)) {
            if (defaultStream == null) return;
            CommonYaml defaultGroup = loader.loadStream(defaultStream);
            int defaultVersion = defaultGroup.getInt("_config-version", 0);
            if (defaultVersion <= 0) return;

            String curKey = stripExtension(f.getName());
            CommonYaml current = result.groupConfigs.get(curKey);
            if (current == null) return;
            int currentVersion = current.getInt("_config-version", 0);
            if (currentVersion >= defaultVersion) return;

            adapter.info("Group config " + f.getName() + " version " + currentVersion
                    + " is outdated (latest: " + defaultVersion + "). Bumping in place; "
                    + "the merge step will add any new keys.");

            // Backup the user's stale file with a timestamped suffix so
            // it sits next to the live file in the Configuration/ folder.
            String stamp = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            File backupFile = new File(f.getParentFile(),
                    stripExtension(f.getName()) + "-" + stamp + ".yml.bak");
            try {
                java.nio.file.Files.copy(f.toPath(), backupFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                adapter.info("Backed up " + f.getName() + " to " + backupFile.getName());
            } catch (Exception e) {
                adapter.info("Failed to backup " + f.getName() + ": " + e.getMessage());
            }

            // Bump the in-memory version; the merge + save below will
            // persist it alongside any newly-added keys.
            current.set("_config-version", defaultVersion);
        } catch (Exception e) {
            adapter.info("Error checking group config version for " + f.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Recursive merge for group configs. Mirrors the v1 legacy
     * {@code ConfigManager.mergeConfigurations} pattern: for
     * each key in defaults, add the missing leaf value via
     * {@code set} (Bukkit handles leaf values correctly), or
     * recurse into the live nested section via
     * {@link CommonYaml#getChild(String)} when both sides have
     * a section for the key. No {@code set(path, Map)} is
     * ever called, which is what was breaking the
     * save round-trip in the previous v2 implementation.
     *
     * @return the number of leaf keys that were filled in from
     *         defaults (zero means the user file is already
     *         complete).
     */
    private int mergeGroupConfigurations(CommonYaml current, CommonYaml defaults) {
        if (current == null || defaults == null) return 0;
        int added = 0;
        for (String key : defaults.getKeys(false)) {
            if (current.contains(key)) {
                Object cv = current.get(key);
                Object dv = defaults.get(key);
                // "Section" here means anything that can hold
                // nested keys: a `Map` (Map-backed configs /
                // fabric configs) or a Bukkit
                // `ConfigurationSection` (live delegate). The
                // merger recurses via `getChild` which the
                // platform implementations handle correctly.
                if (isSection(cv) && isSection(dv)) {
                    CommonYaml curChild = current.getChild(key);
                    CommonYaml defChild = defaults.getChild(key);
                    if (curChild != null && defChild != null) {
                        added += mergeGroupConfigurations(curChild, defChild);
                    } else {
                        // Fallback for sections that don't
                        // expose `getChild` (Map-backed configs
                        // where the section is stored as a
                        // plain Map value rather than a live
                        // nested wrapper). Mutate the Map in
                        // place and write it back so the
                        // parent's in-memory state stays in
                        // sync. The legacy `set(path, Map)`
                        // corruption is avoided because the
                        // fallback only fires for non-Bukkit
                        // backends; on Bukkit, `getChild` always
                        // returns a live wrapper.
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> curMap = (java.util.Map<String, Object>) cv;
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> defMap = (java.util.Map<String, Object>) dv;
                        added += deepMergeInto(curMap, defMap);
                        current.set(key, curMap);
                    }
                }
            } else {
                current.set(key, defaults.get(key));
                added++;
            }
        }
        return added;
    }

    private static boolean isSection(Object v) {
        // Use the platform-neutral helper so we never reference
        // the Bukkit {@code ConfigurationSection} class literal
        // (which would fail to verify on the Fabric classpath).
        return v instanceof java.util.Map
                || link.star_dust.MinerTrack.common.PlatformTypes.isConfigurationSection(v);
    }

    @SuppressWarnings("unchecked")
    private int deepMergeInto(Map<String, Object> current, Map<String, Object> defaults) {
        int added = 0;
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            Object cv = current.get(e.getKey());
            Object dv = e.getValue();
            if (cv instanceof Map && dv instanceof Map) {
                added += deepMergeInto((Map<String, Object>) cv, (Map<String, Object>) dv);
            } else if (!current.containsKey(e.getKey())) {
                current.put(e.getKey(), dv);
                added++;
            }
        }
        return added;
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object v) {
        // Accept both YAML list values (e.g. `key: [a, b]`) and
        // single-string values (e.g. `key: a`). A common mistake
        // when editing config.yml by hand is to drop the `- `
        // prefix on a single world entry, so we tolerate that here
        // and wrap the string into a one-element list rather than
        // silently dropping the mapping.
        if (v instanceof List) return (List<String>) v;
        if (v instanceof String) {
            List<String> one = new ArrayList<>(1);
            one.add((String) v);
            return one;
        }
        return null;
    }

    private static String stripExtension(String name) {
        return name.toLowerCase().endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
    }

    private void applyWorldEntry(String w, String key, GroupLoadResult result) {
        if (w == null) return;
        if (w.equalsIgnoreCase("all_unnamed_world")) {
            result.defaultUnnamedGroupKey = key;
            return;
        }
        String norm = link.star_dust.MinerTrack.common.DimensionId.normalize(w);
        if (norm != null) {
            result.worldToGroup.put(norm, key);
            return;
        }
        try {
            Pattern p = Pattern.compile(w, Pattern.CASE_INSENSITIVE);
            result.groupWorldPatterns.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        } catch (Exception e) {
            adapter.info("Invalid world pattern '" + w + "' in group " + key + ": " + e.getMessage());
        }
    }
}
