package link.star_dust.MinerTrack.common;

import java.util.UUID;
import java.util.Set;

/**
 * Core violation management interface.
 * Platform implementations provide VL state storage and decay scheduling.
 */
public interface ViolationManagerBridge {
    int getViolationLevel(UUID playerId);
    void increaseViolationLevel(UUID playerId, String playerName, int increment, String blockType, int count, int vein, CommonLocation location);
    void processVLDecay();
    void scheduleVLDecayTask(UUID playerId);
    void cancelVLDecayTask(UUID playerId);
    void cancelAllVLDecayTasks();
    void cancelGlobalDecayTask();
    boolean isLogFileEnabled();
    String getLogFormat();
    void appendLogLine(String line);
    void runConsoleCommand(String command);
    Set<UUID> getVerbosePlayers();
    boolean isVerboseConsoleEnabled();
    void sendMessageToPlayer(UUID playerId, String message);
    boolean isWebHookEnabled();
    int getWebHookVLRequired();
    void sendWebhook(UUID playerId, String oreType, int minedVeins, int oreCount, CommonLocation location);
    Object getConfigSection(String path);
    Object getConfig(String path);
    String getPrefixedMessage(String key);
    java.io.File getDataFolder();

    // --- Additional methods needed by ViolationEngine ---
    void resetViolation(UUID playerId);
    void clearPlayerState(UUID playerId);
    void appendCommandLog(String command);
    String getPlayerName(UUID playerId);

    // Typed config accessors
    int getConfigInt(String path, int def);
    boolean getConfigBoolean(String path, boolean def);
    double getConfigDouble(String path, double def);
}
