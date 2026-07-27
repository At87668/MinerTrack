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

package link.star_dust.MinerTrack.common;

import java.io.File;
import java.io.InputStream;

/**
 * Platform-specific YAML loader.
 *
 * {@link link.star_dust.MinerTrack.core.config.ConfigMerger} only needs to
 * (a) read a user file from disk, (b) read a default file from the plugin
 * JAR, and (c) write the merged result back to disk. Each platform supplies
 * its own implementation, keeping the merger itself free of Bukkit/Fabric
 * dependencies.
 */
public interface YamlLoader {

    /** Load a YAML file from disk into a {@link CommonYaml}. */
    CommonYaml loadFile(File file);

    /** Load a YAML resource from an input stream. */
    CommonYaml loadStream(InputStream input);
}
