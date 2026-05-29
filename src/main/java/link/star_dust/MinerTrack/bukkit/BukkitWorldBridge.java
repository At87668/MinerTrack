package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.WorldBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

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
        return world.getBlockAt(x, y, z).getType();
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
}