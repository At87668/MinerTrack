package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.common.PluginAdapter;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.violation.ViolationEngine;
import org.bukkit.scheduler.BukkitTask;

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
import java.util.function.Consumer;

/**
 * Bukkit-side implementation of ViolationManagerBridge.
 * Delegates core logic to ViolationEngine; handles platform-specific I/O and scheduling.
 */
public class BukkitViolationManager implements ViolationManagerBridge {
    // Static reference so the PluginAdapter can call back into the active
    // manager during reloadConfig() (e.g. to refresh the webhook engine
    // against the freshly-merged config) without depending on
    // ServicesManager ceremony. Mirrors the pattern used by
    // BukkitDetectionBridge#getActive().
    private static volatile BukkitViolationManager active;
    public static BukkitViolationManager getActive() { return active; }

    private final PluginAdapter adapter;
    private final ViolationEngine engine;
    private CommonYaml config;
    /**
     * The platform attaches its language bridge after construction
     * (the violation manager is created before the language bridge in
     * {@link BukkitPlatform#onEnable()} so we can't take it in the
     * constructor). All message rendering — verbose format, log
     * format, prefixed messages — delegates to this bridge once it
     * has been set. {@code null} before {@link #setLanguageBridge} is
     * called; the engine still functions in that window but rendering
     * of user-visible strings falls back to the YAML-key default.
     */
    private volatile LanguageBridge languageBridge;
    private final Set<UUID> verbosePlayers = Collections.synchronizedSet(new HashSet<>());
    private BukkitTask decayTask;
    private String currentLogFileName;
    private final Map<UUID, BukkitTask> playerDecayTasks = new ConcurrentHashMap<>();
    // Tracks the in-flight `resetViolation` round-trip: the
    // engine calls back into the bridge to notify it of a
    // reset, the bridge forwards to the engine to actually
    // clear state, the engine notifies the bridge again —
    // and the second notification must be a no-op or the JVM
    // stack overflows (see the user-reported
    // StackOverflowError from `/mt reset <player>`). Adding
    // the player UUID to this set before the engine call, and
    // checking it at entry to the bridge method, breaks the
    // cycle. A `Set` (not a `boolean`) is used because the
    // command path can call `resetViolation` for many players
    // in quick succession, and a single shared flag would
    // mis-classify concurrent unrelated resets.
    private final Set<UUID> resetViolationRecursionGuard = Collections.synchronizedSet(new HashSet<>());

    public BukkitViolationManager(PluginAdapter adapter) {
        this.adapter = adapter;
        this.engine = new ViolationEngine(this);
        active = this;
        this.currentLogFileName = generateLogFileName();

        // Use the platform-agnostic merger so missing keys are added and
        // stale user files are upgraded.
        File f = new File(adapter.getDataFolder(), "config.yml");
        this.config = link.star_dust.MinerTrack.core.config.ConfigMerger.loadAndMerge(
                f, "config.yml", adapter, new BukkitYamlLoader());
    }

    /**
     * Schedule the global VL decay task.
     * Called by BukkitPlatform after plugin enable, once the server world is ready.
     * @param decayIntervalTicks interval in ticks (converted from minutes in calling code)
     */
    public void scheduleGlobalDecayTask(long decayIntervalTicks) {
        Object p = adapter.getPlugin();
        if (!(p instanceof org.bukkit.plugin.Plugin)) return;
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) p;

