package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.CoreLogger;

import java.util.List;
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
        if (!state.isBypassDisabled(worldName)) {
            // Bypass allowed — but we still track placed blocks above
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
        CoreLogger.debug("  smooth=" + smooth + " natural=" + natural);

        if (!natural && !smooth) {
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
