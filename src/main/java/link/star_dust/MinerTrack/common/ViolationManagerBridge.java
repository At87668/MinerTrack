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

    /**
     * Translate the world identifier stored on {@code CommonLocation}
     * into the display string used in the on-screen
     * {@code World: %world%} field of the X-Ray log and the
     * webhook payload. The core layer keys everything by a
     * platform-internal world id (a Bukkit folder name on the
     * Bukkit path) — this method gives the platform a chance to
     * expose a more user-friendly id (a vanilla canonical id
     * like {@code minecraft:overworld}, or a namespaced
     * {@code minecraft:<folder>} for non-vanilla worlds) for log
     * output.
     *
     * <p>The default implementation returns {@code worldKey}
     * unchanged, which is the right behaviour for platforms
     * (Fabric) where the world identifier IS already the
     * canonical id. The Bukkit platform overrides this to apply
     * the layered fallback rules from
     * {@code BukkitDetectionBridge.getDisplayDimensionId}.
     */
    default String getDisplayWorldName(String worldKey) {
        return worldKey;
    }
}
