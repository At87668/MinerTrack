/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/managers/ViolationManager.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack.bukkit.managers;

import link.star_dust.MinerTrack.bukkit.FoliaCheck;
import link.star_dust.MinerTrack.bukkit.MinerTrack;
import link.star_dust.MinerTrack.bukkit.listeners.MiningListener;
import link.star_dust.MinerTrack.bukkit.hooks.DiscordWebHook;
import link.star_dust.MinerTrack.bukkit.hooks.CustomJsonWebHook;
import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.ViolationBridge;
import link.star_dust.MinerTrack.core.violation.ViolationEngine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.function.Consumer;
import java.util.Set;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;

public class ViolationManager {
    private final MinerTrack plugin;
    private final ViolationEngine engine;
    private final Map<UUID, Object> vlDecayTasks = new HashMap<>();
    private String currentLogFileName;
    private static ViolationManager INSTANCE;

    public ViolationManager(MinerTrack plugin) {
        this.plugin = plugin;
        INSTANCE = this;
        this.currentLogFileName = generateLogFileName();

        this.engine = new ViolationEngine(new ViolationBridge() {
            @Override
            public boolean isLogFileEnabled() {
                return plugin.getConfig().getBoolean("log_file");
            }

            @Override
            public String getLogFormat() {
                return plugin.getLanguageManager().getLogFormat();
            }

            @Override
            public void appendLogLine(String line) {
                String fileName = getLogFileName();
                File logDir = new File(plugin.getDataFolder(), "logs");
                File logFile = new File(logDir, fileName);
                try (FileWriter writer = new FileWriter(logFile, true)) {
                    writer.write(line + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void runConsoleCommand(String command) {
                if (FoliaCheck.isFolia()) {
                    try {
                        RegionScheduler regionScheduler = Bukkit.getRegionScheduler();
                        regionScheduler.run(plugin, null, scheduledTask -> {
                            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                            });
                        });
                    } catch (Throwable t) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    }
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
                logCommand(command);
            }

            @Override
            public Set<UUID> getVerbosePlayers() {
                return plugin.getVerbosePlayers();
            }

            @Override
            public boolean isVerboseConsoleEnabled() {
                return plugin.isVerboseConsoleEnabled();
            }

            @Override
            public void sendMessageToPlayer(UUID playerId, String message) {
                Player p = Bukkit.getPlayer(playerId);
                if (p != null) p.sendMessage(message);
            }

            @Override
            public boolean isWebHookEnabled() {
                return plugin.getConfigManager().WebHookEnable();
            }

            @Override
            public int getWebHookVLRequired() {
                return plugin.getConfigManager().WebHookVLRequired();
            }

            @Override
            public void sendWebhook(UUID playerId, String oreType, int minedVeins, int oreCount, CommonLocation location) {
                Location loc = null;
                if (location != null) {
                    try {
                        org.bukkit.World w = Bukkit.getWorld(location.world);
                        if (w != null) loc = new Location(w, location.x, location.y, location.z);
                    } catch (Exception ignored) {}
                }
                ViolationManager.this.WebHook(playerId, oreType, minedVeins, oreCount, loc);
            }

            @Override
            public Map<String,Object> getConfigSection(String path) {
                Object raw = plugin.getConfig().get(path);
                if (raw instanceof Map) return (Map<String,Object>) raw;
                return new HashMap<>();
            }

            @Override
            public Object getConfig(String path) {
                return plugin.getConfig().get(path);
            }

            @Override
            public String getPrefixedMessage(String key) {
                return plugin.getLanguageManager().getPrefixedMessage(key);
            }

            @Override
            public File getDataFolder() {
                return plugin.getDataFolder();
            }
        });

        int interval = 20 * 60; // Scheduling interval (unit: tick)

        if (FoliaCheck.isFolia()) {
            try {
                Class<?> schedulerClass = Class.forName("org.bukkit.Bukkit");
                Object scheduler = schedulerClass.getMethod("getGlobalRegionScheduler").invoke(null);
                scheduler.getClass().getMethod("runAtFixedRate",
                    Plugin.class,
                    Class.forName("java.util.function.Consumer"),
                    long.class,
                    long.class
                ).invoke(scheduler, plugin, (Consumer<Object>) task -> {
                    try {
                        if (!plugin.isEnabled()) {
                            task.getClass().getMethod("cancel").invoke(task);
                            return;
                        }
                        processVLDecayTasks();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, interval, interval);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!plugin.isEnabled()) {
                        cancel();
                        return;
                    }
                    processVLDecayTasks();
                }
            }.runTaskTimer(plugin, interval, interval);
        }
    }
    
    private String generateLogFileName() {
        LocalDate date = LocalDate.now();
        String formattedDate = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        File logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists() && !logDir.mkdirs()) {
            Bukkit.getLogger().warning("Could not create logs directory for MinerTrack.");
        }

        int index = 1;
        File logFile;

        do {
            logFile = new File(logDir, String.format("%s-%d%s.log", formattedDate, index, getOrdinalSuffix(index)));
            index++;
        } while (logFile.exists());

        return logFile.getName();
    }

