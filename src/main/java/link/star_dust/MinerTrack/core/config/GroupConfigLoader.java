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
                        if (w == null || w.isEmpty()) continue;
                        if (w.contains("*") || w.contains("?")) {
                            // Wildcards match raw folder names (e.g.
                            // `world*`); keep the pattern as-is so it
                            // continues to match against the folder name
                            // we receive at runtime.
                            String regex = "^" + w.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$";
                            Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                            result.groupWorldPatterns.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                        } else {
                            String norm = link.star_dust.MinerTrack.common.DimensionId.normalize(w);
                            result.worldToGroup.put(norm != null ? norm : w, key);
                        }
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
        if (worldsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> worldsSection = (Map<String, Object>) worldsObj;
            for (Map.Entry<String, Object> entry : worldsSection.entrySet()) {
                try {
                    String fileKey = entry.getKey();
                    Object v = entry.getValue();
                    List<String> list = asStringList(v);
                    if (list == null) continue;
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
        }

        for (File f : files) {
            // Version check: if the user file's _config-version is older
            // than the JAR default's, back it up and replace with the JAR
            // default (mirrors v1's main-config checkAndUpgradeConfig()).
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
                                    + added + " missing key(s) from defaults");
                        }
                        current.save(f);
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
        if (!(worldsObj instanceof Map)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> worldsSection = (Map<String, Object>) worldsObj;

        for (String fileKey : worldsSection.keySet()) {
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
     * user's file (e.g. {@code overworld-2026-06-06.yml.bak}) and replace
     * it with the JAR default. The replacement is then reloaded into the
     * shared {@link GroupLoadResult#groupConfigs} map so the subsequent
     * merge step operates on the fresh default rather than the stale
     * user file.
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
                    + " is outdated (latest: " + defaultVersion + "). Backing up and updating.");

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

            // Replace with the JAR default. saveResource(..., true) is
            // platform-agnostic on the Bukkit side and a no-op-then-copy
            // on Fabric; either way the on-disk file ends up as the JAR
            // default.
            try {
                adapter.saveResource(resourcePath, true);
                adapter.info("Updated " + f.getName() + " to version " + defaultVersion);
            } catch (Exception e) {
                adapter.info("Failed to replace " + f.getName() + " with default: " + e.getMessage());
                return;
            }

            // Reload from disk so the in-memory map reflects the new
            // default contents (the merge step below will fill in any
            // new keys if the JAR default is later expanded).
            try {
                result.groupConfigs.put(curKey, loader.loadFile(f));
            } catch (Exception e) {
                adapter.info("Failed to reload " + f.getName() + " after upgrade: " + e.getMessage());
            }
        } catch (Exception e) {
            adapter.info("Error checking group config version for " + f.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Recursive merge for group configs: any missing key in {@code current}
     * is filled from {@code defaults}, sections are recursed into. This
     * mirrors the previous v1 behavior but uses {@link CommonYaml}.
     *
     * @return the number of leaf keys that were filled in from defaults
     *         (zero means the user file is already complete).
     */
    private int mergeGroupConfigurations(CommonYaml current, CommonYaml defaults) {
        if (current == null || defaults == null) return 0;
        int added = 0;
        for (String key : defaults.getKeys(false)) {
            if (current.contains(key)) {
                Object cv = current.get(key);
                Object dv = defaults.get(key);
                if (cv instanceof Map && dv instanceof Map) {
                    // Both are sections; recurse via in-memory map views.
                    // The current platform (BukkitCommonYaml) does not expose
                    // a section handle, so we work directly with the maps.
                    // This is fine because the merger only ever adds missing
                    // keys (no value replacement).
                    added += deepMergeInto((Map<String, Object>) cv, (Map<String, Object>) dv);
                    current.set(key, cv);
                }
            } else {
                current.set(key, defaults.get(key));
                added++;
            }
        }
        return added;
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
        if (v instanceof List) return (List<String>) v;
        return null;
    }

    private static String stripExtension(String name) {
        return name.toLowerCase().endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
    }
}
