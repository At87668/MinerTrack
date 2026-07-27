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

package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.core.CoreLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core mining state: per-player, per-world tracking of mining paths,
 * vein clusters, placed blocks, and artificial air.
 * Platform implementations inject a DetectionBridge for world-specific config lookups.
 */
public class MiningState {
    private final DetectionBridge bridge;

    // worldName -> (playerUUID -> path)
    private final Map<String, Map<UUID, List<CommonLocation>>> miningPath = new ConcurrentHashMap<>();
    // worldName -> (playerUUID -> air-exposure flags)
    private final Map<String, Map<UUID, List<Boolean>>> airExposurePath = new ConcurrentHashMap<>();
    // playerUUID -> last mining timestamp
    private final Map<UUID, Long> lastMiningTime = new ConcurrentHashMap<>();
    // playerUUID -> mined vein count
    private final Map<UUID, Integer> minedVeinCount = new ConcurrentHashMap<>();
    // playerUUID -> worldName -> last vein location
    private final Map<UUID, Map<String, CommonLocation>> lastVeinLocation = new ConcurrentHashMap<>();
    // playerUUID -> worldName -> set of cluster locations
    private final Map<UUID, Map<String, Set<CommonLocation>>> lastVeinClusters = new ConcurrentHashMap<>();
    // playerUUID -> worldName -> last analyzed block type
    private final Map<UUID, Map<String, String>> lastVeinType = new ConcurrentHashMap<>();
    // playerUUID -> VL=0 timestamp
    private final Map<UUID, Long> vlZeroTimestamp = new ConcurrentHashMap<>();

    public MiningState(DetectionBridge bridge) {
        this.bridge = bridge;
    }

    // ─── Path tracking ───────────────────────────────────────────────────────

    public List<CommonLocation> getPath(String worldName, UUID playerId) {
        Map<UUID, List<CommonLocation>> worldMap = miningPath.get(worldName);
        if (worldMap == null) return Collections.emptyList();
        List<CommonLocation> path = worldMap.get(playerId);
        return path != null ? path : Collections.emptyList();
    }

    public void appendToPath(String worldName, UUID playerId, CommonLocation loc, boolean exposedToAir) {
        miningPath.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(playerId, k -> new ArrayList<>())
            .add(loc);
        airExposurePath.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(playerId, k -> new ArrayList<>())
            .add(exposedToAir);
        lastMiningTime.put(playerId, System.currentTimeMillis());

        int maxLen = bridge.getConfigForWorld(worldName, "xray.max_path_length", 500);
        List<CommonLocation> path = miningPath.get(worldName).get(playerId);
        if (path != null && path.size() > maxLen) {
            path.remove(0);
            List<Boolean> airPath = airExposurePath.get(worldName).get(playerId);
            if (airPath != null && !airPath.isEmpty()) airPath.remove(0);
        }
    }

    public void clearPlayerPath(UUID playerId) {
        for (Map<UUID, List<CommonLocation>> worldMap : miningPath.values()) {
            worldMap.remove(playerId);
        }
        miningPath.entrySet().removeIf(e -> e.getValue().isEmpty());
        for (Map<UUID, List<Boolean>> worldAir : airExposurePath.values()) {
            worldAir.remove(playerId);
        }
        airExposurePath.entrySet().removeIf(e -> e.getValue().isEmpty());
        lastMiningTime.remove(playerId);
        minedVeinCount.remove(playerId);
        lastVeinLocation.remove(playerId);
        lastVeinClusters.remove(playerId);
        lastVeinType.remove(playerId);
        vlZeroTimestamp.remove(playerId);
    }

    /**
     * Return the set of world names that still have any tracked data
     * (mining path, last vein location, etc.) for {@code playerId}.
     * Used by {@link MiningCore#cleanupExpiredPaths()} to determine
     * the longest {@code xray.trace_remove} window across the worlds
     * the player touched.
     */
    public Set<String> getWorldsWithPlayer(UUID playerId) {
        Set<String> worlds = new HashSet<>();
        for (Map.Entry<String, Map<UUID, List<CommonLocation>>> e : miningPath.entrySet()) {
            Map<UUID, List<CommonLocation>> p = e.getValue();
            if (p != null && p.containsKey(playerId)) worlds.add(e.getKey());
        }
        Map<String, CommonLocation> lv = lastVeinLocation.get(playerId);
        if (lv != null) worlds.addAll(lv.keySet());
        Map<String, Set<CommonLocation>> lc = lastVeinClusters.get(playerId);
        if (lc != null) worlds.addAll(lc.keySet());
        return worlds;
    }

