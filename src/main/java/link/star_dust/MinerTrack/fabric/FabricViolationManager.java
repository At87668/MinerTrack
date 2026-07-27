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
 * Fabric ViolationManagerBridge. Mirrors BukkitViolationManager structurally.
 * Uses ServerTickEvents.END_SERVER_TICK for decay (no per-player scheduler on Fabric).
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
    // Recursion guard to prevent infinite loop between bridge and engine
    // (mirrors BukkitViolationManager.resetViolationRecursionGuard)
    private final Set<UUID> resetViolationRecursionGuard = Collections.synchronizedSet(new HashSet<>());
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
            // MC 26.1+: getTickCount(); 1.18-1.21: getTicks()
            Object tickObj = FabricReflection.callMigrated(server, "getTickCount", "getTicks",
                new Class<?>[0], new Object[0]);
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
            Object server = FabricReflection.getServer();
            if (server == null) return;
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return;
            Object player = FabricReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            Object text = createTextComponent(message);
            if (text == null) return;
            FabricReflection.sendMessageToPlayer(player, text);
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
        // Recursion guard: the FIRST call (from command) adds the player
        // and proceeds to call the engine. The engine's callback back
        // into this bridge finds the player already in the set and
        // returns immediately, breaking the cycle.
        if (!resetViolationRecursionGuard.add(playerId)) {
            return;
        }
        try {
            engine.resetViolation(playerId);
        } finally {
            resetViolationRecursionGuard.remove(playerId);
        }
    }

    @Override
    public void clearPlayerState(UUID playerId) {
        // Cancel any pending VL decay task
        cancelVLDecayTask(playerId);
        // Remove from verbose players set
        verbosePlayers.remove(playerId);
        // Clear detection engine state (mining path, air-exposure list, etc.)
        FabricDetectionBridge bridge = FabricDetectionBridge.getActive();
        if (bridge != null) {
            bridge.clearPlayerPath(playerId);
        }
        // Clear VL state via resetViolation (which uses recursion guard)
        resetViolation(playerId);
    }

    @Override
    public void appendCommandLog(String command) {
        if (!isLogFileEnabled()) return;
        try (FileWriter fw = new FileWriter(getLogFile(),
                java.nio.charset.StandardCharsets.UTF_8, true)) {
            fw.write("Executed Command: " + command + "\n");
        } catch (IOException e) {
            adapter.warning("Failed to write command log: " + e.getMessage());
        }
    }

    @Override
    public String getPlayerName(UUID playerId) {
        try {
            Object server = FabricReflection.getServer();
            if (server == null) return playerId.toString();
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return playerId.toString();
            Object player = FabricReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return playerId.toString();
            // MC 26.1+: Entity.getName() returns Component; use readString to unwrap.
            Object name = FabricReflection.callAny(player, "getName", new Class<?>[0], new Object[0]);
            String s = FabricReflection.readString(name);
            if (s == null) return playerId.toString();
            return s;
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
            Object server = FabricReflection.getServer();
            if (server == null) return;
            // MC 1.18.2+: getCommands() — same name on ALL versions
            Object cmdManager = FabricReflection.callAny(server, "getCommands",
                new Class<?>[0], new Object[0]);
            if (cmdManager == null) {
                // Legacy fallback
                cmdManager = FabricReflection.callAny(server, "getCommandManager",
                    new Class<?>[0], new Object[0]);
            }
            if (cmdManager == null) return;
            // createCommandSourceStack() exists on both 1.18.2 and 1.21.1
            Object source = FabricReflection.callAny(server, "createCommandSourceStack",
                new Class<?>[0], new Object[0]);
            if (source == null) return;
            // withSuppressedOutput() exists on both 1.18.2 and 1.21.1
            Object suppressed = FabricReflection.callAny(source, "withSuppressedOutput",
                new Class<?>[0], new Object[0]);
            if (suppressed == null) suppressed = source;
            Class<?> cssCls = FabricReflection.forName("net.minecraft.commands.CommandSourceStack");
            if (cssCls == null) return;
            // 1.21.1+: performPrefixedCommand; 1.18.2: performCommand
            try {
                FabricReflection.callAny(cmdManager, "performPrefixedCommand",
                    new Class<?>[]{cssCls, String.class},
                    new Object[]{suppressed, command});
            } catch (Throwable t1) {
                try {
                    FabricReflection.callAny(cmdManager, "performCommand",
                        new Class<?>[]{cssCls, String.class},
                        new Object[]{suppressed, command});
                } catch (Throwable t2) {
                    FabricReflection.callAny(cmdManager, "executeWithPrefix",
                        new Class<?>[]{cssCls, String.class},
                        new Object[]{suppressed, command});
                }
            }
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

    // ── Text/Component helpers (delegate to FabricReflection) ──────

    private static Object createTextComponent(String message) {
        return FabricReflection.createText(message);
    }

    private static Class<?> resolveTextComponentClass() {
        return FabricReflection.resolveTextComponentClass();
    }
}
