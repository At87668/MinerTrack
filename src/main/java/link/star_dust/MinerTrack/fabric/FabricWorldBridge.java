package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.WorldBridge;

public class FabricWorldBridge implements WorldBridge {
    private final Object world; // World - Fabric API not available in Bukkit build

    public FabricWorldBridge(Object world) {
        this.world = world;
    }

    @Override
    public Object getBlockAt(int x, int y, int z) {
        return null; // TODO: implement via Fabric API
    }

    @Override
    public Object getBlockType(int x, int y, int z) {
        return null; // TODO: implement via Fabric API
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return false; // TODO: implement
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        return false; // TODO: implement
    }

    @Override
    public boolean isLava(int x, int y, int z) {
        return false; // TODO: implement
    }

    @Override
    public int getMaxHeight() {
        return 256; // TODO: implement
    }

    @Override
    public Object getWorld(String worldName) {
        return null; // TODO: implement via Fabric API
    }

    @Override
    public String getWorldName() {
        return "unknown"; // TODO: implement
    }
}