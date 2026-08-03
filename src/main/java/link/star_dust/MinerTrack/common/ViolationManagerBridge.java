/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 *
 *  Copyright (c) At87668 (Author87668) <https://github.com/At87668>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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

    /**
     * Set whether console verbose output is enabled. The console verbose
     * state is shared across command invocations (a fresh CommandBridge is
     * created per command), so it must be persisted here rather than on the
     * per-invocation bridge. Default no-op for platforms that don't support
     * console verbose toggling.
     */
    default void setVerboseConsoleEnabled(boolean enabled) {}

    void sendMessageToPlayer(UUID playerId, String message);

    /**
     * Check whether a player has a specific permission node.
     * Used by {@code ViolationEngine} to filter verbose recipients.
     */
    boolean hasPermission(UUID playerId, String node);

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
