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
package link.star_dust.MinerTrack.managers;

import link.star_dust.MinerTrack.FoliaCheck;
import link.star_dust.MinerTrack.MinerTrack;
import link.star_dust.MinerTrack.hooks.DiscordWebHook;
import link.star_dust.MinerTrack.hooks.CustomJsonWebHook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public class ViolationManager {
    private final MinerTrack plugin;
    private final static Map<UUID, Integer> violationLevels = new HashMap<>();
    private final Map<UUID, Long> vlZeroTimestamp = new HashMap<>();
    private final Map<UUID, Long> vlChangedTimestamp = new HashMap<>();
    private final Map<UUID, Object> vlDecayTasks = new HashMap<>();

    private String currentLogFileName;

    public ViolationManager(MinerTrack plugin) {
        this.plugin = plugin;
        this.currentLogFileName = generateLogFileName();
        int interval = 20 * 60; // Scheduling interval (unit: tick)

        if (FoliaCheck.isFolia()) {
            // Folia scheduling logic
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                if (!plugin.isEnabled()) {
                    task.cancel();
                    return;
                }
                processVLDecayTasks();
            }, interval, interval);
        } else {
        	Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                processVLDecayTasks();
            }, interval, interval);
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

        String fileName = getLogFileName();
        File logDir = new File(plugin.getDataFolder(), "logs");
        File logFile = new File(logDir, fileName);

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(formattedMessage + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    public static int getViolationLevel(UUID uuid) {
        return violationLevels.getOrDefault(uuid, 0);
    }
    
    public int getViolationLevel(Player player) {
        return violationLevels.getOrDefault(player.getUniqueId(), 0);
    }

    public void increaseViolationLevel(Player player, int increment, String blockType, int count, int vein, Location location) {
    	long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();

        vlZeroTimestamp.remove(playerId);
        
        vlChangedTimestamp.put(playerId, now);

        if (vlDecayTasks.containsKey(playerId)) {
        	cancelVLDecayTask(playerId);
            vlDecayTasks.remove(playerId);
        }

        scheduleVLDecayTask(player);

        int oldLevel = getViolationLevel(playerId);
        int newLevel = oldLevel + increment;
        violationLevels.put(playerId, newLevel);

        // 处理 VL 增加后的其他逻辑，覆盖所有从 oldLevel+1 到 newLevel 的阈值
        for (String key : plugin.getConfig().getConfigurationSection("xray.commands").getKeys(false)) {
            int threshold = Integer.parseInt(key);
            if (threshold > oldLevel && threshold <= newLevel) {
            	String command = plugin.getConfig().getString("xray.commands." + key)
            			.replace("%player%", player.getName());
            	
            	if (FoliaCheck.isFolia()) {
            		RegionScheduler regionScheduler = Bukkit.getRegionScheduler();
            		regionScheduler.run(plugin, location, scheduledTask -> {
            			Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            	            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            	            logCommand(command);
            	        });
            		});
            	} else {
            		Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            		logCommand(command);
            	}
            }
        }

        if (newLevel >= 1) {
            String verboseFormat = plugin.getLanguageManager().getPrefixedMessage("verbose-format");
            String worldName = location.getWorld() != null ? location.getWorld().getName() : "unknown";

            String formattedMessage = verboseFormat
                .replace("%player%", player.getName())
                .replace("%vl%", String.valueOf(newLevel))
                .replace("%add_vl%", String.valueOf(increment))
                .replace("%block_type%", blockType)
                .replace("%count%", String.valueOf(count))
                .replace("%vein_count%", String.valueOf(vein))
                .replace("%world%", worldName)
                .replace("%pos_x%", String.valueOf(location.getBlockX()))
                .replace("%pos_y%", String.valueOf(location.getBlockY()))
                .replace("%pos_z%", String.valueOf(location.getBlockZ()));

            for (UUID uuid : plugin.getVerbosePlayers()) {
                Player verbosePlayer = Bukkit.getPlayer(uuid);
                if (verbosePlayer != null && verbosePlayer.hasPermission("minertrack.verbose")) {
                    verbosePlayer.sendMessage(formattedMessage);
                }
            }

            if (plugin.isVerboseConsoleEnabled()) {
                Bukkit.getConsoleSender().sendMessage(formattedMessage);
            }

            logViolation(player, newLevel, increment, blockType, count, vein, location);
            
            if (plugin.getConfigManager().WebHookEnable() && newLevel >= plugin.getConfigManager().WebHookVLRequired()) {
                WebHook(playerId, blockType, vein, count, location);
            }
        }
    }
    
    public void processVLDecayTasks() {
    	long now = System.currentTimeMillis();

    	for (UUID playerId : new HashSet<>(violationLevels.keySet())) {
    		int currentVL = violationLevels.getOrDefault(playerId, 0);

    		if (currentVL > 0) {
    			long decayIntervalMillis = plugin.getConfig().getInt("xray.decay.interval", 3) * 60 * 1000L;
    			Long lastChangedTime = vlChangedTimestamp.get(playerId);
    			if (lastChangedTime != null && now - lastChangedTime > decayIntervalMillis) {
    				int decayAmount = plugin.getConfig().getInt("xray.decay.amount", 1);
    				double decayFactor = plugin.getConfig().getDouble("xray.decay.factor", 0.9);
    				boolean useFactor = plugin.getConfig().getBoolean("xray.decay.use_factor", false);

    				// 选择线性或非线性衰减
    				int newVL = useFactor
    						? (int) Math.ceil(currentVL * decayFactor)
    								: Math.max(0, currentVL - decayAmount);

    				violationLevels.put(playerId, newVL);
    				vlChangedTimestamp.put(playerId, now);

    				// 如果 VL 归零，记录时间戳
    				if (newVL == 0) {
    					vlZeroTimestamp.put(playerId, now);
    					//plugin.getLogger().info("VL=0 timestamp recorded for player: " + playerId);
    				}
    			}
    		} else {
    			// VL 已经为 0，无需处理
    			vlZeroTimestamp.putIfAbsent(playerId, now);
    		}
    	}
    }
    
    private void scheduleVLDecayTask(Player player) {
        UUID playerId = player.getUniqueId();

        // 如果任务已存在，则跳过
        if (vlDecayTasks.containsKey(playerId)) {
            return;
        }

        // 初始化 VL 和时间戳
        violationLevels.putIfAbsent(playerId, 0);
        vlZeroTimestamp.putIfAbsent(playerId, System.currentTimeMillis());

        // 添加到任务集合中
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

    	// 获取 WebHook 配置项
    	String webHookURL = plugin.getConfigManager().WebHookURL();
    	
    	// 如果启用了自定义JSON格式
    	if (plugin.getConfigManager().isCustomJsonEnabled()) {
    		Map<String, String> placeholders = new HashMap<>();
    		placeholders.put("player", player.getName());
    		placeholders.put("player_uuid", player.getUniqueId().toString());
    		placeholders.put("player_vl", String.valueOf(getViolationLevel(player)));
    		placeholders.put("ore_type", oreType);
    		placeholders.put("mined_veins", String.valueOf(minedVeins));
    		placeholders.put("ore_count", String.valueOf(ore_count));
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
    				.replace("%pos_x%", String.valueOf(location.getBlockX()))
    				.replace("%pos_y%", String.valueOf(location.getBlockY()))
    				.replace("%pos_z%", String.valueOf(location.getBlockZ()))
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
        violationLevels.remove(player.getUniqueId());
    }
}
