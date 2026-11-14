/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 */

package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.PreExplosionBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

final class ExplosionClusterer {
    private static final Comparator<PreExplosionBlock> BLOCK_ORDER = Comparator
        .comparing((PreExplosionBlock block) -> block.location().world,
            Comparator.nullsFirst(String::compareTo))
        .thenComparingInt(block -> block.location().x)
        .thenComparingInt(block -> block.location().y)
        .thenComparingInt(block -> block.location().z)
        .thenComparing(PreExplosionBlock::blockType);

    private ExplosionClusterer() {}

    static List<OreCluster> group(List<PreExplosionBlock> blocks, int maxDistance) {
        List<PreExplosionBlock> ordered = new ArrayList<>(blocks);
        ordered.sort(BLOCK_ORDER);

        Set<PreExplosionBlock> remaining = new HashSet<>(ordered);
        List<OreCluster> clusters = new ArrayList<>();
        double maxDistanceSquared = Math.max(1, maxDistance) * (double) Math.max(1, maxDistance);

        while (!remaining.isEmpty()) {
            PreExplosionBlock seed = ordered.stream()
                .filter(remaining::contains)
                .findFirst()
                .orElseThrow();
            remaining.remove(seed);

            List<CommonLocation> locations = new ArrayList<>();
            Queue<CommonLocation> frontier = new ArrayDeque<>();
            frontier.add(seed.location());
            locations.add(seed.location());

            while (!frontier.isEmpty()) {
                CommonLocation current = frontier.remove();
                List<PreExplosionBlock> connected = new ArrayList<>();
                for (PreExplosionBlock candidate : ordered) {
                    if (remaining.contains(candidate)
                            && seed.blockType().equals(candidate.blockType())
                            && withinDistance(current, candidate.location(), maxDistanceSquared)) {
                        connected.add(candidate);
                    }
                }
                for (PreExplosionBlock candidate : connected) {
                    remaining.remove(candidate);
                    locations.add(candidate.location());
                    frontier.add(candidate.location());
                }
            }

            clusters.add(new OreCluster(seed.blockType(), locations));
        }

        return List.copyOf(clusters);
    }

    private static boolean withinDistance(CommonLocation first, CommonLocation second,
                                          double maxDistanceSquared) {
        if (!Objects.equals(first.world, second.world)) return false;
        double dx = (double) first.x - second.x;
        double dy = (double) first.y - second.y;
        double dz = (double) first.z - second.z;
        return dx * dx + dy * dy + dz * dz <= maxDistanceSquared;
    }

    record OreCluster(String blockType, List<CommonLocation> locations) {
        OreCluster {
            locations = List.copyOf(locations);
        }

        CommonLocation representative() {
            return locations.get(0);
        }
    }
}
