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
import link.star_dust.MinerTrack.common.ExplosionAttribution;
import link.star_dust.MinerTrack.common.ExplosionSnapshot;
import link.star_dust.MinerTrack.common.PreExplosionBlock;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.CoreLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core mining detection orchestrator.
 * Combines MiningState (tracking), PathAnalyzer (path quality),
 * EnvironmentAnalyzer (natural cave/sea/lava detection), and
 * ViolationManagerBridge (VL increase / commands / webhooks).
 *
 * Platform implementations provide DetectionBridge (block lookups, config)
 * and ViolationManagerBridge (VL management).
 */
public class MiningCore {
    private final DetectionBridge bridge;
    private final ViolationManagerBridge vlBridge;
    private final MiningState state;
    private final PathAnalyzer pathAnalyzer;
    private final EnvironmentAnalyzer envAnalyzer;
    private final MiningProfiler profiler;

    public MiningCore(DetectionBridge bridge, ViolationManagerBridge vlBridge) {
        this.bridge = bridge;
        this.vlBridge = vlBridge;
        this.state = new MiningState(bridge);
        this.pathAnalyzer = new PathAnalyzer();
        this.envAnalyzer = new EnvironmentAnalyzer(bridge);
        this.profiler = new MiningProfiler();
    }

    // ─── Public detection entry point ─────────────────────────────────────

    /**
     * Called when a player breaks a block.
     * Returns true if the block was processed (rare ore, detection enabled, not bypassed).
     */
    public boolean onBlockBreak(UUID playerId, String playerName,
                                String worldName, String blockType,
                                int blockX, int blockY, int blockZ) {
        CommonLocation loc = new CommonLocation(worldName, blockX, blockY, blockZ);
        CoreLogger.debug("MiningCore.onBlockBreak: player=" + playerName
            + " world=" + worldName + " block=" + blockType
            + " pos=" + blockX + "," + blockY + "," + blockZ);

        if (!state.isWorldDetectionEnabled(worldName)) {
            CoreLogger.debug("  skip: world detection disabled for " + worldName);
            return false;
        }

        // Check max height
        int maxHeight = state.getWorldMaxHeight(worldName);
        if (maxHeight != -1 && blockY > maxHeight) {
            CoreLogger.debug("  skip: y=" + blockY + " exceeds maxHeight=" + maxHeight);
            return false;
        }

        // Check player-placed block
        if (bridge.isPlayerPlacedBlock(playerId, loc)) {
            CoreLogger.debug("  skip: block is player-placed");
            return false;
        }

        // Track artificial air
        bridge.trackBrokenAir(playerId, loc);

        // Check bypass permission
        if (!state.isBypassDisabled(worldName) && bridge.hasPermission(playerId, "minertrack.bypass")) {
            CoreLogger.debug("  skip: player has bypass permission");
            return false;
        }

        List<String> rareOres = state.getRareOres(worldName);
        if (!rareOres.contains(blockType)) {
            CoreLogger.debug("  skip: " + blockType + " is not a rare ore for " + worldName);
            return false;
        }

        // Record VL=0 timestamp if VL is currently 0
        if (vlBridge.getViolationLevel(playerId) == 0) {
            state.recordVLZero(playerId);
        }

        // Append to mining path
        boolean exposedToAir = state.isExposedToAir(loc);
        state.appendToPath(worldName, playerId, loc, exposedToAir);

        List<CommonLocation> path = state.getPath(worldName, playerId);
        CoreLogger.debug("  path length: " + (path == null ? 0 : path.size())
            + " exposedToAir=" + exposedToAir);

        // Path quality checks
        boolean smooth = isSmoothPath(playerId, worldName, path);
        boolean natural = envAnalyzer.isInNaturalEnvironment(worldName, playerId, loc, path);
        boolean simpleLinear = pathAnalyzer.isSimpleLinearTunnel(path);
        CoreLogger.debug("  smooth=" + smooth + " natural=" + natural
            + " simpleLinear=" + simpleLinear);

        if (!natural && !smooth && !simpleLinear) {
            if (state.isNewVein(playerId, worldName, loc, blockType)) {
                state.incrementVeinCount(playerId);
                state.setLastVeinLocation(playerId, worldName, loc);

                int veinCount = state.getMinedVeinCount(playerId);
                int threshold = state.getVeinCountThreshold(worldName);
                int maxVeinDist = state.getMaxVeinDistance(worldName);
                CoreLogger.debug("  new vein: veinCount=" + veinCount + " threshold=" + threshold
                    + " max_vein_distance=" + maxVeinDist);

                if (veinCount >= threshold) {
                    int veinSize = countVeinBlocks(loc, blockType, worldName);
                    CoreLogger.debug("  threshold reached: veinSize=" + veinSize
                        + " max_vein_distance=" + maxVeinDist);
                    analyzeAndIncreaseVL(playerId, playerName, worldName, blockType, veinSize, loc);
                }
            } else {
                // isNewVein returned false: this block is in the same
                // cluster as (or too close to) the previously recorded
                // vein, so veinCount is NOT incremented. The detailed
                // reason was already logged inside MiningState.isNewVein;
                // here we just surface the high-level outcome so the
                // operator can correlate it with the earlier "smooth=false
                // natural=false" line above.
                int maxVeinDist = state.getMaxVeinDistance(worldName);
                CoreLogger.debug("  same/close vein: not counting toward VL"
                    + " (max_vein_distance=" + maxVeinDist + "); see isNewVein log above");
            }
        }

        return true;
    }

