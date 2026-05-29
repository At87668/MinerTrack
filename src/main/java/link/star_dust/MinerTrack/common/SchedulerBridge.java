package link.star_dust.MinerTrack.common;

/**
 * Platform-agnostic scheduling operations.
 */
public interface SchedulerBridge {
    void runTask(Runnable task);
    void runTaskLater(Runnable task, long delay);
    void runTaskTimer(Runnable task, long delay, long period);
    void cancelTask(Object task);
}