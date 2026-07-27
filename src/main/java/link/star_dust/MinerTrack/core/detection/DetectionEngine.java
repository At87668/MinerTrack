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

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

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