    // ─── Vein cluster helpers ───────────────────────────────────────────────

    public boolean isNewVein(UUID playerId, String worldName, CommonLocation location, String oreType) {
        int maxDistance = bridge.getConfigForWorld(worldName, "xray.max_vein_distance", 5);
        int smallVeinThreshold = bridge.getConfigForWorld(worldName, "xray.small_vein_detection_size", 4);

        Set<CommonLocation> currentCluster = new HashSet<>();
        DetectionEngine engine = new DetectionEngine(bridge);
        for (CommonLocation loc : engine.getVeinLocations(location, oreType, maxDistance)) {
            currentCluster.add(loc);
        }

        lastVeinClusters.putIfAbsent(playerId, new ConcurrentHashMap<>());
        Map<String, Set<CommonLocation>> playerClusters = lastVeinClusters.get(playerId);
        Set<CommonLocation> lastCluster = playerClusters.get(worldName);

        lastVeinLocation.putIfAbsent(playerId, new ConcurrentHashMap<>());
        Map<String, CommonLocation> lastLocs = lastVeinLocation.get(playerId);
        CommonLocation lastLocation = lastLocs.get(worldName);

        // Debug: surface the inputs that drive every decision below.
        // (When debug mode is off CoreLogger.debug is a single
        // volatile read, so this does not affect normal gameplay.)
        CoreLogger.debug("  isNewVein: max_vein_distance=" + maxDistance
            + " smallVeinThreshold=" + smallVeinThreshold
            + " currentClusterSize=" + currentCluster.size()
            + " lastClusterSize=" + (lastCluster == null ? 0 : lastCluster.size()));

        if (lastCluster == null || lastCluster.isEmpty()) {
            playerClusters.put(worldName, new HashSet<>(currentCluster));
            lastLocs.put(worldName, location);
            lastVeinType.putIfAbsent(playerId, new ConcurrentHashMap<>());
            lastVeinType.get(playerId).put(worldName, oreType);
            CoreLogger.debug("    -> newVein=true (no previous cluster recorded)");
            return true;
        }

        if (lastLocation != null
                && lastLocation.x == location.x
                && lastLocation.y == location.y
                && lastLocation.z == location.z
                && lastLocation.world.equals(location.world)) {
            CoreLogger.debug("    -> newVein=false (same block as last vein; max_vein_distance=" + maxDistance + " skipped)");
            return false;
        }

        for (CommonLocation l : currentCluster) {
            if (lastCluster.contains(l)) {
                lastCluster.addAll(currentCluster);
                playerClusters.put(worldName, lastCluster);
                CoreLogger.debug("    -> newVein=false (cluster overlaps last vein; max_vein_distance=" + maxDistance + " skipped)");
                return false;
            }
        }

        double minDist = Double.MAX_VALUE;
        for (CommonLocation a : currentCluster) {
            for (CommonLocation b : lastCluster) {
                double d = Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z));
                if (d < minDist) minDist = d;
            }
        }

        if (minDist <= maxDistance) {
            lastCluster.addAll(currentCluster);
            playerClusters.put(worldName, lastCluster);
            CoreLogger.debug("    -> newVein=false (minDist=" + String.format("%.2f", minDist)
                + " <= max_vein_distance=" + maxDistance + "; clusters merged)");
            return false;
        }

        int currentSize = currentCluster.size();
        int lastSize = lastCluster.size();
        if (currentSize > 0 && currentSize <= smallVeinThreshold && lastSize > 0 && lastSize <= smallVeinThreshold) {
            playerClusters.put(worldName, new HashSet<>(currentCluster));
            lastLocs.put(worldName, location);
            lastVeinType.putIfAbsent(playerId, new ConcurrentHashMap<>());
            lastVeinType.get(playerId).put(worldName, oreType);
            CoreLogger.debug("    -> newVein=true (both clusters are small: currentSize=" + currentSize
                + " lastSize=" + lastSize + " <= smallVeinThreshold=" + smallVeinThreshold
                + "; minDist=" + String.format("%.2f", minDist) + " > max_vein_distance=" + maxDistance + ")");
            return true;
        }

        if (minDist > maxDistance) {
            playerClusters.put(worldName, new HashSet<>(currentCluster));
            lastLocs.put(worldName, location);
            lastVeinType.putIfAbsent(playerId, new ConcurrentHashMap<>());
            lastVeinType.get(playerId).put(worldName, oreType);
            CoreLogger.debug("    -> newVein=true (minDist=" + String.format("%.2f", minDist)
                + " > max_vein_distance=" + maxDistance + ")");
            return true;
        }

        CoreLogger.debug("    -> newVein=false (no rule matched; minDist=" + String.format("%.2f", minDist)
            + " max_vein_distance=" + maxDistance + ")");
        return false;
    }

    // ─── Vein count ────────────────────────────────────────────────────────

    public int getMinedVeinCount(UUID playerId) {
        return minedVeinCount.getOrDefault(playerId, 0);
    }

    public void incrementVeinCount(UUID playerId) {
        minedVeinCount.put(playerId, minedVeinCount.getOrDefault(playerId, 0) + 1);
    }

    public void setLastVeinLocation(UUID playerId, String worldName, CommonLocation loc) {
        lastVeinLocation.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
            .put(worldName, loc);
    }

    public CommonLocation getLastVeinLocation(UUID playerId, String worldName) {
        Map<String, CommonLocation> m = lastVeinLocation.get(playerId);
        return m != null ? m.get(worldName) : null;
    }

    // ─── VL=0 timestamp ────────────────────────────────────────────────────

    public void recordVLZero(UUID playerId) {
        vlZeroTimestamp.put(playerId, System.currentTimeMillis());
    }

    public Long getVLZeroTimestamp(UUID playerId) {
        return vlZeroTimestamp.get(playerId);
    }

    public void removeVLZeroTimestamp(UUID playerId) {
        vlZeroTimestamp.remove(playerId);
    }

    /**
     * Snapshot of the players that have an outstanding VL=0 timestamp
     * so the {@link MiningCore} periodic cleanup can ask the
     * {@link link.star_dust.MinerTrack.common.ViolationManagerBridge} for
     * their current VL and decide whether the trace has been quiet long
     * enough to discard. The set is defensive: callers may iterate and
     * remove entries without affecting the live map.
     */
    public Set<UUID> getPlayersWithVLZeroTimestamp() {
        return new HashSet<>(vlZeroTimestamp.keySet());
    }

    // ─── Cleanup helpers ────────────────────────────────────────────────────

    public void cleanupExpiredPaths() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Map<UUID, List<CommonLocation>>> worldEntry : miningPath.entrySet()) {
            String worldName = worldEntry.getKey();
            long traceBackMs = bridge.getConfigForWorld(worldName, "xray.trace_back_length", 10) * 60 * 1000L;
            Map<UUID, List<CommonLocation>> playerMap = worldEntry.getValue();
            for (Map.Entry<UUID, List<CommonLocation>> p : playerMap.entrySet()) {
                UUID playerId = p.getKey();
                long lastTime = lastMiningTime.getOrDefault(playerId, 0L);
                if (lastTime > 0 && now - lastTime > traceBackMs) {
                    clearPlayerPath(playerId);
                }
            }
        }
    }

    public void cleanupExpiredPlacedBlocks() {
        long now = System.currentTimeMillis();
        // placedOres cleanup is handled by DetectionBridge.isPlayerPlacedBlock() with timestamp expiry
    }

    public void cleanupExpiredBrokenAir() {
        // brokenAir cleanup is handled by DetectionBridge.isArtificialAir() with timestamp expiry
    }

    // ─── Air exposure helper ───────────────────────────────────────────────

    public boolean isExposedToAir(CommonLocation location) {
        String world = location.world;
        int x = location.x, y = location.y, z = location.z;
        // Bridge.getBlockType returns canonical minecraft:xxx ids; compare
        // against BlockId.AIR for portability across platforms.
        return !link.star_dust.MinerTrack.common.BlockId.AIR.equals(bridge.getBlockType(world, x, y + 1, z))
            || !link.star_dust.MinerTrack.common.BlockId.AIR.equals(bridge.getBlockType(world, x, y - 1, z))
            || !link.star_dust.MinerTrack.common.BlockId.AIR.equals(bridge.getBlockType(world, x - 1, y, z))
            || !link.star_dust.MinerTrack.common.BlockId.AIR.equals(bridge.getBlockType(world, x + 1, y, z))
            || !link.star_dust.MinerTrack.common.BlockId.AIR.equals(bridge.getBlockType(world, x, y, z - 1))
            || !link.star_dust.MinerTrack.common.BlockId.AIR.equals(bridge.getBlockType(world, x, y, z + 1));
    }

    // ─── Config shortcuts ──────────────────────────────────────────────────

    public int getVeinCountThreshold(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.veinCountThreshold", 3);
    }

    public int getMaxPathLength(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.max_path_length", 500);
    }

    public int getTraceRemoveTime(String worldName) {
        return bridge.getTraceRemoveTime(worldName);
    }

    public int getTraceBackLength(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.trace_back_length", 10);
    }

    public boolean isWorldDetectionEnabled(String worldName) {
        return bridge.isWorldDetectionEnabled(worldName);
    }

    public int getWorldMaxHeight(String worldName) {
        return bridge.getWorldMaxHeight(worldName);
    }

    public boolean isBypassDisabled(String worldName) {
        return bridge.getConfigBoolean("disable_bypass_permission", false);
    }

    public List<String> getRareOres(String worldName) {
        return bridge.getRareOres(worldName);
    }

    public boolean getNaturalEnable(String worldName) {
        return bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.enable", true);
    }

    public int getCaveBypassAirThreshold(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.natural-detection.cave.air-threshold", 14);
    }

    public int getCaveAirMultiplier(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.natural-detection.cave.CaveAirMultiplier", 5);
    }

    public int getCaveDetectionRange(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.natural-detection.cave.detection-range", 3);
    }

    public boolean isCaveSkipVL(String worldName) {
        return bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.cave.check_skip_vl", true);
    }

    public boolean isIgnoreArtificialAir(String worldName) {
        return bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.cave.ignore-artificial-air", true);
    }

    public int getArtificialAirRemoveTime(String worldName) {
        return bridge.getArtificialAirRemoveTime(worldName);
    }

    public boolean isRunningWaterCheckEnabled(String worldName) {
        return bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.sea.check-running-water", false);
    }

    public int getWaterThreshold(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.natural-detection.sea.water-threshold", 14);
    }

    public int getWaterDetectionRange(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.natural-detection.sea.detection-range", 3);
    }

    public boolean isSeaSkipVL(String worldName) {
        return bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.sea.check_skip_vl", true);
    }

    public int getLavaThreshold(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.natural-detection.lava-sea.lava-threshold", 14);
    }

    public int getLavaDetectionRange(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.natural-detection.lava-sea.detection-range", 3);
    }

    public boolean isLavaSeaSkipVL(String worldName) {
        return bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.lava-sea.check_skip_vl", true);
    }

    public int getTurnCountThreshold(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.path-detection.turn-count-threshold", 10);
    }

    public int getBranchCountThreshold(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.path-detection.branch-count-threshold", 6);
    }

    public int getYChangeThreshold(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.path-detection.y-change-threshold", 4);
    }

    public int getYPosChangeThresholdAddRequired(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.path-detection.y-change-threshold-add-required", 3);
    }

    public int getMaxVeinDistance(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.max_vein_distance", 5);
    }

    public int getSmallVeinSize(String worldName) {
        return bridge.getConfigForWorld(worldName, "xray.small_vein_detection_size", 4);
    }
}