    public boolean onExplosion(ExplosionSnapshot snapshot) {
        String worldName = snapshot.origin().world;
        if (worldName == null || worldName.isBlank()) return false;
        if (!state.isExplosionDetectionEnabled(worldName)) return false;

        ExplosionAttribution attribution = snapshot.attribution();
        if (!state.isWorldDetectionEnabled(worldName)) return false;
        if (attribution == null || !snapshot.source().canScore()) {
            CoreLogger.debug("MiningCore.onExplosion: skip scoring source=" + snapshot.source().marker()
                + " attributed=" + (attribution != null));
            return false;
        }

        UUID playerId = attribution.playerId();
        if (!state.isBypassDisabled(worldName) && bridge.hasPermission(playerId, "minertrack.bypass")) {
            return false;
        }

        if (state.isTrackExplosionCraterAir(worldName)) {
            for (PreExplosionBlock block : snapshot.blocks()) {
                if (worldName.equals(block.location().world)) {
                    bridge.trackBrokenAir(playerId, block.location());
                }
            }
        }

        int maxHeight = state.getWorldMaxHeight(worldName);
        List<String> rareOres = state.getRareOres(worldName);
        List<PreExplosionBlock> rareBlocks = new ArrayList<>();
        for (PreExplosionBlock block : snapshot.blocks()) {
            CommonLocation location = block.location();
            if (!worldName.equals(location.world) || (maxHeight != -1 && location.y > maxHeight)) continue;
            if (block.playerPlaced() || bridge.isPlayerPlacedBlock(playerId, location)) continue;
            if (rareOres.contains(block.blockType())) rareBlocks.add(block);
        }

        List<ExplosionClusterer.OreCluster> clusters = ExplosionClusterer.group(
            rareBlocks, state.getMaxVeinDistance(worldName));
        List<CommonLocation> locations = new ArrayList<>();
        Set<String> blockTypes = new HashSet<>();
        for (ExplosionClusterer.OreCluster cluster : clusters) {
            locations.addAll(cluster.locations());
            blockTypes.add(cluster.blockType());
        }
        if (locations.isEmpty()) return true;

        locations.sort(Comparator
            .comparing((CommonLocation location) -> location.world)
            .thenComparingInt(location -> location.x)
            .thenComparingInt(location -> location.y)
            .thenComparingInt(location -> location.z));
        String blockType = blockTypes.size() == 1
            ? blockTypes.iterator().next()
            : "minecraft:multiple_rare_ores";
        processExplosionCluster(snapshot, attribution,
            new ExplosionClusterer.OreCluster(blockType, locations));
        return true;
    }

    private void processExplosionCluster(ExplosionSnapshot snapshot,
                                         ExplosionAttribution attribution,
                                         ExplosionClusterer.OreCluster cluster) {
        String worldName = snapshot.origin().world;
        CommonLocation location = cluster.representative();
        boolean exposedToAir = state.isExposedToAir(location);
        state.appendToPath(worldName, attribution.playerId(), location, exposedToAir);

        List<CommonLocation> path = state.getPath(worldName, attribution.playerId());
        if (vlBridge.getViolationLevel(attribution.playerId()) == 0) {
            state.recordVLZero(attribution.playerId());
        }
        boolean smooth = isSmoothPath(attribution.playerId(), worldName, path);
        boolean natural = envAnalyzer.isInNaturalEnvironment(
            worldName, attribution.playerId(), location, path);
        boolean simpleLinear = pathAnalyzer.isSimpleLinearTunnel(path);
        CoreLogger.debug("MiningCore.onExplosion: source=" + snapshot.sourceMarker()
            + " ore=" + cluster.blockType() + " size=" + cluster.locations().size()
            + " smooth=" + smooth + " natural=" + natural + " simpleLinear=" + simpleLinear);

        if (natural || smooth || simpleLinear) return;

        Set<CommonLocation> clusterLocations = new HashSet<>(cluster.locations());
        if (!state.isNewVein(attribution.playerId(), worldName, location,
                cluster.blockType(), clusterLocations)) return;

        state.incrementVeinCount(attribution.playerId());
        state.setLastVeinLocation(attribution.playerId(), worldName, location);
        int veinCount = state.getMinedVeinCount(attribution.playerId());
        if (veinCount >= state.getVeinCountThreshold(worldName)) {
            analyzeAndIncreaseVL(attribution.playerId(), attribution.playerName(), worldName,
                cluster.blockType() + "|" + snapshot.sourceMarker(), cluster.locations().size(), location);
        }
    }

