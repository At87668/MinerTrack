package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.YamlLoader;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Bukkit YAML loader.
 *
 * <p>Wraps {@link YamlConfiguration} (and {@code YamlConfiguration#loadConfiguration})
 * so the rest of the codebase can stay platform-agnostic.
 */
public class BukkitYamlLoader implements YamlLoader {

    @Override
    public CommonYaml loadFile(File file) {
        return new BukkitCommonYaml(YamlConfiguration.loadConfiguration(file));
    }

    @Override
    public CommonYaml loadStream(InputStream input) {
        YamlConfiguration yc = new YamlConfiguration();
        try {
            yc.load(new InputStreamReader(input));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML from input stream", e);
        }
        return new BukkitCommonYaml(yc);
    }
}
