/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 *
 *  Copyright (c) At87668 (Author87668) <https://github.com/At87668>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package link.star_dust.MinerTrack.forge;

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

/**
 * Forge CommonYaml backed by SnakeYAML Map. Mirrors FabricCommonYaml.
 */
public class ForgeCommonYaml implements CommonYaml {
    private final Map<String, Object> map;

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

    private static final Representer REPRESENTER = new Representer(DUMPER_OPTIONS);
    static {
        REPRESENTER.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    }

    public ForgeCommonYaml(Map<String, Object> map) {
        this.map = map == null ? new LinkedHashMap<>() : map;
    }

    @Override public Object get(String path) { return deepGet(path); }

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
    public List<String> getStringList(String path) {
        Object v = deepGet(path);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) v;
            ArrayList<String> out = new ArrayList<>(raw.size());
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

    @Override
    public void save(File file) {
        List<String> comments = new ArrayList<>();
        if (file.exists()) {
            comments = extractComments(file);
        }
        Yaml yaml = new Yaml(REPRESENTER, DUMPER_OPTIONS);
        try (FileWriter writer = new FileWriter(file)) {
            for (String comment : comments) {
                writer.write(comment);
                writer.write(System.lineSeparator());
            }
            yaml.dump(map, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save YAML to " + file, e);
        }
    }

    private static List<String> extractComments(File file) {
        List<String> comments = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                    comments.add(line);
                } else {
                    break;
                }
            }
        } catch (IOException ignored) {}
        return comments;
    }

    private Object deepGet(String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> child = (Map<String, Object>) next;
            cur = child;
        }
        return cur.get(parts[parts.length - 1]);
    }
}
