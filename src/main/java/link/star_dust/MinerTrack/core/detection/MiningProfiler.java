package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;

import java.util.List;

public class MiningProfiler {

    public static class Result {
        public final int increaseAmount;
        /**
         * Short human-readable explanation of why {@code increaseAmount}
         * was chosen. Surfaced via {@code CoreLogger.debug} when debug
         * mode is on so the operator can understand a VL bump without
         * having to instrument the code path themselves.
         */
        public final String reason;
        public Result(int increaseAmount, String reason) {
            this.increaseAmount = increaseAmount;
            this.reason = reason;
        }
    }

    // Basic migration of legacy analysis: compute disconnected segments and total distance
    // For now keep behavior identical: always increase by 1 when called.
    public Result analyzeAndDecide(List<CommonLocation> path, int minedVeinCount, CommonLocation blockLocation) {
        if (path == null) return new Result(0, "no-path");

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

        // Default rule preserved: add 1 VL. The reason string surfaces
        // the path statistics so the operator can see at a glance why
        // the profiler fired when reading CoreLogger.debug output.
        String reason = "default +1 (pathLen=" + path.size()
            + " dist=" + String.format("%.2f", totalDistance)
            + " segments=" + disconnectedSegments
            + " veinCount=" + minedVeinCount + ")";
        return new Result(1, reason);
    }
}
