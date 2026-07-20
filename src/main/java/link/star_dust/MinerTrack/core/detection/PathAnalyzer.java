package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CommonPathMetrics;
import link.star_dust.MinerTrack.core.CoreLogger;

import java.util.List;

public class PathAnalyzer {

    public CommonPathMetrics analyzePath(List<CommonLocation> path, int yChangeThresholdAddRequired) {
        if (path == null || path.size() < 2) return new CommonPathMetrics(0, 0, 0);

        int currentTurns = 0;
        int currentBranches = 0;
        int currentYChanges = 0;

        CommonLocation lastLocation = null;
        Dir3d lastDirection = null;

        // Pending y-change accumulator. A "yChange event" is defined as a
        // cumulative y-axis movement of at least
        // {@code yChangeThresholdAddRequired} blocks (in either direction)
        // measured since the previous event. This makes the metric match
        // its name: instead of counting only the rare single steps that
        // happen to jump further than the threshold (the previous
        // implementation), small per-step y deltas now sum up and every
        // completed threshold worth of y travel registers as one event.
        //
        // Why this matters in practice: the path is built from rare-ore
        // break positions, which can be many blocks apart. A player
        // tunneling monotonically down will produce dozens of 1-2 block
        // y deltas — the previous code reported yChanges≈1 for such a
        // path even when the player travelled 30 blocks straight down,
        // so the metric failed to flag obvious staircases. With the
        // accumulator, 30 blocks of vertical travel now registers as
        // ~30/threshold events and the yChangeThreshold starts to do
        // its job.
        int pendingY = 0;
        // Treat a non-positive threshold as "any y movement counts".
        // This also keeps the legacy zero-arg overload (default 0)
        // meaningful: every single block of y change is one event.
        int yAdd = Math.max(1, yChangeThresholdAddRequired);

        for (int i = 0; i < path.size(); i++) {
            CommonLocation currentLocation = path.get(i);
            if (lastLocation != null) {
                Dir3d currentDirection = Dir3d.of(
                    currentLocation.x - lastLocation.x,
                    currentLocation.y - lastLocation.y,
                    currentLocation.z - lastLocation.z
                );
                if (currentDirection.lengthSquared() > 0) currentDirection.normalize();

                if (lastDirection != null && currentDirection.lengthSquared() > 0) {
                    double dotProduct = lastDirection.dot(currentDirection);
                    if (dotProduct < Math.cos(Math.toRadians(30))) {
                        currentTurns++;
                    }
                }

                int dy = Math.abs(currentLocation.y - lastLocation.y);
                if (dy > 0) {
                    pendingY += dy;
                    // Drain as many events as this step can produce, then
                    // keep the leftover (≤ yAdd-1) in pendingY so the
                    // next small step can finish the next event.
                    while (pendingY >= yAdd) {
                        currentYChanges++;
                        pendingY -= yAdd;
                    }
                }

                if (i > 1 && currentDirection.lengthSquared() > 0) {
                    CommonLocation prevLocation = path.get(i - 1);
                    Dir3d prevDirection = Dir3d.of(
                        prevLocation.x - path.get(i-2).x,
                        prevLocation.y - path.get(i-2).y,
                        prevLocation.z - path.get(i-2).z
                    );
                    if (prevDirection.lengthSquared() > 0) {
                        prevDirection.normalize();
                        if (currentDirection.angleDegrees(prevDirection) > 60) {
                            currentBranches++;
                        }
                    }
                }

                lastDirection = currentDirection;
            }
            lastLocation = currentLocation;
        }

        return new CommonPathMetrics(currentTurns, currentBranches, currentYChanges);
    }

    public boolean isSmooth(CommonPathMetrics metrics, int turnThreshold, int branchThreshold, int yChangeThreshold) {
        boolean smooth = metrics.turns < turnThreshold && metrics.branches < branchThreshold && metrics.yChanges < yChangeThreshold;
        CoreLogger.debug("    PathAnalyzer.isSmooth: turns=" + metrics.turns
            + " branches=" + metrics.branches + " yChanges=" + metrics.yChanges
            + " | thresholds turns<" + turnThreshold + " branches<" + branchThreshold
            + " yChanges<" + yChangeThreshold + " -> smooth=" + smooth);
        return smooth;
    }

