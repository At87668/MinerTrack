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
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import link.star_dust.MinerTrack.MinerTrack;

import java.io.*;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.ArrayList;

public class ConfigManager {
    private final MinerTrack plugin;
    private final File configFile;
    private YamlConfiguration config;

    public ConfigManager(MinerTrack plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        loadConfigFile();
    }

    // Reload the config file (replaces in-memory config)
    public void reloadConfigFile() {
        // Ensure default file exists (do not merge on reload)
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        // Reload plugin config to keep plugin.getConfig() in sync
        try {
            plugin.reloadConfig();
        } catch (Exception ignored) {
        }

        // Load into our local config object without merging defaults
        this.config = YamlConfiguration.loadConfiguration(configFile);
        // Also reload any group configurations
        try {
            loadGroupConfigurations();
        } catch (Exception ignored) {}
    }

    public void loadConfigFile() {
        // Save default config file if it doesn't exist
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        } else {
            // Check config version and update if necessary
            checkAndUpgradeConfig();
        }

        // Load the config file
        this.config = YamlConfiguration.loadConfiguration(configFile);

        // Load defaults from the resource file and merge only missing keys
        try (InputStream defaultStream = plugin.getResource("config.yml")) {
            if (defaultStream != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
                mergeConfigurations(config, defaultConfig, ""); // Recursive merge (unchanged)
                saveConfig(); // Save back merged configuration
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Error loading default configuration: " + e.getMessage());
        }

        // Load per-world/group configuration files from the Configuration/ folder
        loadGroupConfigurations();
    }

