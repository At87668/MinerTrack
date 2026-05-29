package link.star_dust.MinerTrack.common;

import java.util.List;

/**
 * Platform-agnostic player operations.
 */
public interface PlayerBridge {
    String getName();
    String getWorld();
    boolean hasPermission(String permission);
    void sendMessage(String message);
    void sendMessage(List<String> messages);
    boolean isOnline();
    int getViolationLevel();
    void setViolationLevel(int level);
    long getLastMiningTime();
    void setLastMiningTime(long time);
    int getMinedVeinCount();
    void setMinedVeinCount(int count);
}