    public CommonPathMetrics analyzePath(List<CommonLocation> path) {
        return analyzePath(path, 0);
    }

    /**
     * Check whether a mining path represents a simple, linear tunnel
     * (straight single-high or double-high digging) that should never
     * trigger VL accumulation regardless of per-step turn/branch counts.
     *
     * <p>A path is considered simple-linear when:
     * <ul>
     *   <li>It has ≥ 4 points (meaningful trend detection).</li>
     *   <li>The XZ crow-flies distance (from first to last point)
     *       is ≥ 66% of the total XZ path distance, meaning the player
     *       is moving predominantly in a straight line.</li>
     *   <li>The total Y-axis range (|maxY − minY|) is ≤ 2 blocks,
     *       allowing for single-high and double-high tunnels.</li>
     * </ul>
     *
     * <p>This guard catches the most common false-positive scenario:
     * a player digging a straight 2-high tunnel where alternating ore
     * positions at y=10 and y=11 produce direction changes that the
     * per-step turn/branch analyser counts as "non-smooth".
     *
     * @param path the ordered mining positions for a player in one world
     * @return true if this path looks like a simple linear tunnel
     */
    public boolean isSimpleLinearTunnel(List<CommonLocation> path) {
        if (path == null || path.size() < 4) return false;

        // ── Y range ──
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (CommonLocation loc : path) {
            if (loc.y < minY) minY = loc.y;
            if (loc.y > maxY) maxY = loc.y;
        }
        int yRange = maxY - minY;
        if (yRange > 2) return false; // staircase or deep drop

        // ── XZ crow-flies vs. total path length ──
        double totalDistXZ = 0.0;
        CommonLocation prev = null;
        for (CommonLocation cur : path) {
            if (prev != null) {
                double dx = cur.x - prev.x;
                double dz = cur.z - prev.z;
                totalDistXZ += Math.sqrt(dx * dx + dz * dz);
            }
            prev = cur;
        }

        if (totalDistXZ < 10.0) return false; // too short to judge

        CommonLocation first = path.get(0);
        CommonLocation last = path.get(path.size() - 1);
        double crowFliesXZ = Math.sqrt(
            (last.x - first.x) * (last.x - first.x)
                + (last.z - first.z) * (last.z - first.z));

        if (crowFliesXZ < 10.0) return false; // not enough net displacement

        double linearity = crowFliesXZ / totalDistXZ;
        boolean linear = linearity >= 0.66;

        CoreLogger.debug("    PathAnalyzer.isSimpleLinearTunnel: yRange=" + yRange
            + " crowFliesXZ=" + String.format("%.1f", crowFliesXZ)
            + " totalDistXZ=" + String.format("%.1f", totalDistXZ)
            + " linearity=" + String.format("%.3f", linearity)
            + " -> linear=" + linear);

        return linear;
    }

    /**
     * Lightweight immutable 3D direction vector used in path analysis.
     * Avoids depending on Bukkit's {@code org.bukkit.util.Vector} so the
     * detection core stays platform-neutral.
     */
    private static final class Dir3d {
        double x, y, z;

        static Dir3d of(double x, double y, double z) {
            Dir3d d = new Dir3d();
            d.x = x; d.y = y; d.z = z;
            return d;
        }

        double lengthSquared() {
            return x * x + y * y + z * z;
        }

        void normalize() {
            double len = Math.sqrt(lengthSquared());
            if (len == 0) return;
            x /= len; y /= len; z /= len;
        }

        double dot(Dir3d other) {
            return x * other.x + y * other.y + z * other.z;
        }

        /** Angle between this and another direction in degrees. */
        double angleDegrees(Dir3d other) {
            double dot = dot(other);
            // Clamp to [-1, 1] to guard against floating-point drift.
            if (dot > 1.0) dot = 1.0;
            else if (dot < -1.0) dot = -1.0;
            return Math.toDegrees(Math.acos(dot));
        }
    }
}
