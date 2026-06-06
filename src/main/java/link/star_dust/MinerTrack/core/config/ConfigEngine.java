package link.star_dust.MinerTrack.core.config;

import link.star_dust.MinerTrack.common.ConfigBridge;
import link.star_dust.MinerTrack.common.CoreConfig;

import java.util.List;

/**
 * Platform-agnostic configuration reader.
 * All typed getters delegate to ConfigBridge (base) and CoreConfig (world-aware).
 */
public class ConfigEngine {
    private final ConfigBridge bridge;
    private final CoreConfig coreConfig;

    public ConfigEngine(ConfigBridge bridge, CoreConfig coreConfig) {
        this.bridge = bridge;
        this.coreConfig = coreConfig;
    }

    // --- Simple getters ---

    public boolean isDenyBypassPermissionEnabled() {
        return bridge.getBoolean("disable_bypass_permission", false);
    }

    public boolean isKickStrikeLightning() {
        return bridge.getBoolean("kick_strike_lightning", true);
    }

    public List<String> getRareOres() {
        // Normalise to canonical minecraft:xxx ids; CoreConfig already does
        // this for the world-aware overload, mirror that here.
        List<String> raw = bridge.getStringList("xray.rare-ores");
        java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
        for (String s : raw) {
            String n = link.star_dust.MinerTrack.common.BlockId.normalize(s);
            out.add(n != null ? n : s);
        }
        return out;
    }

    public List<String> getRareOres(String worldName) {
        return coreConfig.getStringListForWorld(worldName, "xray.rare-ores");
    }

    public int getVeinCountThreshold() {
        return bridge.getInt("xray.veinCountThreshold", 3);
    }

    public int getVeinCountThreshold(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.veinCountThreshold", 3);
    }

    public int getTurnCountThreshold() {
        return bridge.getInt("xray.path-detection.turn-count-threshold", 10);
    }

    public int getTurnCountThreshold(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.path-detection.turn-count-threshold", 10);
    }

    public int getBranchCountThreshold() {
        return bridge.getInt("xray.path-detection.branch-count-threshold", 6);
    }

    public int getBranchCountThreshold(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.path-detection.branch-count-threshold", 6);
    }

    public int getYChangeThreshold() {
        return bridge.getInt("xray.path-detection.y-change-threshold", 4);
    }

    public int getYChangeThreshold(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.path-detection.y-change-threshold", 4);
    }

    public int getYPosChangeThresholdAddRequired() {
        return bridge.getInt("xray.path-detection.y-change-threshold-add-required", 3);
    }

    public int getYPosChangeThresholdAddRequired(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.path-detection.y-change-threshold-add-required", 3);
    }

    public int getMaxVeinDistance() {
        return bridge.getInt("xray.max_vein_distance", 5);
    }

    public int getMaxVeinDistance(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.max_vein_distance", 5);
    }

    public int getSmallVeinSize() {
        return bridge.getInt("xray.small_vein_detection_size", 4);
    }

    public int getSmallVeinSize(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.small_vein_detection_size", 4);
    }

    public boolean getNaturalEnable() {
        return bridge.getBoolean("xray.natural-detection.enable", true);
    }

    public boolean getNaturalEnable(String worldName) {
        return coreConfig.getBooleanForWorld(worldName, "xray.natural-detection.enable", true);
    }

    public int getCaveBypassAirThreshold() {
        return bridge.getInt("xray.natural-detection.cave.air-threshold", 14);
    }

    public int getCaveBypassAirThreshold(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.natural-detection.cave.air-threshold", 14);
    }

    public int getCaveAirMultiplier() {
        return bridge.getInt("xray.natural-detection.cave.CaveAirMultiplier", 5);
    }

    public int getCaveAirMultiplier(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.natural-detection.cave.CaveAirMultiplier", 5);
    }

    public int getCaveDetectionRange() {
        return bridge.getInt("xray.natural-detection.cave.detection-range", 3);
    }

    public int getCaveDetectionRange(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.natural-detection.cave.detection-range", 3);
    }

    public boolean isCaveSkipVL() {
        return bridge.getBoolean("xray.natural-detection.cave.check_skip_vl", true);
    }

    public boolean isCaveSkipVL(String worldName) {
        return coreConfig.getBooleanForWorld(worldName, "xray.natural-detection.cave.check_skip_vl", true);
    }

    public boolean isIgnoreArtificialAir(String worldName) {
        return coreConfig.getBooleanForWorld(worldName, "xray.natural-detection.cave.ignore-artificial-air", true);
    }

    public boolean isRunningWaterCheckEnabled() {
        return bridge.getBoolean("xray.natural-detection.sea.check-running-water", false);
    }

    public boolean isRunningWaterCheckEnabled(String worldName) {
        return coreConfig.getBooleanForWorld(worldName, "xray.natural-detection.sea.check-running-water", false);
    }

