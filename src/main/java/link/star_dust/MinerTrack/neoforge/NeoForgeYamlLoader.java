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

package link.star_dust.MinerTrack.neoforge;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.YamlLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;

/** NeoForge YAML loader. SnakeYAML-backed, returns NeoForgeCommonYaml. */
public class NeoForgeYamlLoader implements YamlLoader {

    @Override
    public CommonYaml loadFile(File file) {
        Yaml yaml = new Yaml();
        try (FileInputStream in = new FileInputStream(file)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = yaml.load(in);
            if (map == null) map = new java.util.LinkedHashMap<>();
            return new NeoForgeCommonYaml(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML from file: " + file, e);
        }
    }

    @Override
    public CommonYaml loadStream(InputStream input) {
        Yaml yaml = new Yaml();
        try (Reader reader = new InputStreamReader(input)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = yaml.load(reader);
            if (map == null) map = new java.util.LinkedHashMap<>();
            return new NeoForgeCommonYaml(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML from input stream", e);
        }
    }
}
