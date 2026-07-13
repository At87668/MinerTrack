package link.star_dust.MinerTrack.common;

public class CommonPathMetrics {
    public final int turns;
    public final int branches;
    public final int yChanges;

    public CommonPathMetrics(int turns, int branches, int yChanges) {
        this.turns = turns;
        this.branches = branches;
        this.yChanges = yChanges;
    }
}
