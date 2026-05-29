package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.SchedulerBridge;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class BukkitSchedulerBridge implements SchedulerBridge {
    private final JavaPlugin plugin;
    private final Map<Object, BukkitTask> tasks = new ConcurrentHashMap<>();

    public BukkitSchedulerBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runTask(Runnable task) {
        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTask(plugin, task);
        tasks.put(bukkitTask.getTaskId(), bukkitTask);
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
        tasks.put(bukkitTask.getTaskId(), bukkitTask);
    }

    @Override
    public void runTaskTimer(Runnable task, long delay, long period) {
        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delay, period);
        tasks.put(bukkitTask.getTaskId(), bukkitTask);
    }

    @Override
    public void cancelTask(Object task) {
        if (task instanceof BukkitTask) {
            ((BukkitTask) task).cancel();
        } else if (task instanceof Integer) {
            plugin.getServer().getScheduler().cancelTask((Integer) task);
        }
    }
}