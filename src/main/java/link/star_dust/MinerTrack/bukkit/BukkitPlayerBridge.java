package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.PlayerBridge;
import org.bukkit.entity.Player;

import java.util.List;

public class BukkitPlayerBridge implements PlayerBridge {
    private final Player player;

    public BukkitPlayerBridge(Player player) {
        this.player = player;
    }

    @Override
    public String getName() {
        return player.getName();
    }

    @Override
    public String getWorld() {
        return player.getWorld().getName();
    }

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }

    @Override
    public void sendMessage(String message) {
        player.sendMessage(message);
    }

    @Override
    public void sendMessage(List<String> messages) {
        for (String msg : messages) {
            player.sendMessage(msg);
        }
    }

    @Override
    public boolean isOnline() {
        return player.isOnline();
    }

    @Override
    public int getViolationLevel() {
        return 0; // Will be connected to ViolationManager
    }

    @Override
    public void setViolationLevel(int level) {
        // Will be connected to ViolationManager
    }

    @Override
    public long getLastMiningTime() {
        return 0; // Will be connected to MiningListener state
    }

    @Override
    public void setLastMiningTime(long time) {
        // Will be connected to MiningListener state
    }

    @Override
    public int getMinedVeinCount() {
        return 0;
    }

    @Override
    public void setMinedVeinCount(int count) {
        // Will be connected to MiningListener state
    }
}