        if (isFolia()) {
            try {
                Class<?> schedulerClass = Class.forName("org.bukkit.Bukkit");
                Object scheduler = schedulerClass.getMethod("getGlobalRegionScheduler").invoke(null);
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                scheduler.getClass().getMethod("runAtFixedRate",
                    org.bukkit.plugin.Plugin.class, consumerClass, long.class, long.class)
                    .invoke(scheduler, plugin, (Consumer<Object>) task -> {
                        try {
                            if (!plugin.isEnabled()) {
                                task.getClass().getMethod("cancel").invoke(task);
                                return;
                            }
                            engine.processDecay();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, decayIntervalTicks, decayIntervalTicks);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            decayTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> engine.processDecay(), decayIntervalTicks, decayIntervalTicks);
        }
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String generateLogFileName() {
        LocalDate date = LocalDate.now();
        String formattedDate = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        File logDir = new File(adapter.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
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

    private File getLogFile() {
        File logDir = new File(adapter.getDataFolder(), "logs");
        return new File(logDir, getLogFileName());
    }

    private String getLogFileName() {
        if (currentLogFileName == null) currentLogFileName = generateLogFileName();
        return currentLogFileName;
    }

    @Override
    public int getViolationLevel(UUID playerId) {
        return engine.getViolationLevel(playerId);
    }

    @Override
    public void increaseViolationLevel(UUID playerId, String playerName, int increment, String blockType, int count, int vein, CommonLocation location) {
        // Cancel existing per-player decay task on new violation
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
        Object pluginObj = adapter.getPlugin();
        if (!(pluginObj instanceof org.bukkit.plugin.Plugin)) return;
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) pluginObj;
        int interval = config.getInt("xray.decay.interval", 3) * 20 * 60;

        if (isFolia()) {
            try {
                Class<?> schedulerClass = Class.forName("org.bukkit.Bukkit");
                Object scheduler = schedulerClass.getMethod("getGlobalRegionScheduler").invoke(null);
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                Object task = scheduler.getClass().getMethod("runAtFixedRate",
                    org.bukkit.plugin.Plugin.class, consumerClass, long.class, long.class)
                    .invoke(scheduler, plugin, (Consumer<Object>) t -> {
                        try {
                            if (!plugin.isEnabled()) {
                                t.getClass().getMethod("cancel").invoke(t);
                                playerDecayTasks.remove(playerId);
                                return;
                            }
                            engine.processDecay();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, interval, interval);
                // Folia tasks don't return a BukkitTask we can cancel per-player
                // The global decay task handles all players; per-player tracking is still maintained
                // in the playerDecayTasks map for reference but we don't store a cancelable handle
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> engine.processDecay(), interval, interval);
            playerDecayTasks.put(playerId, task);
        }
    }

    @Override
    public void cancelVLDecayTask(UUID playerId) {
        if (playerId == null) return;
        BukkitTask task = playerDecayTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    @Override
    public void cancelAllVLDecayTasks() {
        playerDecayTasks.values().forEach(t -> { if (t != null) t.cancel(); });
        playerDecayTasks.clear();
    }

    @Override
    public void cancelGlobalDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    @Override
    public boolean isLogFileEnabled() {
        return config.getBoolean("log_file", false);
    }

    @Override
    public String getLogFormat() {
        // The log format lives in `language.yml` under the top-level
        // `log-format` key, NOT under `config.yml` → `language.log-format`
        // (which was a v1 quirk; v2 reads everything from the
        // LanguageBridge). Delegate to the bridge so admins editing
        // the language file see their changes take effect.
        if (languageBridge != null) {
            // BukkitLanguageBridge.getLogFormat() already applies
            // getString("log-format", <default>) so we don't need to
            // pass a default here.
            return languageBridge.getLogFormat();
        }
        return "%year%-%month%-%day% %hour%:%minute%:%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%";
    }

    @Override
    public String getDisplayWorldName(String worldKey) {
        // The world key is already a canonical Minecraft
        // dimension id at this point (the Bukkit listener
        // resolves folder names through
        // `BukkitDetectionBridge.resolveDimensionId` BEFORE
        // they reach the core layer). We forward to the
        // detection bridge anyway for the rare case where a
        // platform-level transformation is wanted (e.g. a
        // localised display name); the current Bukkit
        // implementation is idempotent — it returns the
        // canonical id unchanged.
        //
        // Static `BukkitDetectionBridge.getActive()` handle
        // rather than constructor injection to avoid a
        // chicken-and-egg ordering issue: this class is
        // constructed BEFORE the detection bridge in
        // `BukkitPlatform.onEnable`, and we want the
        // `getActive` lookup to happen lazily on the first VL
        // increase, when the bridge has already registered
        // itself.
        BukkitDetectionBridge bridge = BukkitDetectionBridge.getActive();
        if (bridge == null) {
            return worldKey;
        }
        return bridge.getDisplayDimensionId(worldKey);
    }

    @Override
    public String getPrefixedMessage(String key) {
        // v2 stores every user-visible string under its top-level
        // `language.yml` key (e.g. `verbose-format`, `kick-format`,
        // `no-permission`). The previous implementation read
        // `messages.<key>` from `config.yml`, which doesn't exist in
        // v2 and silently returned the key literal. Route the call
        // through the LanguageBridge so the prefix + colour pipeline
        // is consistent with the /mtrack command path.
        if (languageBridge != null) {
            return languageBridge.getPrefixedMessage(key);
        }
        return key;
    }

    /**
     * Inject the platform's language bridge. Called by
     * {@link BukkitPlatform} after the bridge has been constructed so
     * this class can delegate message rendering to it. Subsequent
     * calls replace the previous bridge (used during /mtrack reload).
     */
    public void setLanguageBridge(LanguageBridge languageBridge) {
        this.languageBridge = languageBridge;
    }

    /**
     * Wire (or replace) the webhook engine that
     * {@link link.star_dust.MinerTrack.core.violation.ViolationEngine}
     * dispatches to after every VL increase. Called by
     * {@link BukkitPlatform} at startup with the platform's
     * {@link BukkitWebhookSender} and a freshly-built
     * {@link WebhookConfig}, and again on every {@code /minertrack
     * reload} so the engine picks up edits to {@code DiscordWebHook.*}
     * without a server restart.
     */
    public void setWebhookEngine(link.star_dust.MinerTrack.core.violation.WebhookEngine webhookEngine) {
        this.engine.setWebhookEngine(webhookEngine);
    }

    /**
     * Direct read-only access to the merged main config snapshot.
     * Used by the platform at startup (and on every reload) to build
     * a fresh {@link WebhookConfig} without round-tripping through the
     * private {@code config} field.
     */
    public CommonYaml getMainConfig() {
        return config;
    }

    @Override
    public void appendLogLine(String line) {
        if (!isLogFileEnabled()) return;
        try (FileWriter fw = new FileWriter(getLogFile(),
                java.nio.charset.StandardCharsets.UTF_8, true)) {
            fw.write(line + "\n");
        } catch (IOException e) {
            adapter.info("Failed to write violation log: " + e.getMessage());
        }
    }

    @Override
    public void appendCommandLog(String command) {
        if (!isLogFileEnabled()) return;
        try (FileWriter fw = new FileWriter(getLogFile(),
                java.nio.charset.StandardCharsets.UTF_8, true)) {
            fw.write("Executed Command: " + command + "\n");
        } catch (IOException e) {
            adapter.info("Failed to write command log: " + e.getMessage());
        }
    }

    @Override
    public void runConsoleCommand(String command) {
        Object p = adapter.getPlugin();
        if (p instanceof org.bukkit.plugin.Plugin) {
            org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) p;
            if (isFolia()) {
                // On Folia, the global console sender must be invoked
                // from a global-region thread; otherwise
                // Server#dispatchCommand either throws
                // IllegalStateException ("dispatchCommand can only be
                // called from a global region task") or silently
                // no-ops. Detection events arrive on a region thread
                // (the thread owning the broken block's chunk), so we
                // must hop to the global region scheduler before
                // dispatching. Mirrors v1's
                // `Bukkit.getGlobalRegionScheduler().execute(...)`
                // wrapper in
                // `legacy/managers/ViolationManager.java`.
                try {
                    Class<?> schedulerClass = Class.forName("org.bukkit.Bukkit");
                    Object scheduler = schedulerClass.getMethod("getGlobalRegionScheduler").invoke(null);
                    scheduler.getClass().getMethod("execute", org.bukkit.plugin.Plugin.class, java.lang.Runnable.class)
                        .invoke(scheduler, plugin, (java.lang.Runnable) () ->
                            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command));
                } catch (Exception e) {
                    // Fall through to the direct dispatch below if the
                    // reflective GlobalRegionScheduler lookup fails
                    // (e.g. on a server that has Bukkit but not Folia
                    // shaded in the same classpath). The direct call
                    // is what non-Folia servers do anyway.
                    adapter.info("Folia GlobalRegionScheduler execute failed for `"
                        + command + "`: " + e.getMessage());
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
                }
            } else {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
            }
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
        // v1 stored a `verbose.console` boolean in config.yml. v2
        // dropped this setting: verbose output is now opt-in per
        // player via the `minertrack.verbose` permission (toggleable
        // with `/mtrack verbose`). Always return false here so the
        // engine never spams the console, matching the new behaviour.
        return false;
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
    public int getConfigInt(String path, int def) {
        return config.getInt(path, def);
    }

    @Override
    public boolean getConfigBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    @Override
    public double getConfigDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    @Override
    public File getDataFolder() {
        return adapter.getDataFolder();
    }

    @Override
    public void resetViolation(UUID playerId) {
        // The VL state for a player lives entirely inside the
        // `ViolationEngine` (violationLevels, vlChangedTimestamp,
        // vlZeroTimestamp maps). The platform-specific bridge
        // (`BukkitViolationManager`) doesn't keep its own copy
        // of the VL counter, so on the Bukkit path the
        // `resetViolation` notification from the engine is a
        // no-op. We DO need to forward to the engine when the
        // call originates from a command (`/mt reset <player>`)
        // rather than from the engine itself, otherwise the
        // VL state wouldn't be cleared. The guard set
        // distinguishes the two cases: the FIRST entry to this
        // method (the command) marks the player in the set and
        // proceeds to call the engine; the engine's subsequent
        // call back into this bridge finds the player already
        // in the set and returns immediately, breaking the
        // cycle. A `Set` (not a `boolean`) is used because
        // the command path can call `resetViolation` for many
        // players in quick succession, and a single shared
        // flag would mis-classify concurrent unrelated resets.
        //
        // `Set.add` returns `true` when the element was NOT
        // already in the set (i.e. this is the first /
        // outermost call), and `false` when the element WAS
        // already there (i.e. the engine is recursing back into
        // the bridge — short-circuit to break the cycle). The
        // previous implementation inverted this check and
        // dropped the early return, so the command path's
        // first call would short-circuit and never reach
        // `engine.resetViolation` — `/mt reset` would print
        // success but leave the VL state intact, and on the
        // NEXT call (after the engine had already cleaned up
        // state) the command would short-circuit again,
        // producing the StackOverflowError visible in the
        // user-reported stack trace.
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
        // Bukkit-specific extras: cancel any scheduled per-
        // player decay task, and forget the player from the
        // verbose-listener set. The VL state itself is
        // cleared via `resetViolation` (which guards the
        // bridge↔engine round trip with the recursion set
        // — see `resetViolation` for the full rationale).
        //
        // We ALSO drop the detection-engine state for the
        // player (mining path, air-exposure list, vein cluster
        // maps, last-vein-location, mined-vein-count and
        // vlZeroTimestamp) by reaching the active
        // `BukkitDetectionBridge` and calling
        // `clearPlayerPath`. Without this an admin `reset`
        // only zeroes the VL counter, but the next rare-ore
        // break re-evaluates the (long, pre-existing) path
        // list — the same `smoothPath` / `isNewVein` / vein-
        // count decision the player just got reset for — and
        // can push VL right back up. The bridge may be null
        // in theory if a reset fires before
        // `BukkitPlatform.onEnable` finishes wiring it up
        // (which can't happen, the command executor isn't
        // registered until enable completes), but we guard
        // for it anyway.
        cancelVLDecayTask(playerId);
        verbosePlayers.remove(playerId);
        BukkitDetectionBridge bridge = BukkitDetectionBridge.getActive();
        if (bridge != null) {
            bridge.clearPlayerPath(playerId);
        }
        resetViolation(playerId);
    }

    @Override
    public String getPlayerName(UUID playerId) {
        Object obj = adapter.getPlayer(playerId);
        if (obj instanceof org.bukkit.entity.Player) {
            return ((org.bukkit.entity.Player) obj).getName();
        }
        return playerId.toString();
    }

    /**
     * Re-read the main config from disk and re-merge it with the JAR
     * defaults. Called by the {@code /minertrack reload} command so the
     * violation manager picks up edits to {@code config.yml} without a
     * server restart. Group configs are refreshed through the active
     * {@link BukkitDetectionBridge#loadGroupConfigs()} by
     * {@link BukkitAdapter#reloadConfig()}.
     */
    public void reloadConfig() {
        try {
            File f = new File(adapter.getDataFolder(), "config.yml");
            this.config = link.star_dust.MinerTrack.core.config.ConfigMerger.loadAndMerge(
                    f, "config.yml", adapter, new BukkitYamlLoader());
        } catch (Exception e) {
            adapter.info("Failed to reload config.yml in violation manager: " + e.getMessage());
        }
    }
}
