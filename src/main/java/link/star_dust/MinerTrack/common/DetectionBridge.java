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

import java.util.List;
import java.util.UUID;

public interface DetectionBridge {
    /**
     * Return the canonical Minecraft block identifier at {@code (world,x,y,z)}.
     * The returned value is always normalised to the {@code minecraft:<path>}
     * form (e.g. {@code minecraft:diamond_ore}, {@code minecraft:water},
     * {@code minecraft:air}), regardless of the underlying platform.
     */
    String getBlockType(String world, int x, int y, int z);

    // Check whether a block at given location was player-placed (bridge can consult platform data)
    boolean isPlayerPlacedBlock(UUID playerId, CommonLocation location);

    // Retrieve config values; bridge returns raw objects (Integer, Boolean, Double, List, Map…)
    Object getConfig(String path);
    int getConfigInt(String path, int def);
    boolean getConfigBoolean(String path, boolean def);
    double getConfigDouble(String path, double def);
    List<String> getConfigStringList(String path);

    // World-aware config (delegates to CoreConfig when available)
    int getConfigForWorld(String worldName, String path, int def);
    boolean getConfigForWorldBoolean(String worldName, String path, boolean def);
    List<String> getConfigForWorldStringList(String worldName, String path);
    boolean isWorldDetectionEnabled(String worldName);
    int getWorldMaxHeight(String worldName);
    List<String> getRareOres(String worldName);
    int getTraceRemoveTime(String worldName);
    int getArtificialAirRemoveTime(String worldName);

    // Whether a given location is considered artificially created air for the specified player
    boolean isArtificialAir(UUID playerId, CommonLocation location);

    // Whether water at location is still (Levelled.getLevel() == 0)
    boolean isWaterStill(String world, int x, int y, int z);

    // Block tracking (called from MiningListener)
    void trackPlacedBlock(UUID playerId, CommonLocation location);
    void trackBrokenAir(UUID playerId, CommonLocation location);
    void clearPlayerTracking(UUID playerId);

    /**
     * Drop ALL mining-path state the detection engine holds for
     * {@code playerId}: the per-world path list, the parallel
     * air-exposure list, the {@code lastMiningTime} / vein-count /
     * last-vein-location / cluster / type maps and the
     * {@code vlZeroTimestamp} map. Called from the platform's
     * {@code /mt reset <player>} handler (via
     * {@code ViolationManagerBridge.clearPlayerState}) so the
     * admin reset also clears the path-based detection state,
     * not just the violation counter — otherwise a player with
     * a long accumulated path can keep ticking up VL on the
     * next ore break even though the counter was just zeroed.
     *
     * <p>The default implementation is a no-op so platforms
     * without a path-tracking engine (e.g. a pure-vl Fabric
     * build) keep compiling; the Bukkit implementation in
     * {@code BukkitDetectionBridge} forwards to the
     * {@code MiningState.clearPlayerPath} helper.
     */
    default void clearPlayerPath(UUID playerId) {
        // No-op default; override in platform-specific bridges
        // that own a MiningState / path-tracking structure.
    }

    // Invalidate cached config so the next access re-reads from disk
    void clearConfigCache();

    /**
     * Reload and merge all group configuration files under Configuration/.
     * Returns a {@link CoreConfig} populated with the latest group mappings.
     * Called on every config reload so group configs stay up-to-date.
     */
    CoreConfig loadGroupConfigs();

    /** Direct access to the current CoreConfig instance. */
    CoreConfig getCoreConfig();

    /**
     * Resolve a platform-specific world identifier (e.g. a Bukkit world
     * folder name like {@code world_nether}) to its canonical Minecraft
     * dimension id ({@code minecraft:the_nether}).
     *
     * <p>The {@code core/} layer uses this id as the lookup key for
     * world-aware config (rare ores, max-height, etc.) so the same config
     * works on every platform: in Bukkit the bridge maps
     * {@code World.Environment → minecraft:xxx}; in Fabric the world
     * already is identified by that id and the bridge is a no-op.
     *
     * <p>The default implementation normalises the input via
     * {@link DimensionId#normalize(String)}; platform bridges should
     * override it to consult their authoritative source
     * (e.g. {@code Bukkit.getWorld(folder).getEnvironment()}).
     */
    default String resolveDimensionId(String worldName) {
        return DimensionId.normalize(worldName);
    }

    /**
     * Check a platform-specific permission for a player.
     *
     * <p>On Bukkit the bridge delegates to {@code Player.hasPermission(node)};
     * on Fabric it checks the fabric-permissions-api path first and falls back
     * to the vanilla op-level check.
     *
     * <p>The default implementation returns {@code false} so that platforms
     * that do not yet support permission checks (e.g. a pure-vl Fabric build)
     * keep compiling without breaking the interface contract.
     *
     * @param playerId the UUID of the player to check
     * @param node     the permission node (e.g. {@code "minertrack.bypass"})
     * @return {@code true} if the player has the requested permission
     */
    default boolean hasPermission(UUID playerId, String node) {
        return false;
    }
}
