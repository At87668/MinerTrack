package link.star_dust.MinerTrack.common;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform-agnostic YAML facade.
 *
 * Replaces direct {@code org.bukkit.configuration.file.YamlConfiguration}
 * usage from the {@code common} and {@code core} packages. Each platform
 * (Bukkit, Fabric) supplies its own implementation that wraps the
 * platform's native YAML library, while the core logic only depends on this
 * interface.
 *
 * The surface is intentionally small: it is only the methods the core
 * config + detection code path actually needs.
 */
public interface CommonYaml {

    // ── Typed accessors ────────────────────────────────────────────────────

    Object get(String path);

    int getInt(String path, int def);

    boolean getBoolean(String path, boolean def);

    double getDouble(String path, double def);

    String getString(String path, String def);

    List<String> getStringList(String path);

    /** Get a nested section. Returned values are platform-specific but
     *  callers should only treat them as opaque objects. */
    Object get(String path, Object def);

    boolean contains(String path);

    Set<String> getKeys(boolean deep);

    /** Iterate all keys (deep) and return a snapshot. */
    default Set<String> getAllKeys() {
        return getKeys(true);
    }

    // ── Mutators ──────────────────────────────────────────────────────────

    void set(String path, Object value);

    // ── I/O ───────────────────────────────────────────────────────────────

    /** Persist to the given file. */
    void save(File file);

    /**
     * Return a {@link CommonYaml} that represents the nested
     * section at {@code path} and whose {@link #set(String, Object)}
     * / {@link #getKeys(boolean)} / {@link #get(String)} calls
     * mutate the parent in place. This mirrors the v1 legacy
     * {@code ConfigManager.mergeConfigurations} pattern, which
     * recursed directly on the platform's
     * {@code ConfigurationSection} so that {@code set} for missing
     * keys landed in the live section the runtime reads from.
     *
     * <p>The default implementation returns a flat Map view, which
     * is fine for read-only inspection but breaks the v1 save
     * round-trip on platforms whose YAML serializer (SnakeYAML
     * for Bukkit) silently flips scalar values into Maps when
     * the in-memory tree is a mix of {@code ConfigurationSection}
     * and bare {@code Map}. Platform implementations that
     * preserve section identity on assignment (Bukkit,
     * Paper) override this to return a wrapper around the live
     * nested section.
     *
     * <p>Returns {@code null} when {@code path} does not point at
     * a section in this config.
     */
    default CommonYaml getChild(String path) {
        Object v = get(path);
        if (v instanceof java.util.Map) {
            return new MapBackedYaml((java.util.Map<String, Object>) v);
        }
        return null;
    }
}
