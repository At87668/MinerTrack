package link.star_dust.MinerTrack.common;

/**
 * Platform-agnostic world/block operations.
 */
public interface WorldBridge {
    Object getBlockAt(int x, int y, int z);
    Object getBlockType(int x, int y, int z);
    boolean isAir(int x, int y, int z);
    boolean isWater(int x, int y, int z);
    boolean isLava(int x, int y, int z);
    int getMaxHeight();
    Object getWorld(String worldName);
    String getWorldName();
}