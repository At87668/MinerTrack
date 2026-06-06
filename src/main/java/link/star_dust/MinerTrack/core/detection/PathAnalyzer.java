package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CommonPathMetrics;

import java.util.List;

public class PathAnalyzer {

    public CommonPathMetrics analyzePath(List<CommonLocation> path, int yChangeThresholdAddRequired) {
        if (path == null || path.size() < 2) return new CommonPathMetrics(0, 0, 0);

        int currentTurns = 0;
        int currentBranches = 0;
        int currentYChanges = 0;

        CommonLocation lastLocation = null;
        Dir3d lastDirection = null;

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

                if (Math.abs(currentLocation.y - lastLocation.y) > yChangeThresholdAddRequired) {
                    currentYChanges++;
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

    public CommonPathMetrics analyzePath(List<CommonLocation> path) {
        return analyzePath(path, 0);
    }

    public boolean isSmooth(CommonPathMetrics metrics, int turnThreshold, int branchThreshold, int yChangeThreshold) {
        return metrics.turns < turnThreshold && metrics.branches < branchThreshold && metrics.yChanges < yChangeThreshold;
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
