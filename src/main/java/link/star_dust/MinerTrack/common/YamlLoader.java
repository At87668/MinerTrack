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
