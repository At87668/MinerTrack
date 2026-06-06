package link.star_dust.MinerTrack.core.config;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.YamlLoader;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors the v1 LanguageManager / BukkitLanguageBridge completion behavior:
 * - Every key present in the JAR default language.yml is guaranteed to exist
 *   in the user's file (full coverage, including lists and nested sections).
 * - User-customized values are preserved (overlay, not replace).
 * - Result is saved back to the user's file.
 *
 * <p>Unlike {@link ConfigMerger}, this merger does <strong>not</strong> use a
 * whitelist or recurse based on per-path rules. Language files are flat-ish
 * and the expectation is that newly added keys are always surfaced to the
 * server admin as soon as a new plugin version is deployed.
 */
public class LanguageMerger {

    /**
     * Load the user's language file, overlay the JAR default on top, then
     * save the result back to disk so every default key is materialized.
     *
     * @param userFile     the user's language file on disk
     * @param resourcePath the path inside the JAR (e.g. "language.yml")
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
            defaultsConfig = defaultStream != null
                    ? loader.loadStream(defaultStream)
                    : loader.loadFile(userFile);
        } catch (Exception e) {
            defaultsConfig = loader.loadFile(userFile);
        }

        // Read the user's existing file to overlay their customisations.
        CommonYaml userExisting = loader.loadFile(userFile);

        // Start from defaults, then overlay user values.
        CommonYaml merged = defaultsConfig;
        copyAll(userExisting, merged);

        try {
            merged.save(userFile);
        } catch (Exception e) {
            adapter.info("Could not save merged language " + userFile.getName() + ": " + e.getMessage());
        }

        return merged;
    }

    /**
     * Flat copy of every key (including nested sections and lists) from
     * {@code src} into {@code dst}. We operate on the {@link Map}
     * representation that every {@link CommonYaml} backend exposes for
     * sections, so this works for any platform.
     */
    private static void copyAll(CommonYaml src, CommonYaml dst) {
        if (src == null || dst == null) return;
        Set<String> keys = src.getKeys(true);
        for (String key : keys) {
            Object v = deepGet(src, key);
            if (v == null) continue;
            deepSet(dst, key, v);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object deepGet(CommonYaml yaml, String path) {
        String[] parts = path.split("\\.");
        Object cur = yaml.get(parts[0]);
        for (int i = 1; i < parts.length && cur != null; i++) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(parts[i]);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static void deepSet(CommonYaml yaml, String path, Object value) {
        String[] parts = path.split("\\.");
        // For nested keys, we need a mutable Map at each level. The platform
        // adapters (e.g. BukkitCommonYaml) implement `set(path, value)` for
        // simple keys; for compound keys we set the whole subtree.
        if (parts.length == 1) {
            yaml.set(parts[0], value);
            return;
        }
        Object root = yaml.get(parts[0]);
        if (!(root instanceof Map)) {
            // The platform's get() returns the underlying value. If it's not
            // a map we cannot descend; fall back to a single-key set.
            yaml.set(parts[0], value);
            return;
        }
        // Walk down, mutating the existing maps in place.
        Map<String, Object> cur = (Map<String, Object>) root;
        for (int i = 1; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) {
                java.util.LinkedHashMap<String, Object> created = new java.util.LinkedHashMap<>();
                cur.put(parts[i], created);
                cur = created;
            } else {
                cur = (Map<String, Object>) next;
            }
        }
        cur.put(parts[parts.length - 1], value);
        // Persist the mutated top-level map back into the YAML.
        yaml.set(parts[0], root);
    }
}
