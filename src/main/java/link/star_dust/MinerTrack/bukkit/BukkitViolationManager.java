package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.common.CoreWebhookManager;
import link.star_dust.MinerTrack.core.violation.ViolationEngine;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bukkit-side implementation of ViolationManagerBridge.
 * Avoids any dependency on legacy code and delegates core logic to ViolationEngine.
 */
public class BukkitViolationManager implements ViolationManagerBridge {
    private final PluginAdapter adapter;
    private final ViolationEngine engine;
    private final CoreWebhookManager webhookManager;
    private final YamlConfiguration config;
    private final Set<UUID> verbosePlayers = Collections.synchronizedSet(new HashSet<>());
    private BukkitTask decayTask;

    public BukkitViolationManager(PluginAdapter adapter) {
        this.adapter = adapter;
        this.engine = new ViolationEngine(this);
        this.webhookManager = new CoreWebhookManager(this, new BukkitWebhookSender(adapter));

        // load config if present
        YamlConfiguration yc = new YamlConfiguration();
        try {
            File f = new File(adapter.getDataFolder(), "config.yml");
            if (f.exists()) yc.load(f);
        } catch (Exception ignored) {}
        this.config = yc;
    }

    @Override
    public int getViolationLevel(UUID playerId) {
        return engine.getViolationLevel(playerId);
    }

    @Override
    public void increaseViolationLevel(UUID playerId, String playerName, int increment, String blockType, int count, int vein, CommonLocation location) {
        engine.increaseViolation(playerId, playerName, increment, blockType, count, vein, location);
    }

    @Override
    public void processVLDecay() {
        engine.processDecay();
    }

    @Override
    public void scheduleVLDecayTask(UUID playerId) {
        if (decayTask != null) return;
        Object p = adapter.getPlugin();
        if (p instanceof org.bukkit.plugin.Plugin) {
            org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) p;
            // schedule every minute
            decayTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> engine.processDecay(), 20L * 60L, 20L * 60L);
        }
    }

    @Override
    public void cancelVLDecayTask(UUID playerId) {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    @Override
    public boolean isLogFileEnabled() {
        return config.getBoolean("xray.log-file.enabled", false);
    }

    @Override
    public String getLogFormat() {
        return config.getString("xray.log-file.format", "%player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%");
    }

    @Override
    public void appendLogLine(String line) {
        File f = new File(adapter.getDataFolder(), "violations.log");
        try (FileWriter fw = new FileWriter(f, true)) {
            fw.write(line + System.lineSeparator());
        } catch (IOException e) {
            adapter.info("Failed to write violation log: " + e.getMessage());
        }
    }

    @Override
    public void runConsoleCommand(String command) {
        Object p = adapter.getPlugin();
        if (p instanceof org.bukkit.plugin.Plugin) {
            org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) p;
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
        } else {
            adapter.info("Console command (fallback): " + command);
        }
    }

    @Override
    public Set<UUID> getVerbosePlayers() {
        return verbosePlayers;
    }

    @Override
    public boolean isVerboseConsoleEnabled() {
        return config.getBoolean("verbose.console", false);
    }

    @Override
    public void sendMessageToPlayer(UUID playerId, String message) {
        Object obj = adapter.getPlayer(playerId);
        if (obj instanceof org.bukkit.entity.Player) {
            org.bukkit.entity.Player p = (org.bukkit.entity.Player) obj;
            p.sendMessage(adapter.applyColors(message));
        }
    }

    @Override
    public boolean isWebHookEnabled() {
        return config.getBoolean("DiscordWebHook.WebHookEnable", false) || config.getBoolean("DiscordWebHook.enabled", false);
    }

    @Override
    public int getWebHookVLRequired() {
        return config.getInt("DiscordWebHook.VLRequired", Integer.MAX_VALUE);
    }

    @Override
    public void sendWebhook(UUID playerId, String oreType, int minedVeins, int oreCount, CommonLocation location) {
        webhookManager.onViolationIncrease(playerId, oreType, minedVeins, oreCount, location);
    }

    @Override
    public Object getConfigSection(String path) {
        Object o = config.get(path);
        if (o instanceof Map) return o;
        return null;
    }

    @Override
    public Object getConfig(String path) {
        return config.get(path);
    }

    @Override
    public String getPrefixedMessage(String key) {
        String prefix = config.getString("messages.prefix", "[MinerTrack] ");
        return prefix + config.getString("messages." + key, key);
    }

    @Override
    public File getDataFolder() {
        return adapter.getDataFolder();
    }
}
