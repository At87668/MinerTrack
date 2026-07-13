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

/** Fabric YAML loader. SnakeYAML-backed, returns FabricCommonYaml. */
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
