package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Bukkit implementation of {@link CommonYaml} backed by
 * {@link YamlConfiguration}. The wrapper exists so the common/core packages
 * never need to import {@code org.bukkit.configuration.*} directly.
 *
 * All reads/writes forward to the wrapped Bukkit instance. Identity is
 * preserved so equality / hashCode / mutation semantics match Bukkit.
 *
 * <p>The class can wrap either a root {@link YamlConfiguration}
 * (passed via the public constructor) or a nested
 * {@link ConfigurationSection} (returned by
 * {@link #getChild(String)} for recursive merges). The
 * {@code save} method only works on root instances — the merger
 * never calls {@code save} on a nested-section wrapper, so the
 * asymmetry is safe.
 */
public class BukkitCommonYaml implements CommonYaml {

    /**
     * The section this wrapper delegates to. For a root wrapper
     * this is a {@link YamlConfiguration}; for a nested-section
     * wrapper (returned by {@link #getChild(String)}) it is a
     * {@link ConfigurationSection}. Both inherit the
     * {@code get}/{@code set}/{@code getKeys}/{@code contains}
     * API the merger uses.
     */
    private final ConfigurationSection delegate;

    public BukkitCommonYaml(YamlConfiguration delegate) {
        this.delegate = delegate;
    }

    /** Constructor for an empty in-memory config. */
    public BukkitCommonYaml() {
        this(new YamlConfiguration());
    }

    /**
     * Constructor used by {@link #getChild(String)} to wrap a
     * nested Bukkit {@link ConfigurationSection} so the
     * platform-neutral merger can recurse on it. The wrapped
     * section is the LIVE section held by the parent's
     * delegate; mutations through {@code set} land in the
     * parent's in-memory tree, and {@code save(f)} on the
     * parent (or root) serialises them.
     */
    private BukkitCommonYaml(ConfigurationSection nestedSection) {
        this.delegate = nestedSection;
    }

    /** Underlying Bukkit instance (use only from the bukkit package). */
    public YamlConfiguration getDelegate() {
        return delegate instanceof YamlConfiguration
                ? (YamlConfiguration) delegate
                : null;
    }

    @Override
    public Object get(String path) {
        Object v = delegate.get(path);
        // pattern: when a path points at a Bukkit
        // `ConfigurationSection`, return the SECTION itself
        // (not a flattened copy) so the merger can recurse on
        // it in place. The previous v2 implementation flattened
        // every nested section into a fresh `LinkedHashMap` and
        // then had to round-trip it back into the delegate via
        // `set(path, Map)`; that round-trip silently flipped
        // scalar values into Maps under SnakeYAML's serializer
        // (e.g. `xray.enable: true` re-parsed as a one-entry
        // Map and `getBoolean` returned false), which is the
        // "xray.enable reads as false after the merger runs"
        // bug. Returning the live `ConfigurationSection` lets
        // the merger mutate it through `set` calls (which Bukkit
        // handles correctly for leaf values) and the subsequent
        // `save` round-trip serialises the same in-memory
        // structure the runtime reads from.
        //
        // Callers that need a `Map<String, Object>` for legacy
        // reasons (e.g. `CoreConfig.getGroupConfigForWorld` for
        // `mainConfig.get("xray.worlds")`) handle both shapes
        // via the `getValues` / `getKeys` walk; if any caller
        // still requires a Map, the section's
        // `getValues(false)` returns a `Map<String, Object>`
        // where the nested values are also `ConfigurationSection`s
        // (recursive flattening not needed because the caller
        // descends through `get` again).
        if (v instanceof org.bukkit.configuration.ConfigurationSection) {
            return v;
        }
        // After a merger pass the delegate may hold a plain
        // Map (the legacy `set(path, Map)` round-trip left
        // some entries that way). Return it as-is; nested
        // values are walked through `get` again on subsequent
        // calls.
        return v;
    }

    /**
     * Section-level accessor used by {@link
     * link.star_dust.MinerTrack.core.config.ConfigMerger} when
     * it needs to recurse into a nested section without
     * going through a flattened Map. Returns the live Bukkit
     * {@link org.bukkit.configuration.ConfigurationSection} so
     * the merger can call {@code set(key, value)} on it
     * directly — exactly the v1 legacy pattern that works
     * correctly with SnakeYAML's save round-trip. Returns
     * {@code null} for non-section values or missing paths.
     */
    public org.bukkit.configuration.ConfigurationSection getSection(String path) {
        Object v = delegate.get(path);
        if (v instanceof org.bukkit.configuration.ConfigurationSection) {
            return (org.bukkit.configuration.ConfigurationSection) v;
        }
        return null;
    }

    @Override
    public Object get(String path, Object def) {
        return delegate.get(path, def);
    }

    @Override
    public int getInt(String path, int def) {
        return delegate.getInt(path, def);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return delegate.getBoolean(path, def);
    }

    @Override
    public double getDouble(String path, double def) {
        return delegate.getDouble(path, def);
    }

    @Override
    public String getString(String path, String def) {
        return delegate.getString(path, def);
    }

    @Override
    public List<String> getStringList(String path) {
        return delegate.getStringList(path);
    }

    @Override
    public boolean contains(String path) {
        return delegate.contains(path);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getKeys(boolean deep) {
        return delegate.getKeys(deep);
    }

    @Override
    public void set(String path, Object value) {
        delegate.set(path, value);
    }

    @Override
    public void save(File file) {
        try {
            // Only root wrappers (backed by a YamlConfiguration) can save.
            // Nested-section wrappers are read/write views used by the
            // merger; the root owns the file. If anything tries to call
            // save on a nested view, fail loudly so we notice in
            // development instead of silently dropping the merge result.
            if (!(delegate instanceof YamlConfiguration)) {
                throw new UnsupportedOperationException(
                        "BukkitCommonYaml.save called on a nested-section wrapper; "
                                + "save on the root wrapper instead");
            }
            ((YamlConfiguration) delegate).save(file);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save YAML to " + file, e);
        }
    }

    @Override
    public CommonYaml getChild(String path) {
        ConfigurationSection section = delegate.getConfigurationSection(path);
        if (section == null) {
            return null;
        }
        return new BukkitCommonYaml(section);
    }
}
