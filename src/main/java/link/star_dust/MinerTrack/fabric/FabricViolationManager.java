package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.config.ConfigMerger;
import link.star_dust.MinerTrack.core.violation.ViolationEngine;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric implementation of {@link ViolationManagerBridge}.
 *
 * <p>Mirrors {@code BukkitViolationManager} structurally: holds a
 * per-player VL map, a {@link ViolationEngine} that owns the
 * scheduling, and a small per-platform layer for I/O (log files,
 * verbose-player set, decay scheduling).
 *
 * <p>Scheduling uses Fabric API's
 * {@link ServerTickEvents#END_SERVER_TICK} for the global decay
 * tick. Per-player decay is fanned out from the same global tick
 * — Fabric does not have an equivalent of Bukkit's per-player
 * {@code BukkitTask}, so we mark which players have a pending
 * per-player decay and run the engine's processDecay on them
 * inside the same tick.
 *
 * <p>All {@code net.minecraft.*} access (player lookups, command
 * dispatch, sending messages) goes through
 * {@link FabricReflection} so the file compiles on a non-Fabric
 * classpath; the lookups succeed at runtime when the Fabric
 * loader is in play.
 */
public class FabricViolationManager implements ViolationManagerBridge {
    private static volatile FabricViolationManager active;
    public static FabricViolationManager getActive() { return active; }

    private final FabricAdapter adapter;
    private final ViolationEngine engine;
    private CommonYaml config;
    private volatile LanguageBridge languageBridge;
    private final Set<UUID> verbosePlayers = Collections.synchronizedSet(new HashSet<>());
    private final Map<UUID, Long> playerDecayTasks = new ConcurrentHashMap<>();
    private String currentLogFileName;
    private long globalDecayIntervalTicks = 20L * 60L * 20L; // 20 minutes; overridden on first tick
    private long lastGlobalDecayRunTick = -1;

    public FabricViolationManager(FabricAdapter adapter) {
        this.adapter = adapter;
        this.engine = new ViolationEngine(this);
        active = this;
        this.currentLogFileName = generateLogFileName();

        // Use the platform-agnostic merger so missing keys are added
        // and stale user files are upgraded, exactly like the Bukkit
        // path.
        File f = new File(adapter.getDataFolder(), "config.yml");
        this.config = ConfigMerger.loadAndMerge(f, "config.yml", adapter, new FabricYamlLoader());
    }

    /**
     * Register the global decay tick handler. Called by
     * {@code FabricPlatform#onInitializeServer} after the violation
     * manager is fully constructed. Uses Fabric API's
     * {@link ServerTickEvents#END_SERVER_TICK} so the timer fires
     * once per server tick on the main thread, then we gate the
     * actual decay on a wall-clock interval that mirrors the Bukkit
     * implementation.
     */
    public void scheduleGlobalDecayTask(long decayIntervalTicks) {
        this.globalDecayIntervalTicks = decayIntervalTicks;
        FabricEventBus.registerEndServerTick(server -> {
            Object tickObj = FabricReflection.call(server, "getTicks", new Class<?>[0], new Object[0]);
            long tick = tickObj instanceof Number ? ((Number) tickObj).longValue() : 0L;
            if (lastGlobalDecayRunTick < 0
                    || tick - lastGlobalDecayRunTick >= decayIntervalTicks) {
                lastGlobalDecayRunTick = tick;
                try {
                    engine.processDecay();
                } catch (Throwable t) {
                    adapter.warning("Global VL decay tick failed: " + t.getMessage());
                }
            }
        });
    }

    public void cancelAllVLDecayTasks() {
        playerDecayTasks.clear();
    }

    public void cancelGlobalDecayTask() {
        // No global handle on Fabric (ServerTickEvents.END_SERVER_TICK
        // holds a registered callback that we don't track). The
        // callback runs until the server stops, which is fine for
        // a server-side mod — there's no plugin reload here.
    }

    public void setWebhookEngine(WebhookEngine webhookEngine) {
        engine.setWebhookEngine(webhookEngine);
    }

    public void setLanguageBridge(LanguageBridge languageBridge) {
        this.languageBridge = languageBridge;
    }

    public CommonYaml getMainConfig() {
        return config;
    }

    public void reloadConfig() {
        try {
            File f = new File(adapter.getDataFolder(), "config.yml");
            this.config = ConfigMerger.loadAndMerge(f, "config.yml", adapter, new FabricYamlLoader());
        } catch (Exception e) {
            adapter.info("Failed to reload config: " + e.getMessage());
        }
    }

    // ── ViolationManagerBridge surface ───────────────────────────────

    @Override
    public int getViolationLevel(UUID playerId) {
        return engine.getViolationLevel(playerId);
    }

    @Override
    public void increaseViolationLevel(UUID playerId, String playerName, int increment, String blockType, int count, int vein, CommonLocation location) {
        cancelVLDecayTask(playerId);
        engine.increaseViolation(playerId, playerName, increment, blockType, count, vein, location);
        scheduleVLDecayTask(playerId);
    }

    @Override
    public void processVLDecay() {
        engine.processDecay();
    }

    @Override
    public void scheduleVLDecayTask(UUID playerId) {
        if (playerDecayTasks.containsKey(playerId)) return;
        // Mark the player as "has a pending decay" so the global
        // tick handler knows to consult the engine for them. The
        // engine reads xray.decay.interval itself.
        playerDecayTasks.put(playerId, System.currentTimeMillis());
    }

    @Override
    public void cancelVLDecayTask(UUID playerId) {
        if (playerId == null) return;
        playerDecayTasks.remove(playerId);
    }

    @Override
    public boolean isLogFileEnabled() {
        return config.getBoolean("log_file", false);
    }

    @Override
    public String getLogFormat() {
        if (languageBridge != null) return languageBridge.getLogFormat();
        return "%year%-%month%-%day% %hour%-%minute%-%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%";
    }

    @Override
    public String getDisplayWorldName(String worldKey) {
        // On Fabric the world key IS already a canonical id (the
        // detection bridge returns {@code minecraft:overworld} for
        // the overworld). Identity transform — the default
        // implementation in the interface returns the key
        // unchanged, which is correct here.
        return worldKey;
    }

    @Override
    public String getPrefixedMessage(String key) {
        if (languageBridge != null) return languageBridge.getPrefixedMessage(key);
        return "[" + key + "]";
    }

    @Override
    public Set<UUID> getVerbosePlayers() {
        return verbosePlayers;
    }

    @Override
    public boolean isVerboseConsoleEnabled() {
        return false;
    }

    @Override
    public void sendMessageToPlayer(UUID playerId, String message) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
            if (pm == null) return;
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            Object text = FabricReflection.callStatic("net.minecraft.text.Text",
                "literal", new Class<?>[]{String.class}, new Object[]{message});
            // sendMessage(Text, boolean overlay) on the player
            // (1.18+ signature; 1.20+ is identical).
            FabricReflection.call(player, "sendMessage",
                new Class<?>[]{FabricReflection.forName("net.minecraft.text.Text"), boolean.class},
                new Object[]{text, false});
        } catch (Throwable t) {
            // Player likely offline; silently drop.
        }
    }

    @Override
    public Object getConfigSection(String path) {
        return config.get(path);
    }

    @Override
    public Object getConfig(String path) {
        return config.get(path);
    }

    @Override
    public java.io.File getDataFolder() {
        return adapter.getDataFolder();
    }

    @Override
    public void resetViolation(UUID playerId) {
        cancelVLDecayTask(playerId);
    }

    @Override
    public void clearPlayerState(UUID playerId) {
        cancelVLDecayTask(playerId);
    }

    @Override
    public void appendCommandLog(String command) {
        // No console log file on Fabric; the server log already
        // records the dispatched command.
    }

    @Override
    public String getPlayerName(UUID playerId) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return playerId.toString();
            Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
            if (pm == null) return playerId.toString();
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return playerId.toString();
            Object name = FabricReflection.callAny(player, "getName", new Class<?>[0], new Object[0]);
            if (name == null) return playerId.toString();
            return name.toString();
        } catch (Throwable t) {
            return playerId.toString();
        }
    }

    @Override
    public int getConfigInt(String path, int def) { return config.getInt(path, def); }

    @Override
    public boolean getConfigBoolean(String path, boolean def) { return config.getBoolean(path, def); }

    @Override
    public double getConfigDouble(String path, double def) { return config.getDouble(path, def); }

    @Override
    public void runConsoleCommand(String command) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            Object cmdManager = FabricReflection.callAny(server, "getCommandManager", new Class<?>[0], new Object[0]);
            if (cmdManager == null) return;
            // Server's command source (the "console" with full
            // permission). On 1.18-1.21, MinecraftServer exposes
            // {@code getCommandSource()} or
            // {@code getCommandSource().withSilent()}.
            Object source = FabricReflection.callAny(server, "getCommandSource", new Class<?>[0], new Object[0]);
            if (source == null) return;
            Object withSilent = FabricReflection.callAny(source, "withSilent", new Class<?>[0], new Object[0]);
            if (withSilent == null) withSilent = source;
            FabricReflection.callAny(cmdManager, "executeWithPrefix",
                new Class<?>[]{FabricReflection.forName("net.minecraft.server.command.ServerCommandSource"), String.class},
                new Object[]{withSilent, command});
        } catch (Throwable t) {
            adapter.warning("Failed to execute command '" + command + "': " + t.getMessage());
        }
    }

    @Override
    public void appendLogLine(String line) {
        if (!isLogFileEnabled()) return;
        File f = getLogFile();
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line + System.lineSeparator());
        } catch (IOException e) {
            adapter.warning("Failed to write log line: " + e.getMessage());
        }
    }

    // ── Internals ────────────────────────────────────────────────────

    private File getLogFile() {
        File logDir = new File(adapter.getDataFolder(), "logs");
        if (!logDir.exists()) //noinspection ResultOfMethodCallIgnored
            logDir.mkdirs();
        return new File(logDir, currentLogFileName);
    }

    private String generateLogFileName() {
        LocalDate date = LocalDate.now();
        String formattedDate = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        File logDir = new File(adapter.getDataFolder(), "logs");
        if (!logDir.exists()) //noinspection ResultOfMethodCallIgnored
            logDir.mkdirs();
        int index = 1;
        File logFile;
        do {
            logFile = new File(logDir, String.format("%s-%d%s.log", formattedDate, index, getOrdinalSuffix(index)));
            index++;
        } while (logFile.exists());
        return logFile.getName();
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
}
