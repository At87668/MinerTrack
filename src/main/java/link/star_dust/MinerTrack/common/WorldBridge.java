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

    /**
     * Resolve a platform-specific world identifier (e.g. a Bukkit world
     * folder name like {@code world_nether}) to its canonical Minecraft
     * dimension id (e.g. {@code minecraft:the_nether}). The returned
     * value is suitable for matching against
     * {@link DimensionId#OVERWORLD}, {@link DimensionId#THE_NETHER},
     * {@link DimensionId#THE_END} and against the
     * {@code xray.worlds} entries in the main config.
     */
    default String resolveDimensionId(String worldName) {
        return DimensionId.normalize(worldName);
    }
}