    // ─── Path analysis ─────────────────────────────────────────────────────

    private boolean isSmoothPath(UUID playerId, String worldName, List<CommonLocation> path) {
        if (path == null || path.size() < 2) return true;
        int turnThreshold = state.getTurnCountThreshold(worldName);
        int branchThreshold = state.getBranchCountThreshold(worldName);
        int yChangeThreshold = state.getYChangeThreshold(worldName);
        int yChangeAddRequired = state.getYPosChangeThresholdAddRequired(worldName);

        var metrics = pathAnalyzer.analyzePath(path, yChangeAddRequired);
        return pathAnalyzer.isSmooth(metrics, turnThreshold, branchThreshold, yChangeThreshold);
    }

    // ─── Vein counting ─────────────────────────────────────────────────────

    public int countVeinBlocks(CommonLocation start, String type, String worldName) {
        int maxDist = state.getMaxVeinDistance(worldName);
        DetectionEngine engine = new DetectionEngine(bridge);
        return engine.getVeinLocations(start, type, maxDist).size();
    }

    // ─── VL increase ──────────────────────────────────────────────────────

    private void analyzeAndIncreaseVL(UUID playerId, String playerName,
                                       String worldName, String blockType,
                                       int veinSize, CommonLocation loc) {
        List<CommonLocation> path = state.getPath(worldName, playerId);
        int veinCount = state.getMinedVeinCount(playerId);

        MiningProfiler.Result result = profiler.analyzeAndDecide(path, veinCount, loc);
        int increaseAmount = result.increaseAmount;
        CoreLogger.debug("  profiler: increaseAmount=" + increaseAmount
            + " reason=" + result.reason);

        if (increaseAmount > 0) {
            vlBridge.increaseViolationLevel(playerId, playerName, increaseAmount,
                blockType, veinSize, veinCount, loc);
            state.removeVLZeroTimestamp(playerId);
            CoreLogger.debug("  VL increased by " + increaseAmount + " for " + playerName);
        }
    }

    // ─── Periodic cleanup (called by platform scheduler) ──────────────────

    public void cleanupExpiredPaths() {
        state.cleanupExpiredPaths();

        long now = System.currentTimeMillis();
        for (UUID playerId : state.getPlayersWithVLZeroTimestamp()) {
            Long lastZero = state.getVLZeroTimestamp(playerId);
            if (lastZero == null) continue;
            if (vlBridge.getViolationLevel(playerId) != 0) {
                // VL went back up since the timestamp was recorded; the
                // decay scheduler should clear the timestamp itself.
                // Just make sure we don't keep a stale entry.
                state.removeVLZeroTimestamp(playerId);
                continue;
            }
            
            long maxTraceRemoveMs = 0;
            for (String worldName : state.getWorldsWithPlayer(playerId)) {
                long ms = state.getTraceRemoveTime(worldName) * 60L * 1000L;
                if (ms > maxTraceRemoveMs) maxTraceRemoveMs = ms;
            }
            if (maxTraceRemoveMs <= 0) {
                // No worlds tracked; the timestamp is dangling.
                state.removeVLZeroTimestamp(playerId);
                continue;
            }
            if (now - lastZero > maxTraceRemoveMs) {
                CoreLogger.debug("  cleanupExpiredPaths: resetting trace for "
                    + playerId + " (VL=0 for " + ((now - lastZero) / 1000)
                    + "s, threshold=" + (maxTraceRemoveMs / 1000) + "s)");
                resetPlayer(playerId);
            }
        }
    }

    public void cleanupExpiredPlacedBlocks() {
        state.cleanupExpiredPlacedBlocks();
    }

    public void cleanupExpiredBrokenAir() {
        state.cleanupExpiredBrokenAir();
    }

    /**
     * Reset all tracking for a player (called when VL reaches 0 and times out,
     * or when the player is explicitly reset).
     */
    public void resetPlayer(UUID playerId) {
        state.clearPlayerPath(playerId);
        vlBridge.clearPlayerState(playerId);
    }

    // ─── Accessors ──────────────────────────────────────────────────────────

    public MiningState getState() { return state; }
    public ViolationManagerBridge getVLBridge() { return vlBridge; }
    public DetectionBridge getBridge() { return bridge; }
}
