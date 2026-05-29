package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.PlayerBridge;

import java.util.List;

public class FabricPlayerBridge implements PlayerBridge {
    private final Object player; // PlayerEntity - Fabric API not available in Bukkit build

    public FabricPlayerBridge(Object player) {
        this.player = player;
    }

    @Override
    public String getName() {
        return "unknown";
    }

    @Override
    public String getWorld() {
        return "unknown";
    }

    @Override
    public boolean hasPermission(String permission) {
        return true;
    }

    @Override
    public void sendMessage(String message) {
        // TODO: implement via Fabric messaging
    }

    @Override
    public void sendMessage(List<String> messages) {
        for (String msg : messages) {
            sendMessage(msg);
        }
    }

    @Override
    public boolean isOnline() {
        return false;
    }

    @Override
    public int getViolationLevel() {
        return 0;
    }

    @Override
    public void setViolationLevel(int level) {
        // TODO
    }

    @Override
    public long getLastMiningTime() {
        return 0;
    }

    @Override
    public void setLastMiningTime(long time) {
        // TODO
    }

    @Override
    public int getMinedVeinCount() {
        return 0;
    }

    @Override
    public void setMinedVeinCount(int count) {
        // TODO
    }
}