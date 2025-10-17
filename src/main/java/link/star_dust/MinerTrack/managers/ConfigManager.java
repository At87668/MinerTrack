/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/managers/ConfigManager.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack.managers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import link.star_dust.MinerTrack.MinerTrack;

import java.io.*;
import java.util.List;
import java.util.Set;

public class ConfigManager {
    private final MinerTrack plugin;
    private final File configFile;
    private final FileConfiguration config;

    public ConfigManager(MinerTrack plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");

        // Load existing config
        this.config = YamlConfiguration.loadConfiguration(configFile);

        // Load defaults and merge only missing keys
        try (InputStream defaultStream = plugin.getResource("config.yml")) {
            if (defaultStream != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
                mergeConfigurations(config, defaultConfig, ""); // Recursive merge
                saveConfig(); // Save back merged configuration
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Error loading default configuration: " + e.getMessage());
        }
    }

    /**
     * Recursively merges default configuration into the current configuration.
     *
     * @param currentConfig The current configuration to merge into.
     * @param defaultConfig The default configuration to merge from.
     */
    private void mergeConfigurations(ConfigurationSection currentConfig, ConfigurationSection defaultConfig, String currentPath) {
    	Set<String> whitelistKeys = Set.of(
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
    		    "xray.natural-detection.cave.air-monitor",
    		    "xray.natural-detection.cave.air-monitor.enable",
    		    "xray.natural-detection.cave.air-monitor.min-path-length",
    		    "xray.natural-detection.cave.air-monitor.air-ratio-threshold",
    		    "xray.natural-detection.cave.air-monitor.violation-increase",
    		    "xray.natural-detection.cave.air-monitor.violation-threshold",
    		    "xray.natural-detection.cave.air-monitor.remove-time",
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

        for (String key : defaultConfig.getKeys(false)) {
            String fullKeyPath = (currentPath.isEmpty() ? "" : currentPath + ".") + key;
            if (!whitelistKeys.contains(fullKeyPath)) {
                continue; // Skip keys not in the whitelist
            }

            if (currentConfig.contains(key)) {
                Object currentValue = currentConfig.get(key);
                Object defaultValue = defaultConfig.get(key);

                // Recurse for nested sections
                if (currentValue instanceof ConfigurationSection && defaultValue instanceof ConfigurationSection) {
                    mergeConfigurations(
                        (ConfigurationSection) currentValue,
                        (ConfigurationSection) defaultValue,
                        fullKeyPath
                    );
                }
            } else {
                // Add missing key
                currentConfig.set(key, defaultConfig.get(key));
            }
        }
    }


    /**
     * Saves the current configuration back to the file while preserving structure.
     */
    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save configuration to " + configFile.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Reloads the configuration from the file.
     */
    public void reloadConfig() {
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Error reloading configuration: " + e.getMessage());
        }
    }

    // Add your getter methods here, for example:
    public boolean isDenyBypassPermissionEnabled() {
        return config.getBoolean("disable_bypass_permission", false);
    }

    public boolean isKickStrikeLightning() {
        return config.getBoolean("xray.kick-strike-lightning", true);
    }

    public List<String> getRareOres() {
        return config.getStringList("xray.rare-ores");
    }

    public int getVeinCountThreshold() {
        return config.getInt("xray.veinCountThreshold", 3);
    }

    public int getTurnCountThreshold() {
        return config.getInt("xray.path-detection.turn-count-threshold", 10);
    }
    
    public int getBranchCountThreshold() {
        return config.getInt("xray.path-detection.branch-count-threshold", 6);
    }
    
    public int getYChangeThreshold() {
        return config.getInt("xray.path-detection.y-change-threshold", 4);
    }

    public int getWorldMaxHeight(String worldName) {
        ConfigurationSection xraySection = config.getConfigurationSection("xray.worlds");
        if (xraySection == null || !xraySection.isConfigurationSection(worldName)) {
            //plugin.getLogger().warning("Max height configuration for world " + worldName + " not found. Defaulting to no height limit.");
            return config.getInt("xray.worlds.all_unnamed_world.max-height", -1);
        }
        return xraySection.getInt(worldName + ".max-height", -1);
    }

    public boolean isWorldDetectionEnabled(String worldName) {
        ConfigurationSection worldsSection = config.getConfigurationSection("xray.worlds");
        if (worldsSection == null || !worldsSection.isConfigurationSection(worldName)) {
            return config.getBoolean("xray.worlds.all_unnamed_world.enable", false);
        }
        return worldsSection.getBoolean(worldName + ".enable", false);
    }

    public boolean DisableBypass() {
        return config.getBoolean("disable_bypass_permission", false);
    }

    public String getCommandForThreshold(int threshold) {
        ConfigurationSection commandsSection = config.getConfigurationSection("xray.commands");
        if (commandsSection != null && commandsSection.contains(String.valueOf(threshold))) {
            return commandsSection.getString(String.valueOf(threshold));
        }
        return null;
    }

    public int getMaxVeinDistance() {
        return config.getInt("xray.max_vein_distance", 5);
    }

    public int getSmallVeinSize() {
        return config.getInt("xray.small_vein_detection_size", 4);
    }
    
    public boolean getNaturalEnable() {
        return config.getBoolean("xray.natural-detection.enable", true);
    }
    
    public int getCaveBypassAirThreshold() {
        return config.getInt("xray.natural-detection.cave.air-threshold", 14);
    }

    public int getCaveAirMultiplier() {
        return config.getInt("xray.natural-detection.cave.CaveAirMultiplier", 5);
    }

    public int getCaveDetectionRange() {
        return config.getInt("xray.natural-detection.cave.detection-range", 3);
    }

    public boolean isCaveSkipVL() {
        return config.getBoolean("xray.natural-detection.cave.check_skip_vl", true);
    }

    public boolean isRunningWaterCheckEnabled() {
        return config.getBoolean("xray.natural-detection.sea.check-running-water", false);
    }

    public int getWaterThreshold() {
        return config.getInt("xray.natural-detection.sea.water-threshold", 14);
    }

    public int getWaterDetectionRange() {
        return config.getInt("xray.natural-detection.sea.detection-range", 3);
    }

    public boolean isSeaSkipVL() {
        return config.getBoolean("xray.natural-detection.sea.check_skip_vl", true);
    }

    public int getLavaThreshold() {
        return config.getInt("xray.natural-detection.lava-sea.lava-threshold", 14);
    }

    public int getLavaDetectionRange() {
        return config.getInt("xray.natural-detection.lava-sea.detection-range", 3);
    }

    public boolean isLavaSeaSkipVL() {
        return config.getBoolean("xray.natural-detection.lava-sea.check_skip_vl", true);
    }

    public int traceBackLength() {
        return config.getInt("xray.trace_back_length", 10);
    }
    
    public boolean updateCheck() {
        return config.getBoolean("check_update", true);
    }
    
    public String updateCheckChannel() {
    	return config.getString("check_update_channel", "stable");
    }

	public int getSuspicionThreshold() {
		return config.getInt("xray.mine.suspicionThreshold", 100);
	}
	
	public String WebHookURL() {
        return config.getString("DiscordWebHook.WebHookURL");
    }
	
	public boolean WebHookEnable() {
        return config.getBoolean("DiscordWebHook.enable", false);
    }
	
	public int WebHookColor() {
        return config.getInt("DiscordWebHook.vl-add-message.color", 0xFF5733);
    }
	
	public String WebHookTitle() {
        return config.getString("DiscordWebHook.vl-add-message.title");
    }
	
	public List<String> WebHookText() {
        return config.getStringList("DiscordWebHook.vl-add-message.text");
    }
	
	public int WebHookVLRequired() {
        return config.getInt("DiscordWebHook.vl-required");
    }

    public boolean isCustomJsonEnabled() {
        return config.getBoolean("DiscordWebHook.custom-json.enable", false);
    }

    public String getCustomJsonFormat() {
        return config.getString("DiscordWebHook.custom-json.format", "");
    }

	public int getYPosChangeThresholdAddRequired() {
		return config.getInt("xray.path-detection.y-change-threshold-add-required", 3);
	}

	public int AirMonitorVLT() {
		return plugin.getConfig().getInt("xray.natural-detection.cave.air-monitor.violation-threshold", 5);
	}
}



