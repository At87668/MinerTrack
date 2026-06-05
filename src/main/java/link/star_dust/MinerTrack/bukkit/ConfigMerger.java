package link.star_dust.MinerTrack.bukkit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;

/**
 * Mirrors v1 ConfigManager.mergeConfigurations() behavior:
 * - Whitelisted keys are recursively merged (nested sections)
 * - All other missing keys are filled from JAR defaults
 * - Result is saved back to the user's file
 */
public class ConfigMerger {

    /**
     * Keys whose nested structure should be recursively merged rather than replaced.
     * Matches v1's whitelist in ConfigManager.mergeConfigurations().
     */
    private static final Set<String> WHITELIST_KEYS = Set.of(
        "check_update",
        "check_update_channel",
        "kick_strike_lightning",
        "log_file",
        "delete_time",
        "disable_bypass_permission",
        "DiscordWebHook",
        "DiscordWebHook.enable",
        "DiscordWebHook.WebHookURL",
        "DiscordWebHook.vl-required",
        "DiscordWebHook.vl-add-message",
        "DiscordWebHook.vl-add-message.color",
        "DiscordWebHook.vl-add-message.title",
        "DiscordWebHook.vl-add-message.text",
        "xray",
        "xray.enable",
        "xray.worlds",
        "xray.worlds.world",
        "xray.worlds.all_unnamed_world",
        "xray.worlds.all_unnamed_world.enable",
        "xray.rare-ores",
        "xray.max_path_length",
        "xray.trace_remove",
        "xray.max_vein_distance",
        "xray.veinCountThreshold",
        "xray.path-detection",
        "xray.path-detection.turn-count-threshold",
        "xray.path-detection.branch-count-threshold",
        "xray.path-detection.y-change-threshold",
        "xray.path-detection.y-change-threshold-add-required",
        "xray.natural-detection",
        "xray.natural-detection.enable",
        "xray.natural-detection.cave",
        "xray.natural-detection.cave.air-threshold",
        "xray.natural-detection.cave.CaveAirMultiplier",
        "xray.natural-detection.cave.detection-range",
        "xray.natural-detection.cave.check_skip_vl",
        "xray.natural-detection.cave.artificial-air-remove-time",
        "xray.natural-detection.cave.ignore-artificial-air",
        "xray.natural-detection.sea",
        "xray.natural-detection.sea.check-running-water",
        "xray.natural-detection.sea.water-threshold",
        "xray.natural-detection.sea.detection-range",
        "xray.natural-detection.sea.check_skip_vl",
        "xray.natural-detection.lava-sea",
        "xray.natural-detection.lava-sea.lava-threshold",
        "xray.natural-detection.lava-sea.detection-range",
        "xray.natural-detection.lava-sea.check_skip_vl",
        "xray.small_vein_detection_size",
        "xray.decay",
        "xray.decay.interval",
        "xray.decay.amount",
        "xray.decay.use_factor",
        "xray.decay.factor",
        "explosion",
        "explosion.entity-explode-check",
        "explosion.explosion_retention_time",
        "explosion.base_vl_rate",
        "explosion.suspicious_hit_rate",
        "commands"
    );

    /**
     * Load user's config file, merge with JAR defaults (whitelist-based recursive merge),
     * then save the merged result back to disk.
     *
     * @param userFile   the user's config file on disk
     * @param resourcePath the path inside the JAR (e.g. "config.yml")
     * @param adapter    the plugin adapter to access getResource()
     * @return the merged YamlConfiguration
     */
    public static YamlConfiguration loadAndMerge(File userFile, String resourcePath, link.star_dust.MinerTrack.common.PluginAdapter adapter) {        // Create from JAR if the file doesn't exist yet (mirrors v1's saveDefaultConfig / saveResource)
        if (!userFile.exists()) {
            adapter.saveResource(resourcePath, false);
        }
        YamlConfiguration defaultsConfig = new YamlConfiguration();
        try (InputStream defaultStream = adapter.getResource(resourcePath)) {
            if (defaultStream != null) {
                defaultsConfig.load(new InputStreamReader(defaultStream));
            }
        } catch (Exception ignored) {}

        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(userFile);

        mergeConfigurations(userConfig, defaultsConfig, "");

        try {
            userConfig.save(userFile);
        } catch (IOException e) {
            adapter.info("Could not save merged config " + userFile.getName() + ": " + e.getMessage());
        }

        return userConfig;
    }

    /**
     * Recursive whitelist-based merge. Matches v1 behavior exactly.
     */
    private static void mergeConfigurations(ConfigurationSection currentConfig,
                                             ConfigurationSection defaultConfig,
                                             String currentPath) {
        if (currentConfig == null || defaultConfig == null) return;

        for (String key : defaultConfig.getKeys(false)) {
            String fullKeyPath = (currentPath.isEmpty() ? "" : currentPath + ".") + key;
            if (!WHITELIST_KEYS.contains(fullKeyPath)) {
                // Skip keys not in whitelist (v1 behavior: only whitelisted keys are merged recursively)
                // But still ensure missing top-level keys are filled below when absent
            }

            if (currentConfig.contains(key)) {
                Object currentValue = currentConfig.get(key);
                Object defaultValue = defaultConfig.get(key);

                if (currentValue instanceof ConfigurationSection && defaultValue instanceof ConfigurationSection) {
                    // Only recurse if this full path is whitelisted
                    if (WHITELIST_KEYS.contains(fullKeyPath)) {
                        mergeConfigurations(
                            (ConfigurationSection) currentValue,
                            (ConfigurationSection) defaultValue,
                            fullKeyPath
                        );
                    }
                }
                // else: keep user's value
            } else {
                // Add missing key from defaults
                currentConfig.set(key, defaultConfig.get(key));
            }
        }
    }
}
