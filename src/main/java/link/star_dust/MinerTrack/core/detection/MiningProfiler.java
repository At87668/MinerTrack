package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;

import java.util.List;

public class MiningProfiler {

    public static class Result {
        public final int increaseAmount;
        public Result(int increaseAmount) { this.increaseAmount = increaseAmount; }
    }

    // Basic migration of legacy analysis: compute disconnected segments and total distance
    // For now keep behavior identical: always increase by 1 when called.
    public Result analyzeAndDecide(List<CommonLocation> path, int minedVeinCount, CommonLocation blockLocation) {
        if (path == null) return new Result(0);

        double totalDistance = 0.0;
        CommonLocation last = null;
        int disconnectedSegments = 0;

        for (CommonLocation cur : path) {
            if (last != null) {
                double dx = cur.x - last.x;
                double dy = cur.y - last.y;
                double dz = cur.z - last.z;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                totalDistance += dist;
                if (dist > 3) disconnectedSegments++;
            }
            last = cur;
        }

        // Default rule preserved: add 1 VL
        return new Result(1);
    }
}
