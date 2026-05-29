package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.SchedulerBridge;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class FabricSchedulerBridge implements SchedulerBridge {
    private final Map<Object, Runnable> tasks = new ConcurrentHashMap<>();

    public FabricSchedulerBridge(Object tickable) {
        // Tickable - Fabric API not available in Bukkit build
    }

    @Override
    public void runTask(Runnable task) {
        tasks.put(tasks.size(), task);
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
        tasks.put(tasks.size(), task);
    }

    @Override
    public void runTaskTimer(Runnable task, long delay, long period) {
        tasks.put(tasks.size(), task);
    }

    @Override
    public void cancelTask(Object task) {
        tasks.remove(task);
    }
}