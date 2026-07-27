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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests: straight-line tunnels must not be classified as
 * suspicious by the path analyser or the smooth-path guard.
 */
class PathAnalyzerTest {

    private final PathAnalyzer analyzer = new PathAnalyzer();

    // ── helpers ──

    static CommonLocation loc(String world, int x, int y, int z) {
        return new CommonLocation(world, x, y, z);
    }

    // ── straight 1‑high tunnel ──────────────────────────────────────────

    @Test
    void straight1HighTunnel_isSimpleLinear() {
        List<CommonLocation> path = new ArrayList<>();
        for (int x = 0; x <= 50; x += 5) {
            path.add(loc("world", x, 10, 0));
        }
        // 11 points, perfectly straight in XZ, yRange = 0
        assertTrue(analyzer.isSimpleLinearTunnel(path),
            "straight 1-high tunnel should be simple-linear");
    }

    @Test
    void straight1HighTunnel_isSmoothByMetrics() {
        List<CommonLocation> path = new ArrayList<>();
        for (int x = 0; x <= 50; x += 5) {
            path.add(loc("world", x, 10, 0));
        }
        var m = analyzer.analyzePath(path, 3);
        // With no direction changes, turns/branches/yChanges must all be 0
        assertEquals(0, m.turns, "straight 1-high: turns");
        assertEquals(0, m.branches, "straight 1-high: branches");
        assertEquals(0, m.yChanges, "straight 1-high: yChanges");
    }

    // ── straight 2‑high tunnel (Y alternates 10 ↔ 11) ────────────────────

    @Test
    void straight2HighTunnel_isSimpleLinear() {
        List<CommonLocation> path = new ArrayList<>();
        for (int x = 0; x <= 50; x += 5) {
            int y = (x / 5) % 2 == 0 ? 10 : 11; // alternate Y
            path.add(loc("world", x, y, 0));
        }
        assertTrue(analyzer.isSimpleLinearTunnel(path),
            "straight 2-high tunnel (Y 10↔11) should be simple-linear");
    }

    @Test
    void straight2HighTunnel_yChangesExceedThreshold() {
        // Demonstrate the bug: Y alternation (10↔11) in a straight 2-high
        // tunnel accumulates yChanges via the pending-y accumulator, and
        // with default yChangeThreshold=4 / yChangeThresholdAddRequired=3
        // the path is falsely classified as non-smooth.
        List<CommonLocation> path = new ArrayList<>();
        for (int x = 0; x <= 80; x += 5) {
            int y = (x / 5) % 2 == 0 ? 10 : 11;
            path.add(loc("world", x, y, 0));
        }
        var m = analyzer.analyzePath(path, 3);
        // 17 points, 16 pairs with |dy|=1 → 16/3 = 5 yChanges
        assertEquals(0, m.turns,  "2-high straight: turns should be 0");
        assertEquals(0, m.branches, "2-high straight: branches should be 0");
        assertTrue(m.yChanges > 4,
            "2-high straight: yChanges should exceed default threshold of 4 "
            + "(got " + m.yChanges + "); this is the root cause of the false positive");
    }

    // ── zigzag x-ray pattern ────────────────────────────────────────────

    @Test
    void zigzagXrayPath_notSimpleLinear() {
        List<CommonLocation> path = new ArrayList<>();
        // Random-looking positions that aren't a simple straight line
        path.add(loc("world", 0, 10, 0));
        path.add(loc("world", 5, 11, 3));
        path.add(loc("world", 10, 10, 5));
        path.add(loc("world", 15, 12, 8));
        path.add(loc("world", 20, 11, 12));
        path.add(loc("world", 25, 13, 7));
        path.add(loc("world", 30, 10, 15));
        path.add(loc("world", 35, 12, 10));
        path.add(loc("world", 40, 11, 18));
        path.add(loc("world", 45, 14, 13));
        assertFalse(analyzer.isSimpleLinearTunnel(path),
            "zigzag xray path must not be classified as simple-linear");
    }

    @Test
    void zigzagXrayPath_hasRealTurns() {
        List<CommonLocation> path = new ArrayList<>();
        path.add(loc("world", 0, 10, 0));
        path.add(loc("world", 5, 11, 3));
        path.add(loc("world", 10, 10, 5));
        path.add(loc("world", 15, 12, 8));
        path.add(loc("world", 20, 11, 12));
        path.add(loc("world", 25, 13, 7));
        path.add(loc("world", 30, 10, 15));
        path.add(loc("world", 35, 12, 10));
        path.add(loc("world", 40, 11, 18));
        path.add(loc("world", 45, 14, 13));
        var m = analyzer.analyzePath(path, 3);
        assertTrue(m.turns > 2,
            "zigzag path must have substantial turns");
    }

    // ── staircase (Y changes monotonically) ──────────────────────────────

    @Test
    void staircaseDown_notSimpleLinear() {
        List<CommonLocation> path = new ArrayList<>();
        for (int x = 0; x <= 50; x += 5) {
            path.add(loc("world", x, 50 - x / 5, 0));
        }
        // yRange = 10, so must NOT be simple-linear
        assertFalse(analyzer.isSimpleLinearTunnel(path),
            "staircase (yRange > 2) must not be simple-linear");
    }

    // ── too‑short path ───────────────────────────────────────────────────

    @Test
    void tooShortPath_notSimpleLinear() {
        List<CommonLocation> path = new ArrayList<>();
        path.add(loc("world", 0, 10, 0));
        path.add(loc("world", 20, 10, 0));
        assertFalse(analyzer.isSimpleLinearTunnel(path),
            "2-point path is too short for linearity check");
    }

    @Test
    void tooFewPoints_notSimpleLinear() {
        assertFalse(analyzer.isSimpleLinearTunnel(List.of(
            loc("world", 0, 10, 0), loc("world", 5, 10, 0)
        )), "2-point path is too short");
    }
}
