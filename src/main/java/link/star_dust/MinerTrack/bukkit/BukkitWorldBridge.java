package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.MaterialMapper;
import link.star_dust.MinerTrack.common.WorldBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Bukkit implementation of WorldBridge.
 *
 * <p>{@link #getBlockType(int, int, int)} returns the canonical Minecraft
 * namespace id ({@code minecraft:diamond_ore}) rather than the Bukkit
 * {@code Material} enum name, so consumers in {@code core/} and
 * {@code common/} can compare against the same string regardless of
 * platform.
 */
public class BukkitWorldBridge implements WorldBridge {
    private final World world;

    public BukkitWorldBridge(World world) {
        this.world = world;
    }

    @Override
    public Object getBlockAt(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    @Override
    public Object getBlockType(int x, int y, int z) {
        return MaterialMapper.bukkitToMinecraft(world.getBlockAt(x, y, z).getType().name());
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isAir();
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        Material mat = world.getBlockAt(x, y, z).getType();
        return mat == Material.WATER;
    }

    @Override
    public boolean isLava(int x, int y, int z) {
        Material mat = world.getBlockAt(x, y, z).getType();
        return mat == Material.LAVA;
    }

    @Override
    public int getMaxHeight() {
        return world.getMaxHeight();
    }

    @Override
    public Object getWorld(String worldName) {
        return Bukkit.getWorld(worldName);
    }

    @Override
    public String getWorldName() {
        return world.getName();
    }

    @Override
    public String resolveDimensionId(String worldName) {
        // Prefer the live World's environment (NORMAL / NETHER / THE_END)
        // because it is the authoritative source for "which Minecraft
        // dimension is this world". Fall back to the static folder-name
        // table in DimensionId when the world is no longer loaded (e.g.
        // on shutdown, or in a cached config path).
        try {
            org.bukkit.World w = Bukkit.getWorld(worldName);
            if (w != null) {
                switch (w.getEnvironment()) {
                    case NETHER:    return link.star_dust.MinerTrack.common.DimensionId.THE_NETHER;
                    case THE_END:   return link.star_dust.MinerTrack.common.DimensionId.THE_END;
                    case NORMAL:
                    default:        return link.star_dust.MinerTrack.common.DimensionId.OVERWORLD;
                }
            }
        } catch (Throwable ignored) {
            // World may not be available (e.g. during shutdown); fall through.
        }
        return link.star_dust.MinerTrack.common.DimensionId.normalize(worldName);
    }
}