    private String getLogFileName() {
        if (currentLogFileName == null) {
            currentLogFileName = generateLogFileName();
        }
        return currentLogFileName;
    }

    private String getOrdinalSuffix(int index) {
        if (index >= 11 && index <= 13) return "th";
        switch (index % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }

    private void logViolation(Player player, int vl, int addVl, String blockType, int count, int vein, Location location) {
        // 保留兼容方法，实际日志写入由 ViolationEngine 通过 bridge.appendLogLine 执行
        if (!plugin.getConfig().getBoolean("log_file")) return;
        String logFormat = plugin.getLanguageManager().getLogFormat();
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "unknown";

        LocalDateTime now = LocalDateTime.now();
        String year = String.valueOf(now.getYear());
        String month = String.format("%02d", now.getMonthValue());
        String day = String.format("%02d", now.getDayOfMonth());
        String hour = String.format("%02d", now.getHour());
        String minute = String.format("%02d", now.getMinute());
        String second = String.format("%02d", now.getSecond());

        String formattedMessage = logFormat
            .replace("%year%", year)
            .replace("%month%", month)
            .replace("%day%", day)
            .replace("%hour%", hour)
            .replace("%minute%", minute)
            .replace("%second%", second)
            .replace("%player%", player.getName())
            .replace("%vl%", String.valueOf(vl))
            .replace("%add_vl%", String.valueOf(addVl))
            .replace("%block_type%", blockType)
            .replace("%count%", String.valueOf(count))
            .replace("%vein_count%", String.valueOf(vein))
            .replace("%world%", worldName)
            .replace("%pos_x%", String.valueOf(location.getBlockX()))
            .replace("%pos_y%", String.valueOf(location.getBlockY()))
            .replace("%pos_z%", String.valueOf(location.getBlockZ()));

        engineAppendLog(formattedMessage);
    }
    
    private void logCommand(String command) {
        if (!plugin.getConfig().getBoolean("log_file")) return;

        String fileName = getLogFileName();
        File logDir = new File(plugin.getDataFolder(), "logs");
        File logFile = new File(logDir, fileName);

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write("Excuted Command: " + command + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Helper used by bridge/engine to append raw log lines
    private void engineAppendLog(String line) {
        String fileName = getLogFileName();
        File logDir = new File(plugin.getDataFolder(), "logs");
        File logFile = new File(logDir, fileName);

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(line + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static int getViolationLevel(UUID uuid) {
        if (INSTANCE == null) return 0;
        return INSTANCE.engine.getViolationLevel(uuid);
    }
    
    public int getViolationLevel(Player player) {
        return engine.getViolationLevel(player.getUniqueId());
    }

    public void increaseViolationLevel(Player player, int increment, String blockType, int count, int vein, Location location) {
        UUID playerId = player.getUniqueId();

        if (vlDecayTasks.containsKey(playerId)) {
            cancelVLDecayTask(playerId);
            vlDecayTasks.remove(playerId);
        }

        scheduleVLDecayTask(player);

        CommonLocation cloc = null;
        if (location != null && location.getWorld() != null) {
            cloc = new CommonLocation(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        engine.increaseViolation(playerId, player.getName(), increment, blockType, count, vein, cloc);
    }
    
    public void processVLDecayTasks() {
        // 将衰减委托给核心引擎
        engine.processDecay();
    }
    
    private void scheduleVLDecayTask(Player player) {
        UUID playerId = player.getUniqueId();

        // 如果任务已存在，则跳过
        if (vlDecayTasks.containsKey(playerId)) {
            return;
        }

        // 仅登记为活跃的衰减目标，实际 VL 状态在引擎中维护
        vlDecayTasks.put(playerId, playerId);
    }
    
    private void cancelVLDecayTask(UUID playerId) {
        /*if (vlDecayTasks.remove(playerId) != null) {
            plugin.getLogger().info("VL decay task canceled for player: " + playerId);
        }*/
        vlDecayTasks.remove(playerId);
    }
    
    private void WebHook(UUID playerId, String oreType, int minedVeins, int ore_count, Location location) {
    	Player player = Bukkit.getPlayer(playerId);
    	if (player == null) {
    		return; // 如果玩家离线则跳过
    	}
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "unknown";

    	// 获取 WebHook 配置项
    	String webHookURL = plugin.getConfigManager().WebHookURL();
        // Debug: log which URL will be used for this webhook invocation
        //plugin.getLogger().info("[Webhook] ViolationManager will use WebHookURL = " + webHookURL);
    	
    	// 如果启用了自定义JSON格式
    	if (plugin.getConfigManager().isCustomJsonEnabled()) {
    		Map<String, String> placeholders = new HashMap<>();
    		placeholders.put("player", player.getName());
    		placeholders.put("player_uuid", player.getUniqueId().toString());
    		placeholders.put("player_vl", String.valueOf(getViolationLevel(player)));
    		placeholders.put("ore_type", oreType);
    		placeholders.put("mined_veins", String.valueOf(minedVeins));
    		placeholders.put("ore_count", String.valueOf(ore_count));
            placeholders.put("world", worldName);
    		placeholders.put("pos_x", String.valueOf(location.getBlockX()));
    		placeholders.put("pos_y", String.valueOf(location.getBlockY()));
    		placeholders.put("pos_z", String.valueOf(location.getBlockZ()));
    		
    		CustomJsonWebHook customWebHook = new CustomJsonWebHook(plugin, webHookURL, plugin.getConfigManager().getCustomJsonFormat());
    		customWebHook.sendMessage(placeholders);
    		return;
    	}

    	// 原有的Discord WebHook逻辑
    	String title = plugin.getConfigManager().WebHookTitle();
    	List<String> textTemplate = plugin.getConfigManager().WebHookText();
    	int color = plugin.getConfigManager().WebHookColor();

    	// 替换占位符
    	List<String> formattedText = new ArrayList<>();
    	for (String line : textTemplate) {
    		formattedText.add(
    				line.replace("%player%", player.getName())
    				.replace("%player_uuid%", player.getUniqueId().toString())
    				.replace("%player_vl%", String.valueOf(getViolationLevel(player)))
    				.replace("%ore_type%", oreType)
    				.replace("%mined_veins%", String.valueOf(minedVeins))
    				.replace("%ore_count%", String.valueOf(ore_count))
                    .replace("%world%", worldName)
    				.replace("%pos_x%", String.valueOf(location.getBlockX()))
    				.replace("%pos_y%", String.valueOf(location.getBlockY()))
    				.replace("%pos_z%", String.valueOf(location.getBlockZ()))
                    .replace("%timestamp%", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
    				);
    	}

    	// 转换为多行字符串
    	String description = String.join("\n", formattedText);

    	// 创建并发送嵌入消息
    	DiscordWebHook discordWebHook = new DiscordWebHook(plugin, webHookURL);
    	DiscordWebHook.Embed embed = new DiscordWebHook.Embed(
    			title,
    			description,
    			color
    			);
    	discordWebHook.sendEmbed(embed);
    }


    public void resetViolationLevel(Player player) {
        UUID playerId = player.getUniqueId();
        engine.resetViolation(playerId);

        // Also clear tracking data related to this player if the listener is available
        try {
            MiningListener listener = plugin.getMiningListener();
            if (listener != null) {
                listener.checkAndResetPaths(playerId);
            }
        } catch (Exception ignored) {
            // If mining listener isn't available or something goes wrong, we still
            // removed the VL; ignore here to avoid crashing.
        }
    }
}
