package link.star_dust.MinerTrack.common;

import java.util.UUID;

public interface DetectionBridge {
    // Return material name at world,x,y,z (e.g. "DIAMOND_ORE")
    String getBlockType(String world, int x, int y, int z);

    // Check whether a block at given location was player-placed (bridge can consult platform data)
    boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location);

    // Retrieve config values (plugin-specific); bridge should return raw objects like int/double/Map
    Object getConfig(String path);

    // Whether a given location is considered artificially created air for the specified player
    boolean isArtificialAir(UUID playerId, CommonLocation location);

    // Whether water at location is still (not flowing)
    boolean isWaterStill(String world, int x, int y, int z);
}