    /**
     * Check config version and upgrade if necessary.
     * Backs up current config.yml and replaces with default from JAR if version is outdated.
     */
    private void checkAndUpgradeConfig() {
        try {
            // Load current config version
            YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
            int currentVersion = currentConfig.getInt("_config-version", 0);
            
            // Load default config version from JAR
            try (InputStream defaultStream = plugin.getResource("config.yml")) {
                if (defaultStream != null) {
                    YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
                    int defaultVersion = defaultConfig.getInt("_config-version", 0);
                    
                    // If current version is less than default version, upgrade
                    if (currentVersion < defaultVersion) {
                        plugin.getLogger().info("Config version " + currentVersion + " is outdated. Backing up and updating to version " + defaultVersion);
                        
                        // Backup current config with timestamp
                        String backupFileName = "config-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".yml.bak";
                        File backupFile = new File(plugin.getDataFolder(), backupFileName);
                        
                        try {
                            java.nio.file.Files.copy(configFile.toPath(), backupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            plugin.getLogger().info("Backed up current config to " + backupFile.getAbsolutePath());
                        } catch (IOException e) {
                            plugin.getLogger().warning("Failed to backup config file: " + e.getMessage());
                        }
                        
                        // Replace with default config from JAR
                        plugin.saveResource("config.yml", true);
                        plugin.getLogger().info("Updated config.yml to version " + defaultVersion);
                    }
                } else {
                    plugin.getLogger().warning("Could not load default config.yml from JAR for version check");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking config version: " + e.getMessage());
        }
    }

    /**
     * Public method to reload group configurations at runtime.
     * Call this after adding/removing custom files in the data Configuration/ folder.
     */
    public void reloadGroupConfigurations() {
        loadGroupConfigurations();
    }

    /**
     * Load YAML files from the plugin data folder's Configuration directory.
     * Copies defaults from resources/Configuration if missing.
     */
    private void loadGroupConfigurations() {
        File configDir = new File(plugin.getDataFolder(), "Configuration");
        if (!configDir.exists()) configDir.mkdirs();

        // Ensure files referenced in main config.yml xray.worlds exist; if missing, create from defaults
        if (config != null) {
            ConfigurationSection worldsSection = config.getConfigurationSection("xray.worlds");
            if (worldsSection != null) {
                for (String fileKey : worldsSection.getKeys(false)) {
                    // Normalize fileKey to a group key without extension
                    String groupKey = fileKey;
                    if (groupKey.toLowerCase().endsWith(".yml")) groupKey = groupKey.substring(0, groupKey.length() - 4);
                    String filename = groupKey + ".yml";
                    File out = new File(configDir, filename);
                    if (!out.exists()) {
                        // Try to copy same-name resource first (Configuration/<group>.yml)
                        try {
                            plugin.saveResource("Configuration/" + filename, false);
                            plugin.getLogger().info("Created missing group config from resource: " + filename);
                        } catch (Exception e) {
                            // Fallback: copy overworld.yml resource into this filename
                            try (InputStream is = plugin.getResource("Configuration/overworld.yml")) {
                                if (is != null) {
                                    try (OutputStream os = new FileOutputStream(out)) {
                                        byte[] buf = new byte[8192];
                                        int r;
                                        while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                                    }
                                    plugin.getLogger().info("Created missing group config " + filename + " from default overworld.yml");
                                } else {
                                    plugin.getLogger().warning("Resource overworld.yml not found inside jar; cannot create " + filename);
                                }
                            } catch (IOException ioe) {
                                plugin.getLogger().warning("Failed to create missing group config " + filename + ": " + ioe.getMessage());
                            }
                        }
                    }
                }
            }
        }

        // Load any .yml files present (re-list after possibly creating missing files)
        File[] files = configDir.listFiles((d, name) -> name.toLowerCase().endsWith(".yml"));

        if (files == null) return;

        // Clear existing maps
        this.groupConfigs = new HashMap<>();
        this.worldToGroup = new HashMap<>();
        this.groupWorldPatterns = new HashMap<>();
        this.defaultUnnamedGroupKey = null;

        for (File f : files) {
            try {
                YamlConfiguration yc = YamlConfiguration.loadConfiguration(f);
                String key = f.getName();
                if (key.toLowerCase().endsWith(".yml")) key = key.substring(0, key.length() - 4);
                groupConfigs.put(key, yc);

                // If group file declares a list of worlds, map them
                List<String> worlds = yc.getStringList("worlds");
                if (worlds != null && !worlds.isEmpty()) {
                    for (String w : worlds) {
                        if (w == null || w.isEmpty()) continue;
                        if (w.contains("*") || w.contains("?")) {
                            // convert wildcard to regex
                            String regex = "^" + w.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$";
                            Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                            groupWorldPatterns.putIfAbsent(key, new ArrayList<>());
                            groupWorldPatterns.get(key).add(p);
                        } else {
                            worldToGroup.put(w, key);
                        }
                    }
                } else {
                    // No implicit heuristics: do not auto-map worlds here.
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load group config " + f.getName() + ": " + e.getMessage());
            }
        }

        // Respect mappings declared in main config.yml: xray.worlds: { 'groupname': [worlds...] }
        if (config != null) {
            ConfigurationSection worldsSection = config.getConfigurationSection("xray.worlds");
            if (worldsSection != null) {
                for (String fileKey : worldsSection.getKeys(false)) {
                    try {
                        List<String> list = worldsSection.getStringList(fileKey);
                        if (list == null) continue;
                        // Normalize group key (support keys with or without .yml)
                        String k = fileKey;
                        if (k.toLowerCase().endsWith(".yml")) k = k.substring(0, k.length() - 4);
                        for (String w : list) {
                            if (w == null) continue;
                            if (w.equalsIgnoreCase("all_unnamed_world")) {
                                defaultUnnamedGroupKey = k;
                            } else {
                                // Only map if the referenced group config actually exists
                                if (groupConfigs.containsKey(k)) {
                                    worldToGroup.put(w, k);
                                } else {
                                    plugin.getLogger().warning("xray.worlds references group '" + k + "' but no such group file was loaded; skipping mapping for world '" + w + "'");
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // After loading each group, attempt to merge missing defaults from jar resource of same name
        for (File f : files) {
            try (InputStream defaultStream = plugin.getResource("Configuration/" + f.getName())) {
                if (defaultStream != null) {
                    YamlConfiguration defaultGroup = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
                    String curKey = f.getName();
                    if (curKey.toLowerCase().endsWith(".yml")) curKey = curKey.substring(0, curKey.length() - 4);
                    YamlConfiguration current = groupConfigs.get(curKey);
                    if (current != null) {
                        mergeConfigurations(current, defaultGroup, "");
                        try {
                            current.save(f);
                        } catch (IOException ioe) {
                            plugin.getLogger().warning("Failed to save merged group config " + f.getName() + ": " + ioe.getMessage());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Prune any loaded group configs that are not assigned to any world
        List<String> toUnload = new ArrayList<>();
        for (String groupKey : new ArrayList<>(groupConfigs.keySet())) {
            boolean assigned = false;
            // explicitly assigned via worldToGroup
            for (String mapped : worldToGroup.values()) {
                if (groupKey.equals(mapped)) { assigned = true; break; }
            }
            // assigned via wildcard patterns
            if (!assigned && groupWorldPatterns.containsKey(groupKey) && !groupWorldPatterns.get(groupKey).isEmpty()) assigned = true;
            // assigned as default for unnamed worlds
            if (!assigned && defaultUnnamedGroupKey != null && defaultUnnamedGroupKey.equals(groupKey)) assigned = true;

            if (!assigned) {
                toUnload.add(groupKey);
            }
        }
        for (String ung : toUnload) {
            groupConfigs.remove(ung);
            groupWorldPatterns.remove(ung);
            //plugin.getLogger().info("Unloaded unused group config: " + ung);
        }

        // Clean up worldToGroup entries that now point to missing group keys
        List<String> worldKeys = new ArrayList<>(worldToGroup.keySet());
        for (String w : worldKeys) {
            String mapped = worldToGroup.get(w);
            if (!groupConfigs.containsKey(mapped)) {
                worldToGroup.remove(w);
            }
        }

        // Log mapping relationships for visibility
        try {
            plugin.getLogger().info("Loaded group configurations:");
            for (String key : groupConfigs.keySet()) {
                List<String> exactWorlds = new ArrayList<>();
                for (Map.Entry<String, String> e : worldToGroup.entrySet()) {
                    if (e.getValue().equals(key)) exactWorlds.add(e.getKey());
                }
                List<String> patternStrings = new ArrayList<>();
                List<Pattern> pats = groupWorldPatterns.get(key);
                if (pats != null) {
                    for (Pattern p : pats) patternStrings.add(p.pattern());
                }
                plugin.getLogger().info(" - " + key + ": worlds=" + exactWorlds + ", patterns=" + patternStrings);
            }
            if (defaultUnnamedGroupKey != null) {
                plugin.getLogger().info("Default unnamed group (all_unnamed_world): " + defaultUnnamedGroupKey);
            }
        } catch (Exception ignored) {}
    }

    // Maps group key -> YamlConfiguration
    private Map<String, YamlConfiguration> groupConfigs = new HashMap<>();
    // Maps world name -> group key
    private Map<String, String> worldToGroup = new HashMap<>();
    // Maps group key -> list of patterns for wildcard world matches
    private Map<String, List<Pattern>> groupWorldPatterns = new HashMap<>();
    // Group key to use for worlds not explicitly listed (from config.yml xray.worlds mapping via 'all_unnamed_world')
    private String defaultUnnamedGroupKey = null;

    /**
     * Return the group config applicable for the given world name, or null.
     */
    @Nullable
    private YamlConfiguration getGroupConfigForWorld(String worldName) {
        if (worldName == null) return null;
        if (worldToGroup.containsKey(worldName)) {
            String k = worldToGroup.get(worldName);
            return groupConfigs.get(k);
        }
        // Check patterns (wildcards) first
        for (Map.Entry<String, List<Pattern>> e : groupWorldPatterns.entrySet()) {
            for (Pattern p : e.getValue()) {
                if (p.matcher(worldName).matches()) return groupConfigs.get(e.getKey());
            }
        }

        // Scan groups for explicit "worlds" list (exact matches)
        for (Map.Entry<String, YamlConfiguration> e : groupConfigs.entrySet()) {
            List<String> worlds = e.getValue().getStringList("worlds");
            if (worlds != null && worlds.contains(worldName)) return e.getValue();
        }
        // If main config.yml declares mapping xray.worlds: { 'file.yml': [worlds...] }, respect that mapping
        if (config != null) {
            ConfigurationSection worldsSection = config.getConfigurationSection("xray.worlds");
            if (worldsSection != null) {
                for (String fileKey : worldsSection.getKeys(false)) {
                    try {
                        List<String> list = worldsSection.getStringList(fileKey);
                        if (list == null) continue;
                        String k = fileKey;
                        if (k.toLowerCase().endsWith(".yml")) k = k.substring(0, k.length() - 4);
                        for (String w : list) {
                            if (w == null) continue;
                            if (w.equalsIgnoreCase("all_unnamed_world")) {
                                defaultUnnamedGroupKey = k;
                            } else if (w.equals(worldName)) {
                                // only return if the group file was loaded
                                if (groupConfigs.containsKey(k)) return groupConfigs.get(k);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        // Default for unnamed worlds if configured in config.yml
        if (defaultUnnamedGroupKey != null && groupConfigs.containsKey(defaultUnnamedGroupKey)) return groupConfigs.get(defaultUnnamedGroupKey);

        return null;
    }

    // Typed helpers that prefer group config values when available
    private int getIntForWorld(String worldName, String path, int def) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null && yc.contains(path)) return yc.getInt(path, def);
        return config.getInt(path, def);
    }

    private boolean getBooleanForWorld(String worldName, String path, boolean def) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null && yc.contains(path)) return yc.getBoolean(path, def);
        return config.getBoolean(path, def);
    }

    @SuppressWarnings("unused")
    private List<String> getStringListForWorld(String worldName, String path) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null && yc.contains(path)) return yc.getStringList(path);
        return config.getStringList(path);
    }

    private double getDoubleForWorld(String worldName, String path, double def) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null && yc.contains(path)) return yc.getDouble(path, def);
        return config.getDouble(path, def);
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
        // Use the centralized reload method
        try {
            reloadConfigFile();
        } catch (Exception e) {
            plugin.getLogger().severe("Error reloading configuration: " + e.getMessage());
        }
    }

    // Add your getter methods here, for example:
    public boolean isDenyBypassPermissionEnabled() {
        return config.getBoolean("disable_bypass_permission", false);
    }

    public boolean isKickStrikeLightning() {
        return config.getBoolean("kick_strike_lightning", true);
    }

    public List<String> getRareOres() {
        return config.getStringList("xray.rare-ores");
    }

    public int getVeinCountThreshold() {
        return config.getInt("xray.veinCountThreshold", 3);
    }

    // World-aware getters (prefer group configuration when present)
    public int getVeinCountThreshold(String worldName) {
        return getIntForWorld(worldName, "xray.veinCountThreshold", 3);
    }

    public int getTurnCountThreshold() {
        return config.getInt("xray.path-detection.turn-count-threshold", 10);
    }

    public int getTurnCountThreshold(String worldName) {
        return getIntForWorld(worldName, "xray.path-detection.turn-count-threshold", 10);
    }
    
    public int getBranchCountThreshold() {
        return config.getInt("xray.path-detection.branch-count-threshold", 6);
    }
    
    public int getBranchCountThreshold(String worldName) {
        return getIntForWorld(worldName, "xray.path-detection.branch-count-threshold", 6);
    }
    
    public int getYChangeThreshold() {
        return config.getInt("xray.path-detection.y-change-threshold", 4);
    }

    public int getYChangeThreshold(String worldName) {
        return getIntForWorld(worldName, "xray.path-detection.y-change-threshold", 4);
    }

    public int getWorldMaxHeight(String worldName) {
        // Prefer group-specific config when available
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null && yc.contains("xray.max-height")) {
            return yc.getInt("xray.max-height", -1);
        }
        ConfigurationSection xraySection = config.getConfigurationSection("xray.worlds");
        if (xraySection == null || !xraySection.isConfigurationSection(worldName)) {
            return config.getInt("xray.worlds.all_unnamed_world.max-height", -1);
        }
        return xraySection.getInt(worldName + ".max-height", -1);
    }

    public boolean isWorldDetectionEnabled(String worldName) {
        YamlConfiguration yc = getGroupConfigForWorld(worldName);
        if (yc != null && yc.contains("xray.enable")) {
            return yc.getBoolean("xray.enable", false);
        }
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

    public int getMaxVeinDistance(String worldName) {
        return getIntForWorld(worldName, "xray.max_vein_distance", 5);
    }

    public int getSmallVeinSize() {
        return config.getInt("xray.small_vein_detection_size", 4);
    }
    
    public int getSmallVeinSize(String worldName) {
        return getIntForWorld(worldName, "xray.small_vein_detection_size", 4);
    }
    
    public boolean getNaturalEnable() {
        return config.getBoolean("xray.natural-detection.enable", true);
    }

    public boolean getNaturalEnable(String worldName) {
        return getBooleanForWorld(worldName, "xray.natural-detection.enable", true);
    }
    
    public int getCaveBypassAirThreshold() {
        return config.getInt("xray.natural-detection.cave.air-threshold", 14);
    }

    public int getCaveBypassAirThreshold(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.cave.air-threshold", 14);
    }

    public int getCaveAirMultiplier() {
        return config.getInt("xray.natural-detection.cave.CaveAirMultiplier", 5);
    }

    public int getCaveAirMultiplier(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.cave.CaveAirMultiplier", 5);
    }

    public int getCaveDetectionRange() {
        return config.getInt("xray.natural-detection.cave.detection-range", 3);
    }

    public int getCaveDetectionRange(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.cave.detection-range", 3);
    }

    public boolean isCaveSkipVL() {
        return config.getBoolean("xray.natural-detection.cave.check_skip_vl", true);
    }

    public boolean isCaveSkipVL(String worldName) {
        return getBooleanForWorld(worldName, "xray.natural-detection.cave.check_skip_vl", true);
    }

    public boolean isRunningWaterCheckEnabled() {
        return config.getBoolean("xray.natural-detection.sea.check-running-water", false);
    }

    public boolean isRunningWaterCheckEnabled(String worldName) {
        return getBooleanForWorld(worldName, "xray.natural-detection.sea.check-running-water", false);
    }

    public int getWaterThreshold() {
        return config.getInt("xray.natural-detection.sea.water-threshold", 14);
    }

    public int getWaterThreshold(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.sea.water-threshold", 14);
    }

    public int getWaterDetectionRange() {
        return config.getInt("xray.natural-detection.sea.detection-range", 3);
    }

    public int getWaterDetectionRange(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.sea.detection-range", 3);
    }

    public boolean isSeaSkipVL() {
        return config.getBoolean("xray.natural-detection.sea.check_skip_vl", true);
    }

    public boolean isSeaSkipVL(String worldName) {
        return getBooleanForWorld(worldName, "xray.natural-detection.sea.check_skip_vl", true);
    }

    public int getLavaThreshold() {
        return config.getInt("xray.natural-detection.lava-sea.lava-threshold", 14);
    }

    public int getLavaThreshold(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.lava-sea.lava-threshold", 14);
    }

    public int getLavaDetectionRange() {
        return config.getInt("xray.natural-detection.lava-sea.detection-range", 3);
    }

    public int getLavaDetectionRange(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.lava-sea.detection-range", 3);
    }

    public boolean isLavaSeaSkipVL() {
        return config.getBoolean("xray.natural-detection.lava-sea.check_skip_vl", true);
    }

    public boolean isLavaSeaSkipVL(String worldName) {
        return getBooleanForWorld(worldName, "xray.natural-detection.lava-sea.check_skip_vl", true);
    }

    public int getTraceRemoveTime(String worldName) {
        return getIntForWorld(worldName, "xray.trace_remove", 15);
    }

    public int traceBackLength() {
        return config.getInt("xray.trace_back_length", 10);
    }

    public int traceBackLength(String worldName) {
        return getIntForWorld(worldName, "xray.trace_back_length", 10);
    }

    public int getMaxPathLength(String worldName) {
        return getIntForWorld(worldName, "xray.max_path_length", 500);
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

    public int getYPosChangeThresholdAddRequired(String worldName) {
        return getIntForWorld(worldName, "xray.path-detection.y-change-threshold-add-required", 3);
    }

	public int AirMonitorVLT() {
		return getIntForWorld(null, "xray.natural-detection.cave.air-monitor.violation-threshold", 5);
	}

    public boolean isAirMonitorEnabled(String worldName) {
        return getBooleanForWorld(worldName, "xray.natural-detection.cave.air-monitor.enable", true);
    }

    public int getAirMonitorMinPathLength(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.cave.air-monitor.min-path-length", 10);
    }

    public double getAirMonitorAirRatioThreshold(String worldName) {
        return getDoubleForWorld(worldName, "xray.natural-detection.cave.air-monitor.air-ratio-threshold", 0.3);
    }

    public int getAirMonitorViolationIncrease(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.cave.air-monitor.violation-increase", 1);
    }

    public int getAirMonitorViolationThreshold(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.cave.air-monitor.violation-threshold", 5);
    }

    public int getAirMonitorRemoveTime(String worldName) {
        return getIntForWorld(worldName, "xray.natural-detection.cave.air-monitor.remove-time", 20);
    }
}



