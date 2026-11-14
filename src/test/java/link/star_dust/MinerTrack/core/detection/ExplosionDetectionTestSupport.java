package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CoreConfig;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class InMemoryDetectionBridge implements DetectionBridge {
    final Map<CommonLocation, String> blocks = new HashMap<>();
    final Set<CommonLocation> liveAir = new HashSet<>();
    final Set<CommonLocation> manualSingletons = new HashSet<>();
    final Set<CommonLocation> playerPlaced = new HashSet<>();
    final Set<CommonLocation> artificialAir = new HashSet<>();
    final Map<String, Object> worldValues = new HashMap<>();
    boolean worldDetectionEnabled = true;
    boolean explosionDetectionEnabled = true;
    boolean bypass;

    @Override public String getBlockType(String world, int x, int y, int z) {
        CommonLocation location = new CommonLocation(world, x, y, z);
        if (manualSingletons.contains(location)) return BlockId.DIAMOND_ORE;
        return liveAir.contains(location) ? BlockId.AIR : blocks.getOrDefault(location, "minecraft:stone");
    }
    @Override public boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location) { return playerPlaced.contains(location); }
    @Override public Object getConfig(String path) { return value(path); }
    @Override public int getConfigInt(String path, int def) { return number(value(path), def); }
    @Override public boolean getConfigBoolean(String path, boolean def) { return bool(value(path), def); }
    @Override public double getConfigDouble(String path, double def) { return number(value(path), def); }
    @Override public List<String> getConfigStringList(String path) { return List.of(); }
    @Override public int getConfigForWorld(String world, String path, int def) { return number(worldValues.getOrDefault(path, value(path)), def); }
    @Override public boolean getConfigForWorldBoolean(String world, String path, boolean def) {
        if (path.equals("xray.explosion-detection.enable")) return explosionDetectionEnabled;
        return bool(worldValues.getOrDefault(path, value(path)), def);
    }
    @Override public List<String> getConfigForWorldStringList(String world, String path) { return List.of(BlockId.DIAMOND_ORE); }
    @Override public boolean isWorldDetectionEnabled(String world) { return worldDetectionEnabled; }
    @Override public int getWorldMaxHeight(String world) { return -1; }
    @Override public List<String> getRareOres(String world) { return List.of(BlockId.DIAMOND_ORE); }
    @Override public int getTraceRemoveTime(String world) { return 15; }
    @Override public int getArtificialAirRemoveTime(String world) { return 20; }
    @Override public boolean isArtificialAir(UUID playerId, CommonLocation location) { return artificialAir.contains(location); }
    @Override public boolean isWaterStill(String world, int x, int y, int z) { return true; }
    @Override public void trackPlacedBlock(UUID playerId, CommonLocation location) { playerPlaced.add(location); }
    @Override public void trackBrokenAir(UUID playerId, CommonLocation location) { artificialAir.add(location); }
    @Override public void clearPlayerTracking(UUID playerId) { artificialAir.clear(); playerPlaced.clear(); }
    @Override public void clearConfigCache() {}
    @Override public CoreConfig loadGroupConfigs() { return new CoreConfig(); }
    @Override public CoreConfig getCoreConfig() { return new CoreConfig(); }
    @Override public boolean hasPermission(UUID playerId, String node) { return bypass; }

    private Object value(String path) {
        return switch (path) {
            case "disable_bypass_permission" -> false;
            case "xray.natural-detection.enable" -> true;
            case "xray.natural-detection.cave.ignore-artificial-air" -> true;
            case "xray.natural-detection.cave.air-threshold" -> 14;
            case "xray.natural-detection.cave.CaveAirMultiplier" -> 5;
            case "xray.natural-detection.cave.detection-range" -> 3;
            case "xray.natural-detection.cave.check_skip_vl" -> true;
            case "xray.natural-detection.sea.water-threshold", "xray.natural-detection.lava-sea.lava-threshold" -> 14;
            case "xray.natural-detection.sea.check-running-water" -> false;
            case "xray.natural-detection.sea.check_skip_vl", "xray.natural-detection.lava-sea.check_skip_vl" -> true;
            case "xray.natural-detection.sea.detection-range", "xray.natural-detection.lava-sea.detection-range" -> 3;
            case "xray.path-detection.turn-count-threshold" -> 10;
            case "xray.path-detection.branch-count-threshold" -> 6;
            case "xray.path-detection.y-change-threshold" -> 4;
            case "xray.path-detection.y-change-threshold-add-required" -> 3;
            case "xray.max_vein_distance" -> 5;
            case "xray.veinCountThreshold" -> 2;
            case "xray.small_vein_detection_size" -> 4;
            case "xray.max_path_length" -> 500;
            default -> null;
        };
    }
    private static int number(Object value, int def) { return value instanceof Number n ? n.intValue() : def; }
    private static double number(Object value, double def) { return value instanceof Number n ? n.doubleValue() : def; }
    private static boolean bool(Object value, boolean def) { return value instanceof Boolean b ? b : def; }
}

final class InMemoryViolationManager implements ViolationManagerBridge {
    private final Map<UUID, Integer> levels = new HashMap<>();
    final List<ExplosionDetectionTest.ViolationRecord> records = new ArrayList<>();
    @Override public int getViolationLevel(UUID playerId) { return levels.getOrDefault(playerId, 0); }
    @Override public void increaseViolationLevel(UUID playerId, String playerName, int increment, String blockType,
                                                 int count, int vein, CommonLocation location) {
        levels.merge(playerId, increment, Integer::sum);
        records.add(new ExplosionDetectionTest.ViolationRecord(playerId, playerName, increment, blockType, count, vein, location));
    }
    @Override public void processVLDecay() {}
    @Override public void scheduleVLDecayTask(UUID playerId) {}
    @Override public void cancelVLDecayTask(UUID playerId) {}
    @Override public void cancelAllVLDecayTasks() {}
    @Override public void cancelGlobalDecayTask() {}
    @Override public boolean isLogFileEnabled() { return false; }
    @Override public String getLogFormat() { return ""; }
    @Override public void appendLogLine(String line) {}
    @Override public void runConsoleCommand(String command) {}
    @Override public Set<UUID> getVerbosePlayers() { return Set.of(); }
    @Override public boolean isVerboseConsoleEnabled() { return false; }
    @Override public void sendMessageToPlayer(UUID playerId, String message) {}
    @Override public boolean hasPermission(UUID playerId, String node) { return false; }
    @Override public Object getConfigSection(String path) { return null; }
    @Override public Object getConfig(String path) { return null; }
    @Override public String getPrefixedMessage(String key) { return key; }
    @Override public File getDataFolder() { return new File("."); }
    @Override public void resetViolation(UUID playerId) { levels.remove(playerId); }
    @Override public void clearPlayerState(UUID playerId) { levels.remove(playerId); }
    @Override public void appendCommandLog(String command) {}
    @Override public String getPlayerName(UUID playerId) { return "miner"; }
    @Override public int getConfigInt(String path, int def) { return def; }
    @Override public boolean getConfigBoolean(String path, boolean def) { return def; }
    @Override public double getConfigDouble(String path, double def) { return def; }
}
