package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CommonPathMetrics;

import java.util.List;
import org.bukkit.util.Vector;
import org.bukkit.Location;

public class PathAnalyzer {

    public CommonPathMetrics analyzePath(List<CommonLocation> path) {
        if (path == null || path.size() < 2) return new CommonPathMetrics(0, 0, 0);

        int currentTurns = 0;
        int currentBranches = 0;
        int currentYChanges = 0;

        CommonLocation lastLocation = null;
        Vector lastDirection = null;

        for (int i = 0; i < path.size(); i++) {
            CommonLocation currentLocation = path.get(i);
            if (lastLocation != null) {
                Vector currentDirection = new Vector(currentLocation.x - lastLocation.x, currentLocation.y - lastLocation.y, currentLocation.z - lastLocation.z);
                if (currentDirection.lengthSquared() > 0) currentDirection.normalize();

                if (lastDirection != null && currentDirection.lengthSquared() > 0) {
                    double dotProduct = lastDirection.dot(currentDirection);
                    if (dotProduct < Math.cos(Math.toRadians(30))) {
                        currentTurns++;
                    }
                }

                if (Math.abs(currentLocation.y - lastLocation.y) > 0) {
                    currentYChanges++;
                }

                if (i > 1 && currentDirection.lengthSquared() > 0) {
                    CommonLocation prevLocation = path.get(i - 1);
                    Vector prevDirection = new Vector(prevLocation.x - path.get(i-2).x, prevLocation.y - path.get(i-2).y, prevLocation.z - path.get(i-2).z);
                    if (prevDirection.lengthSquared() > 0) {
                        prevDirection.normalize();
                        if (currentDirection.angle(prevDirection) > Math.toRadians(60)) {
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
        return metrics.turns < turnThreshold && metrics.branches < branchThreshold && metrics.yChanges < yChangeThreshold;
    }
}
