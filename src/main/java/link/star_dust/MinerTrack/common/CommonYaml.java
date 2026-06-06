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
}
