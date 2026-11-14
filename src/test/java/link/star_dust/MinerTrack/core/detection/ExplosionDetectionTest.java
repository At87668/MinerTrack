/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 */

package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.BlockId;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.ExplosionSnapshot;
import link.star_dust.MinerTrack.common.ExplosionSource;
import link.star_dust.MinerTrack.common.PreExplosionBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionDetectionTest {
    private static final String WORLD = "minecraft:overworld";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<CommonLocation> SUSPICIOUS_POINTS = List.of(
        loc(0, 10, 0), loc(8, 11, 4), loc(0, 12, 8), loc(8, 13, 12),
        loc(0, 14, 16), loc(8, 15, 20), loc(0, 16, 24), loc(8, 17, 28),
        loc(0, 18, 32));
    private static final List<int[]> CRATER_AIR_OFFSETS = List.of(
        new int[]{-2, 0, 0}, new int[]{-1, 0, 0}, new int[]{0, 0, -2},
        new int[]{0, 0, -1}, new int[]{0, 0, 1}, new int[]{0, 0, 2},
        new int[]{1, 0, 0}, new int[]{2, 0, 0}, new int[]{-1, 1, 0},
        new int[]{0, 1, -1}, new int[]{0, 1, 0}, new int[]{0, 1, 1},
        new int[]{1, 1, 0}, new int[]{-1, -1, 0}, new int[]{0, -1, 0},
        new int[]{1, -1, 0});

    @Test
    void attributedTntSequence_scoresNonLinearSeparatedOreGroups_andTracksCraterAir() {
        ScenarioResult tracked = runSuspiciousSequence(true);
        ScenarioResult untracked = runSuspiciousSequence(false);

        assertEquals(1, tracked.violations.getViolationLevel(PLAYER));
        assertEquals(1, tracked.violations.records.size());
        ViolationRecord record = tracked.violations.records.get(0);
        assertEquals(1, record.increment());
        assertEquals(BlockId.DIAMOND_ORE + "|explosion:tnt", record.blockType());
        assertEquals(1, record.veinSize());
        assertEquals(2, record.veinCount());
        assertEquals(SUSPICIOUS_POINTS.get(8), record.location());
        assertEquals(9, tracked.core.getState().getPath(WORLD, PLAYER).size());
        assertEquals(2, tracked.core.getState().getMinedVeinCount(PLAYER));
        assertEquals(9 * 17, tracked.detection.artificialAir.size());

        assertEquals(0, untracked.violations.getViolationLevel(PLAYER));
        assertTrue(untracked.violations.records.isEmpty());
        assertEquals(0, untracked.detection.artificialAir.size());
        assertFalse(untracked.core.getState().getPath(WORLD, PLAYER).isEmpty(),
            "untracked crater air still contributes path samples before natural classification");
    }

    @Test
    void singleMultiBlockCluster_addsOnePathSample_withoutFalseViolation() {
        InMemoryDetectionBridge detection = fixture(false);
        InMemoryViolationManager violations = new InMemoryViolationManager();
        MiningCore core = new MiningCore(detection, violations);
        List<PreExplosionBlock> blocks = List.of(
            new PreExplosionBlock(loc(0, 10, 0), BlockId.DIAMOND_ORE),
            new PreExplosionBlock(loc(1, 10, 0), BlockId.DIAMOND_ORE),
            new PreExplosionBlock(loc(0, 11, 0), BlockId.DIAMOND_ORE));
        ExplosionSnapshot snapshot = new ExplosionSnapshot(
            loc(0, 10, 0), ExplosionSource.TNT, PLAYER, "miner", blocks);
        detection.liveAir.addAll(blocks.stream().map(PreExplosionBlock::location).toList());

        assertTrue(core.onExplosion(snapshot));
        assertEquals(List.of(loc(0, 10, 0)), core.getState().getPath(WORLD, PLAYER));
        assertEquals(0, core.getState().getMinedVeinCount(PLAYER));
        assertEquals(0, violations.getViolationLevel(PLAYER));
        assertTrue(violations.records.isEmpty());
    }

    @Test
    void oneExplosionWithDistantSameTypeOres_addsOnePathSampleAndOneVein() {
        InMemoryDetectionBridge detection = fixture(true);
        detection.worldValues.put("xray.path-detection.turn-count-threshold", 0);
        InMemoryViolationManager violations = new InMemoryViolationManager();
        MiningCore core = new MiningCore(detection, violations);
        CommonLocation previous = loc(100, 10, 0);
        CommonLocation first = loc(0, 10, 0);
        CommonLocation second = loc(20, 10, 0);

        detection.liveAir.add(previous);
        assertTrue(core.onExplosion(new ExplosionSnapshot(
            previous, ExplosionSource.TNT, PLAYER, "miner",
            List.of(new PreExplosionBlock(previous, BlockId.DIAMOND_ORE)) )));

        List<PreExplosionBlock> blocks = List.of(
            new PreExplosionBlock(first, BlockId.DIAMOND_ORE),
            new PreExplosionBlock(second, BlockId.DIAMOND_ORE));
        detection.liveAir.addAll(List.of(first, second));

        assertTrue(core.onExplosion(new ExplosionSnapshot(
            first, ExplosionSource.TNT, PLAYER, "miner", blocks)));

        assertEquals(List.of(previous, first), core.getState().getPath(WORLD, PLAYER));
        assertEquals(1, core.getState().getMinedVeinCount(PLAYER));
        assertEquals(0, violations.getViolationLevel(PLAYER));
        assertTrue(violations.records.isEmpty());
    }

    @Test
    void suspiciousSequenceWithDistantOreGroups_addsOneViolationRecord() {
        InMemoryDetectionBridge detection = fixture(true);
        InMemoryViolationManager violations = new InMemoryViolationManager();
        MiningCore core = new MiningCore(detection, violations);

        for (CommonLocation point : SUSPICIOUS_POINTS.subList(0, 8)) {
            ExplosionSnapshot snapshot = snapshotAt(point, ExplosionSource.TNT, PLAYER, false);
            detection.liveAir.addAll(snapshot.blocks().stream().map(PreExplosionBlock::location).toList());
            assertTrue(core.onExplosion(snapshot));
        }

        List<PreExplosionBlock> finalBlocks = List.of(
            new PreExplosionBlock(loc(0, 20, 40), BlockId.DIAMOND_ORE),
            new PreExplosionBlock(loc(1, 20, 40), BlockId.DIAMOND_ORE),
            new PreExplosionBlock(loc(20, 20, 40), BlockId.DIAMOND_ORE),
            new PreExplosionBlock(loc(21, 20, 40), BlockId.DIAMOND_ORE));
        detection.liveAir.addAll(finalBlocks.stream().map(PreExplosionBlock::location).toList());

        assertTrue(core.onExplosion(new ExplosionSnapshot(
            loc(0, 20, 40), ExplosionSource.TNT, PLAYER, "miner", finalBlocks)));

        assertEquals(1, violations.getViolationLevel(PLAYER));
        assertEquals(1, violations.records.size());
        ViolationRecord record = violations.records.get(0);
        assertEquals(1, record.increment());
        assertEquals(BlockId.DIAMOND_ORE + "|explosion:tnt", record.blockType());
        assertEquals(4, record.veinSize());
        assertEquals(2, record.veinCount());
        assertEquals(loc(0, 20, 40), record.location());
        assertEquals(9, core.getState().getPath(WORLD, PLAYER).size());
        assertEquals(2, core.getState().getMinedVeinCount(PLAYER));
    }

    @Test
    void blockBreakIsolatedRareOreAlreadyAir_recordsOneManualSingletonVein() {
        InMemoryDetectionBridge detection = fixture(false);
        detection.worldValues.put("xray.path-detection.turn-count-threshold", 0);
        InMemoryViolationManager violations = new InMemoryViolationManager();
        MiningCore core = new MiningCore(detection, violations);
        CommonLocation previous = loc(100, 10, 0);
        CommonLocation singleton = loc(0, 10, 0);

        detection.blocks.put(previous, BlockId.DIAMOND_ORE);
        assertTrue(core.onBlockBreak(PLAYER, "miner", WORLD, BlockId.DIAMOND_ORE,
            previous.x, previous.y, previous.z));

        detection.liveAir.add(singleton);
        detection.manualSingletons.add(singleton);
        assertTrue(core.onBlockBreak(PLAYER, "miner", WORLD, BlockId.DIAMOND_ORE,
            singleton.x, singleton.y, singleton.z));

        assertEquals(List.of(previous, singleton), core.getState().getPath(WORLD, PLAYER));
        assertEquals(1, core.getState().getMinedVeinCount(PLAYER));
        assertEquals(0, violations.getViolationLevel(PLAYER));
        assertTrue(violations.records.isEmpty());
    }

    @Test
    void explosionGuards_doNotIncreaseViolationLevel() {
        List<NonScoringCase> cases = List.of(
            new NonScoringCase("placed ore", ExplosionSource.TNT, PLAYER, true, true, true, false),
            new NonScoringCase("unattributed TNT", ExplosionSource.TNT, null, false, true, true, false),
            new NonScoringCase("non-player explosion source", ExplosionSource.CREEPER, PLAYER, false, true, true, false),
            new NonScoringCase("natural air", ExplosionSource.TNT, PLAYER, false, false, true, false),
            new NonScoringCase("world detection disabled", ExplosionSource.TNT, PLAYER, false, true, false, false),
            new NonScoringCase("explosion detection disabled", ExplosionSource.TNT, PLAYER, false, true, true, true),
            new NonScoringCase("bypass", ExplosionSource.TNT, PLAYER, false, true, true, false));

        for (NonScoringCase testCase : cases) {
            InMemoryDetectionBridge detection = fixture(testCase.trackCraterAir());
            detection.worldDetectionEnabled = testCase.worldEnabled();
            detection.explosionDetectionEnabled = testCase.explosionDisabled() ? false : true;
            detection.bypass = testCase.name().equals("bypass");
            InMemoryViolationManager violations = new InMemoryViolationManager();
            MiningCore core = new MiningCore(detection, violations);
            ExplosionSnapshot snapshot = snapshotAt(
                SUSPICIOUS_POINTS.get(0), testCase.source(), testCase.playerId(), testCase.playerPlaced());
            detection.liveAir.addAll(snapshot.blocks().stream().map(PreExplosionBlock::location).toList());

            core.onExplosion(snapshot);

            assertEquals(0, violations.getViolationLevel(PLAYER), testCase.name());
            assertTrue(violations.records.isEmpty(), testCase.name());
        }
    }

    private static ScenarioResult runSuspiciousSequence(boolean trackCraterAir) {
        InMemoryDetectionBridge detection = fixture(trackCraterAir);
        InMemoryViolationManager violations = new InMemoryViolationManager();
        MiningCore core = new MiningCore(detection, violations);
        for (CommonLocation point : SUSPICIOUS_POINTS) {
            ExplosionSnapshot snapshot = snapshotAt(point, ExplosionSource.TNT, PLAYER, false);
            detection.liveAir.addAll(snapshot.blocks().stream().map(PreExplosionBlock::location).toList());
            assertTrue(core.onExplosion(snapshot));
        }
        return new ScenarioResult(core, detection, violations);
    }

    private static ExplosionSnapshot snapshotAt(CommonLocation point, ExplosionSource source,
                                                 UUID playerId, boolean playerPlaced) {
        List<PreExplosionBlock> blocks = new ArrayList<>();
        blocks.add(new PreExplosionBlock(point, BlockId.DIAMOND_ORE, playerPlaced));
        for (int[] offset : CRATER_AIR_OFFSETS) {
            blocks.add(new PreExplosionBlock(
                loc(point.x + offset[0], point.y + offset[1], point.z + offset[2]), BlockId.AIR));
        }
        return new ExplosionSnapshot(point, source, playerId, "miner", blocks);
    }

    private static InMemoryDetectionBridge fixture(boolean trackCraterAir) {
        InMemoryDetectionBridge detection = new InMemoryDetectionBridge();
        detection.worldValues.put("xray.explosion-detection.track-crater-air", trackCraterAir);
        return detection;
    }

    private static CommonLocation loc(int x, int y, int z) {
        return new CommonLocation(WORLD, x, y, z);
    }

    private record ScenarioResult(MiningCore core, InMemoryDetectionBridge detection,
                                  InMemoryViolationManager violations) {}

    private record NonScoringCase(String name, ExplosionSource source, UUID playerId,
                                   boolean playerPlaced, boolean trackCraterAir,
                                   boolean worldEnabled, boolean explosionDisabled) {}

    record ViolationRecord(UUID playerId, String playerName, int increment,
                           String blockType, int veinSize, int veinCount,
                           CommonLocation location) {}

}
