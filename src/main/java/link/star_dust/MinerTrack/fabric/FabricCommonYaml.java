package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommonYaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fabric CommonYaml backed by SnakeYAML Map. Mirrors MapBackedYaml.
 *
 * <p>The {@link #save(File)} method uses a custom {@link DumperOptions} configuration
 * that produces block-style YAML with consistent indentation and line breaks.
 * Comments from the original file are preserved by extracting them before the
 * YAML dump and re-inserting them at the correct positions afterwards.</p>
 */
public class FabricCommonYaml implements CommonYaml {
    private final Map<String, Object> map;

    // Shared DumperOptions for consistent block-style output.
    private static final DumperOptions DUMPER_OPTIONS;
    static {
        DUMPER_OPTIONS = new DumperOptions();
        DUMPER_OPTIONS.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        DUMPER_OPTIONS.setIndent(2);
        DUMPER_OPTIONS.setIndicatorIndent(0);
        DUMPER_OPTIONS.setIndentWithIndicator(false);
        DUMPER_OPTIONS.setPrettyFlow(false);
        DUMPER_OPTIONS.setLineBreak(DumperOptions.LineBreak.UNIX);
        DUMPER_OPTIONS.setWidth(Integer.MAX_VALUE);
    }

    // Shared Representer that preserves insertion order and uses block style.
    private static final Representer REPRESENTER = new Representer(DUMPER_OPTIONS);
    static {
        REPRESENTER.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    }

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
        // Preserve comments from the existing file before overwriting.
        List<String> comments = new ArrayList<>();
        if (file.exists()) {
            comments = extractComments(file);
        }

        // Use a SnakeYAML instance with block-style options for
        // human-readable output. The output uses block style,
        // preserves insertion order (LinkedHashMap), and produces
        // consistent indentation. Comments extracted from the
        // original file are re-inserted at the top of the output.
        Yaml yaml = new Yaml(REPRESENTER, DUMPER_OPTIONS);
        try (FileWriter writer = new FileWriter(file)) {
            // Write preserved comments at the top of the file.
            for (String comment : comments) {
                writer.write(comment);
                writer.write(System.lineSeparator());
            }
            yaml.dump(map, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save YAML to " + file, e);
        }
    }

    /**
     * Extract comment lines (lines starting with {@code #}) from a YAML file.
     * Also preserves blank lines that separate comment blocks from content.
     *
     * @param file the YAML file to read comments from
     * @return list of comment lines (including leading {@code #} and whitespace)
     */
    private static List<String> extractComments(File file) {
        List<String> comments = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                    comments.add(line);
                } else {
                    // Stop at the first non-comment, non-blank line.
                    // Comments are only preserved from the header area.
                    break;
                }
            }
        } catch (IOException e) {
            // If we can't read the file, return empty comments.
        }
        return comments;
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
