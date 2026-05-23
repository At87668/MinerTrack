package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;

import java.util.*;

public class DetectionEngine {
    private final DetectionBridge bridge;

    public DetectionEngine(DetectionBridge bridge) {
        this.bridge = bridge;
    }

    // Find connected ore cluster using bridge.getBlockType
    public Set<CommonLocation> getVeinLocations(CommonLocation startLocation, String type, int maxDistance) {
        Set<CommonLocation> visited = new HashSet<>();
        Queue<CommonLocation> toVisit = new LinkedList<>();
        if (startLocation == null) return visited;

        double maxDistanceSq = Math.max(1, maxDistance) * (double) Math.max(1, maxDistance);

        if (type.equals(bridge.getBlockType(startLocation.world, startLocation.x, startLocation.y, startLocation.z))) {
            toVisit.add(startLocation);
        } else {
            int seedRadius = Math.max(1, maxDistance);
            int baseX = startLocation.x;
            int baseY = startLocation.y;
            int baseZ = startLocation.z;
            String world = startLocation.world;
            for (int dx = -seedRadius; dx <= seedRadius; dx++) {
                for (int dy = -seedRadius; dy <= seedRadius; dy++) {
                    for (int dz = -seedRadius; dz <= seedRadius; dz++) {
                        double distSq = dx*dx + dy*dy + dz*dz;
                        if (distSq <= maxDistanceSq) {
                            String mat = bridge.getBlockType(world, baseX+dx, baseY+dy, baseZ+dz);
                            if (type.equals(mat)) {
                                toVisit.add(new CommonLocation(world, baseX+dx, baseY+dy, baseZ+dz));
                            }
                        }
                    }
                }
            }
            if (toVisit.isEmpty()) return visited;
            visited.add(startLocation);
        }

        int safetyCap = 2000;
        while (!toVisit.isEmpty() && visited.size() < safetyCap) {
            CommonLocation current = toVisit.poll();
            if (visited.contains(current)) continue;
            String mat = bridge.getBlockType(current.world, current.x, current.y, current.z);
            if (!type.equals(mat)) continue;

            visited.add(current);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        CommonLocation neighbor = new CommonLocation(current.world, current.x+dx, current.y+dy, current.z+dz);
                        if (visited.contains(neighbor)) continue;
                        String nmat = bridge.getBlockType(neighbor.world, neighbor.x, neighbor.y, neighbor.z);
                        if (!type.equals(nmat)) continue;
                        double distSq = (neighbor.x - startLocation.x)*(neighbor.x - startLocation.x)
                                + (neighbor.y - startLocation.y)*(neighbor.y - startLocation.y)
                                + (neighbor.z - startLocation.z)*(neighbor.z - startLocation.z);
                        if (distSq <= maxDistanceSq) {
                            toVisit.add(neighbor);
                        }
                    }
                }
            }
        }

        return visited;
    }

    public int countVeinBlocks(CommonLocation startLocation, String type, int maxDistance) {
        return getVeinLocations(startLocation, type, maxDistance).size();
    }
}
