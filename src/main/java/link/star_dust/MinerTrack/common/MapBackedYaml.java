package link.star_dust.MinerTrack.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link CommonYaml} backed by an in-memory {@code LinkedHashMap}.
 *
 * <p>Used by the v2 config merger as a generic, platform-neutral
 * container for default config values loaded from the JAR (which
 * arrive as a {@code Map} regardless of which YAML library the
 * platform uses). Also used by the merger's recursion on user
 * values that are stored as plain Maps after a legacy
 * {@code set(path, Map)} round-trip.
 *
 * <p>This class is the platform-neutral counterpart to
 * {@code BukkitCommonYaml}: a Bukkit implementation that wraps a
 * live {@code ConfigurationSection} mutates in place, while this
 * implementation mutates the underlying Map directly. The merger
 * uses {@link CommonYaml#getChild(String)} (overridden in
 * {@code BukkitCommonYaml} to return a wrapper around the live
 * nested section) so that mutations during the merge land in the
 * same in-memory tree the runtime reads from.
 *
 * <p>Stored in the {@code common} package (rather than the
 * {@code core/config} package where it was originally a static
 * inner class) so the default implementation of
 * {@link CommonYaml#getChild(String)} can reference it without
 * pulling the platform-agnostic core/config layer into the
 * {@code common} interface module.
 */
public final class MapBackedYaml implements CommonYaml {
    private final Map<String, Object> map;

    public MapBackedYaml(Map<String, Object> map) {
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
            ArrayList<String> out = new ArrayList<>(raw.size());
            for (Object o : raw) out.add(o == null ? null : o.toString());
            return out;
        }
        return Collections.emptyList();
    }
    @Override public boolean contains(String path) { return deepGet(path) != null; }
    @Override public Set<String> getKeys(boolean deep) { return map.keySet(); }
    @Override public void set(String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) {
                LinkedHashMap<String, Object> created = new LinkedHashMap<>();
                cur.put(parts[i], created);
                cur = created;
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> nm = (Map<String, Object>) next;
                cur = nm;
            }
        }
        cur.put(parts[parts.length - 1], value);
    }
    @Override public void save(File file) { /* not used in the merger; the merger saves the parent */ }

    @Override
    public CommonYaml getChild(String path) {
        Object v = get(path);
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new MapBackedYaml(m);
        }
        return null;
    }

    private Object deepGet(String path) {
        String[] parts = path.split("\\.");
        Object cur = map;
        for (String p : parts) {
            if (!(cur instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) cur;
            cur = m.get(p);
            if (cur == null) return null;
        }
        return cur;
    }
}
