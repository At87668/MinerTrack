package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.YamlLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collections;
import java.util.Map;

/**
 * Fabric YAML loader.
 *
 * <p>SnakeYAML is bundled at compile time (transitive of the
 * Bukkit / Paper classpath via the {@code org.yaml.snakeyaml} package
 * the project already depends on) and is therefore available at
 * runtime on the Fabric server too — SnakeYAML is a transitive
 * dependency of every Fabric YAML utility, and even when it isn't
 * present, the Fabric server's own classpath provides it. We
 * therefore don't need to shadow it into the JAR for the Fabric
 * build.
 *
 * <p>The loader reads a YAML stream into a {@code Map} and hands it
 * off to {@link FabricCommonYaml}, which exposes the same
 * {@code CommonYaml} surface every other platform uses. The
 * {@code Map}-backed representation is correct for the platform
 * merger path (the only mutating code path is the merger's own
 * {@code set} calls, which {@code MapBackedYaml} handles).
 */
public class FabricYamlLoader implements YamlLoader {

    @Override
    public CommonYaml loadFile(File file) {
        Yaml yaml = new Yaml();
        try (FileInputStream in = new FileInputStream(file)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = yaml.load(in);
            if (map == null) {
                // Empty file → return an empty config so the
                // merger's "is key present?" checks return false
                // and the defaults get copied in.
                map = new java.util.LinkedHashMap<>();
            }
            return new FabricCommonYaml(map);
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
            if (map == null) {
                map = new java.util.LinkedHashMap<>();
            }
            return new FabricCommonYaml(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML from input stream", e);
        }
    }
}
