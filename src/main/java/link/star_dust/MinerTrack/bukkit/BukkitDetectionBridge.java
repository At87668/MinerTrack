package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.PluginAdapter;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Bukkit implementation of DetectionBridge.
 */
public class BukkitDetectionBridge implements DetectionBridge {
    private final PluginAdapter adapter;

    public BukkitDetectionBridge(PluginAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public String getBlockType(String world, int x, int y, int z) {
        try {
            org.bukkit.World w = Bukkit.getWorld(world);
            if (w == null) return "AIR";
            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
            return b.getType().name();
        } catch (Exception e) {
            return "AIR";
        }
    }

    @Override
    public boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location) {
        // Placeholder: without block-placing tracking, assume false
        return false;
    }

    @Override
    public Object getConfig(String path) {
        // Delegate to adapter's data folder config if available
        return null;
    }

    @Override
    public boolean isArtificialAir(UUID playerId, CommonLocation location) {
        return false;
    }

    @Override
    public boolean isWaterStill(String world, int x, int y, int z) {
        try {
            org.bukkit.World w = Bukkit.getWorld(world);
            if (w == null) return false;
            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
            return b.getType() == org.bukkit.Material.WATER;
        } catch (Exception e) {
            return false;
        }
    }
}
