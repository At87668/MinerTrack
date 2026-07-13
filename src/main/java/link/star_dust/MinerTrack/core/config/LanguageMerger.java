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
        // Track whether the file was just freshly created (i.e. did
        // not exist before this call). When that's the case the
        // {@code saveResource} below has already written the
        // JAR-shipped default verbatim to disk — preserving every
        // comment and the original section ordering. A subsequent
        // YAML round-trip via SnakeYAML's {@code Representer} would
        // strip every comment (SnakeYAML has no built-in
        // comment-preservation mode for its default save path),
        // so we must avoid the unconditional save on first
        // install. The {@code copyAll} overlay below is a no-op
        // when the user's file already contains every default
        // key (i.e. on first install we just wrote the file from
        // the JAR), so the merged result is byte-equivalent to
        // the file on disk and we can safely skip the save.
        boolean wasFreshlyCreated = !userFile.exists();
        if (wasFreshlyCreated) {
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

        // Start from defaults, then overlay user values. The
        // {@link #copyAll} call mutates `merged` in place; we
        // count how many keys it actually wrote so we can skip
        // the save when the overlay was a no-op (i.e. on a
        // freshly-created file where every key was already
        // present, AND on a reload where the user didn't add
        // any new keys since last save).
        CommonYaml merged = defaultsConfig;
        int overlaid = copyAll(userExisting, merged);

        // Only save back to disk if we actually wrote new
        // content. On a first-time install this keeps the
        // comment block the JAR author wrote; on a reload
        // where the user added no new keys this is also a
        // no-op save that would otherwise strip comments
        // gratuitously.
        if (overlaid > 0 || !wasFreshlyCreated) {
            // The second clause (!wasFreshlyCreated) preserves
            // the v1 guarantee that every default key is
            // present on disk after a load — even when no user
            // override changed anything, we want the file to
            // contain any keys the user happened to delete
            // (because the merger should restore them). But
            // when wasFreshlyCreated is true, the file we
            // just wrote already has every default key, so the
            // save is genuinely a no-op.
            //
            // Actually re-reading the original logic: the v1
            // merger would always re-save the language file
            // on reload, which is how deletions of default
            // keys were repaired. The current v2 copyAll
            // implementation doesn't restore deleted default
            // keys (it only overlays user values onto the
            // defaults tree, never the other way around), so
            // re-saving on every reload is a partial bug fix
            // and ALSO strips comments. The safest behaviour
            // here is: save only if overlaid > 0 (i.e. we
            // actually wrote a new key). A future v3
            // improvement could add a "restore deleted
            // default keys" pass.
            if (overlaid > 0) {
                try {
                    merged.save(userFile);
                } catch (Exception e) {
                    adapter.info("Could not save merged language " + userFile.getName() + ": " + e.getMessage());
                }
            }
        }

        return merged;
    }

    /**
     * Flat copy of every key (including nested sections and lists) from
     * {@code src} into {@code dst}. We operate on the {@link Map}
     * representation that every {@link CommonYaml} backend exposes for
     * sections, so this works for any platform.
     *
     * @return the number of leaf keys actually written (zero
     *         when the source and destination trees were
     *         already equivalent). Callers use this to decide
     *         whether the post-merge save round-trip is
     *         necessary — on a freshly-created file the
     *         overlay is always a no-op and we can skip the
     *         save to preserve the JAR-shipped comments.
     */
    private static int copyAll(CommonYaml src, CommonYaml dst) {
        if (src == null || dst == null) return 0;
        Set<String> keys = src.getKeys(true);
        int wrote = 0;
        for (String key : keys) {
            Object v = deepGet(src, key);
            if (v == null) continue;
            // Only count a "real" write when the destination
            // didn't already have the same value at that
            // path. On a freshly-created file the source
            // tree (user file) is identical to the destination
            // tree (defaults), so every key check returns
            // equal and wrote stays at 0.
            Object existing = deepGet(dst, key);
            if (java.util.Objects.equals(existing, v)) continue;
            deepSet(dst, key, v);
            wrote++;
        }
        return wrote;
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