    public int getWaterThreshold() {
        return bridge.getInt("xray.natural-detection.sea.water-threshold", 14);
    }

    public int getWaterThreshold(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.natural-detection.sea.water-threshold", 14);
    }

    public int getWaterDetectionRange() {
        return bridge.getInt("xray.natural-detection.sea.detection-range", 3);
    }

    public int getWaterDetectionRange(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.natural-detection.sea.detection-range", 3);
    }

    public boolean isSeaSkipVL() {
        return bridge.getBoolean("xray.natural-detection.sea.check_skip_vl", true);
    }

    public boolean isSeaSkipVL(String worldName) {
        return coreConfig.getBooleanForWorld(worldName, "xray.natural-detection.sea.check_skip_vl", true);
    }

    public int getLavaThreshold() {
        return bridge.getInt("xray.natural-detection.lava-sea.lava-threshold", 14);
    }

    public int getLavaThreshold(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.natural-detection.lava-sea.lava-threshold", 14);
    }

    public int getLavaDetectionRange() {
        return bridge.getInt("xray.natural-detection.lava-sea.detection-range", 3);
    }

    public int getLavaDetectionRange(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.natural-detection.lava-sea.detection-range", 3);
    }

    public boolean isLavaSeaSkipVL() {
        return bridge.getBoolean("xray.natural-detection.lava-sea.check_skip_vl", true);
    }

    public boolean isLavaSeaSkipVL(String worldName) {
        return coreConfig.getBooleanForWorld(worldName, "xray.natural-detection.lava-sea.check_skip_vl", true);
    }

    public int getTraceRemoveTime(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.trace_remove", 15);
    }

    public int traceBackLength() {
        return bridge.getInt("xray.trace_back_length", 10);
    }

    public int traceBackLength(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.trace_back_length", 10);
    }

    public int getMaxPathLength(String worldName) {
        return coreConfig.getIntForWorld(worldName, "xray.max_path_length", 500);
    }

    public boolean updateCheck() {
        return bridge.getBoolean("check_update", true);
    }

    public String updateCheckChannel() {
        return bridge.get("check_update_channel") != null ? bridge.get("check_update_channel").toString() : "stable";
    }

    public int getSuspicionThreshold() {
        return bridge.getInt("xray.mine.suspicionThreshold", 100);
    }

    public String webHookURL() {
        return bridge.get("DiscordWebHook.WebHookURL") != null ? bridge.get("DiscordWebHook.WebHookURL").toString() : null;
    }

    public boolean webHookEnable() {
        return bridge.getBoolean("DiscordWebHook.enable", false);
    }

    public int webHookColor() {
        return bridge.getInt("DiscordWebHook.vl-add-message.color", 0xFF5733);
    }

    public String webHookTitle() {
        return bridge.get("DiscordWebHook.vl-add-message.title") != null ? bridge.get("DiscordWebHook.vl-add-message.title").toString() : null;
    }

    public List<String> webHookText() {
        return bridge.getStringList("DiscordWebHook.vl-add-message.text");
    }

    public int webHookVLRequired() {
        return bridge.getInt("DiscordWebHook.vl-required", 0);
    }

    public boolean isCustomJsonEnabled() {
        return bridge.getBoolean("DiscordWebHook.custom-json.enable", false);
    }

    public String getCustomJsonFormat() {
        return bridge.get("DiscordWebHook.custom-json.format") != null ? bridge.get("DiscordWebHook.custom-json.format").toString() : "";
    }

    public int getArtificialAirRemoveTime(String worldName) {
        int def = bridge.getInt("xray.natural-detection.cave.air-monitor.remove-time", 20);
        return coreConfig.getIntForWorld(worldName, "xray.natural-detection.cave.artificial-air-remove-time", def);
    }

    public String getCommandForThreshold(int threshold) {
        // Commands are platform-specific (e.g. Bukkit dispatchCommand); the
        // violation engine resolves them through the active platform
        // ViolationManagerBridge, not through this config reader. Returning
        // null here is the contract callers expect.
        return null;
    }

    public int getWorldMaxHeight(String worldName) {
        // Look up the per-group `max-height` in the resolved group config;
        // -1 (sentinel) means "no limit" and lets the caller fall back to
        // the world's natural build height.
        return coreConfig.getIntForWorld(worldName, "xray.max-height", -1);
    }

    public boolean isWorldDetectionEnabled(String worldName) {
        // The per-group `xray.enable` flag is the source of truth; the
        // main xray.worlds mapping only assigns worlds → groups, not
        // per-world enable states.
        return coreConfig.getBooleanForWorld(worldName, "xray.enable", false);
    }

    public boolean disableBypass() {
        return bridge.getBoolean("disable_bypass_permission", false);
    }
}