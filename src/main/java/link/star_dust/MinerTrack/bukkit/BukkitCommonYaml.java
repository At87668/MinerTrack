package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonYaml;
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
 */
public class BukkitCommonYaml implements CommonYaml {

    private final YamlConfiguration delegate;

    public BukkitCommonYaml(YamlConfiguration delegate) {
        this.delegate = delegate;
    }

    /** Constructor for an empty in-memory config. */
    public BukkitCommonYaml() {
        this(new YamlConfiguration());
    }

    /** Underlying Bukkit instance (use only from the bukkit package). */
    public YamlConfiguration getDelegate() {
        return delegate;
    }

    @Override
    public Object get(String path) {
        Object v = delegate.get(path);
        // Bukkit's YamlConfiguration#get(String) returns a
        // MemorySection (which implements ConfigurationSection but
        // NOT java.util.Map) for nested section values. The core
        // config layer relies on the Map contract (instanceof Map
        // checks in ConfigMerger and GroupConfigLoader) to do
        // recursive merges, so flatten ConfigurationSection values
        // into a plain Map<String, Object> here. This keeps the
        // CommonYaml surface platform-neutral and avoids forcing
        // the core layer to depend on org.bukkit.
        //
        // The conversion is recursive: a section's getValues(false)
        // result still contains ConfigurationSection references for
        // grandchildren, so we walk the returned Map and flatten
        // those too. Without this, the merger would stop descending
        // at the first nested section because Map vs MemorySection
        // checks would fail.
        if (v instanceof org.bukkit.configuration.ConfigurationSection) {
            org.bukkit.configuration.ConfigurationSection cs = (org.bukkit.configuration.ConfigurationSection) v;
            return flattenSection(cs);
        }
        return v;
    }

    /**
     * Recursively flatten a Bukkit {@link
     * org.bukkit.configuration.ConfigurationSection} into a plain
     * {@code LinkedHashMap} whose nested section values are also
     * {@code Map}s (and so on, all the way down). Scalars and lists
     * are kept as-is.
     */
    private static java.util.Map<String, Object> flattenSection(
            org.bukkit.configuration.ConfigurationSection cs) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String key : cs.getKeys(false)) {
            Object child = cs.get(key);
            if (child instanceof org.bukkit.configuration.ConfigurationSection) {
                out.put(key, flattenSection(
                        (org.bukkit.configuration.ConfigurationSection) child));
            } else {
                out.put(key, child);
            }
        }
        return out;
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
            delegate.save(file);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save YAML to " + file, e);
        }
    }
}
