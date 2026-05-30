package link.star_dust.MinerTrack.common;

import java.util.List;
import java.util.UUID;

public interface DetectionBridge {
    // Return material name at world,x,y,z (e.g. "DIAMOND_ORE")
    String getBlockType(String world, int x, int y, int z);

    // Check whether a block at given location was player-placed (bridge can consult platform data)
    boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location);

    // Retrieve config values; bridge returns raw objects (Integer, Boolean, Double, List, Map…)
    Object getConfig(String path);
    int getConfigInt(String path, int def);
    boolean getConfigBoolean(String path, boolean def);
    double getConfigDouble(String path, double def);
    List<String> getConfigStringList(String path);

    // World-aware config (delegates to CoreConfig when available)
    int getConfigForWorld(String worldName, String path, int def);
    boolean getConfigForWorldBoolean(String worldName, String path, boolean def);
    List<String> getConfigForWorldStringList(String worldName, String path);
    boolean isWorldDetectionEnabled(String worldName);
    int getWorldMaxHeight(String worldName);
    List<String> getRareOres(String worldName);
    int getTraceRemoveTime(String worldName);
    int getArtificialAirRemoveTime(String worldName);

    // Whether a given location is considered artificially created air for the specified player
    boolean isArtificialAir(UUID playerId, CommonLocation location);

    // Whether water at location is still (Levelled.getLevel() == 0)
    boolean isWaterStill(String world, int x, int y, int z);

    // Block tracking (called from MiningListener)
    void trackPlacedBlock(UUID playerId, CommonLocation location);
    void trackBrokenAir(UUID playerId, CommonLocation location);
    void clearPlayerTracking(UUID playerId);

    // Invalidate cached config so the next access re-reads from disk
    void clearConfigCache();
}
