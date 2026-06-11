package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommonYaml;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fabric platform implementation of {@link CommonYaml}.
 *
 * <p>Backed by SnakeYAML (a project-wide {@code implementation}
 * dependency), so the on-disk YAML format is identical to what the
 * Bukkit adapter produces. The Fabric platform does not have a
 * ready-made "live section" wrapper (Fabric doesn't ship a Bukkit-style
 * {@code YamlConfiguration}), so this implementation uses a flat
 * {@code LinkedHashMap} for everything. The {@code ConfigMerger} on
 * the Fabric path uses the {@code MapBackedYaml} fallback already
 * implemented in the merger; that path is correct because the only
 * mutating code path on Fabric is the merger's own
 * {@code set(key, value)} calls, which {@code MapBackedYaml} handles
 * fine.
 *
 * <p>Round-tripping YAML through SnakeYAML with the default
 * {@code Representer} / {@code Constructor} preserves the section
 * structure (sub-maps are sub-maps, scalars stay scalars), so the
 * v2 "xray.enable reads as false after the merger runs" bug that
 * hit the Bukkit path doesn't reappear here.
 */
public class FabricCommonYaml implements CommonYaml {
    private final Map<String, Object> map;

    public FabricCommonYaml(Map<String, Object> map) {
        this.map = map == null ? new LinkedHashMap<>() : map;
    }

    @Override
    public Object get(String path) { return deepGet(path); }

    @Override
    public Object get(String path, Object def) {
        Object v = deepGet(path);
        return v == null ? def : v;
    }

    @Override
    public int getInt(String path, int def) {
        Object v = deepGet(path);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        Object v = deepGet(path);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @Override
    public double getDouble(String path, double def) {
        Object v = deepGet(path);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    @Override
    public String getString(String path, String def) {
        Object v = deepGet(path);
        return v == null ? def : v.toString();
    }

    @Override
    public java.util.List<String> getStringList(String path) {
        Object v = deepGet(path);
        if (v instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> raw = (java.util.List<Object>) v;
            java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
            for (Object o : raw) out.add(o == null ? null : o.toString());
            return out;
        }
        return java.util.Collections.emptyList();
    }

    @Override
    public boolean contains(String path) {
        return deepGet(path) != null;
    }

    @Override
    public java.util.Set<String> getKeys(boolean deep) {
        return map.keySet();
    }

    @Override
    public void set(String path, Object value) {
        String[] parts = path.split("\\.");
        java.util.Map<String, Object> cur = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof java.util.Map)) {
                LinkedHashMap<String, Object> created = new LinkedHashMap<>();
                cur.put(parts[i], created);
                cur = created;
            } else {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> nm = (java.util.Map<String, Object>) next;
                cur = nm;
            }
        }
        cur.put(parts[parts.length - 1], value);
    }

    @Override
    public void save(File file) {
        // Use a clean SnakeYAML instance with default options for
        // readability. The output uses block style, preserves
        // insertion order (LinkedHashMap), and round-trips
        // correctly. We deliberately do NOT touch DumperOptions /
        // Representer here: SnakeYAML's defaults produce a layout
        // the v1 / v2 merger round-trips cleanly through loadFile,
        // and tweaking the flow style would risk silently flipping
        // a list into a flow style that's then parsed back as a
        // single string by the next load.
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(map, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save YAML to " + file, e);
        }
    }

    @Override
    public CommonYaml getChild(String path) {
        Object v = get(path);
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) v;
            return new FabricCommonYaml(m);
        }
        return null;
    }

    private Object deepGet(String path) {
        String[] parts = path.split("\\.");
        Object cur = map;
        for (String p : parts) {
            if (!(cur instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) cur;
            cur = m.get(p);
            if (cur == null) return null;
        }
        return cur;
    }
}
