/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 *
 *  Copyright (c) At87668 (Author87668) <https://github.com/At87668>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package link.star_dust.MinerTrack.neoforge;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** NeoForge ViolationManagerBridge. Mirrors ForgeViolationManager (uses NeoForge tick event). */
public class NeoForgeViolationManager implements ViolationManagerBridge {
    private static volatile NeoForgeViolationManager active;
    public static NeoForgeViolationManager getActive() { return active; }

    private final NeoForgeAdapter adapter;
    private final ViolationEngine engine;
    private CommonYaml config;
    private volatile LanguageBridge languageBridge;
    private final Set<UUID> verbosePlayers = Collections.synchronizedSet(new HashSet<>());
    private final Map<UUID, Long> playerDecayTasks = new ConcurrentHashMap<>();
    private final Set<UUID> resetViolationRecursionGuard = Collections.synchronizedSet(new HashSet<>());
    private String currentLogFileName;
    private long globalDecayIntervalTicks = 20L * 60L * 20L;
    private long lastGlobalDecayRunTick = -1;
    private final Map<UUID, String> playerNameCache = new ConcurrentHashMap<>();

    public NeoForgeViolationManager(NeoForgeAdapter adapter) { this.adapter = adapter; this.engine = new ViolationEngine(this); active = this; this.currentLogFileName = generateLogFileName(); File f = new File(adapter.getDataFolder(), "config.yml"); this.config = ConfigMerger.loadAndMerge(f, "config.yml", adapter, new NeoForgeYamlLoader()); }

    public void scheduleGlobalDecayTask(long decayIntervalTicks) {
        this.globalDecayIntervalTicks = decayIntervalTicks;
        // ServerTickEvent is ABSTRACT (its Post/Pre subclasses are the concrete
        // ones), so registering the abstract class itself makes EventBus throw
        // "Cannot register listeners for abstract ..." and fall through to the
        // raw-consumer path, which fails with "Failed to resolve handler".
        // Register the $Post subclass (fires at the END phase) instead.
        // NeoForge 26.2+ uses ServerTickEvent in the tick package; older
        // NeoForge uses TickEvent$ServerTickEvent (inner class of TickEvent).
        Class<?> serverTickCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.event.tick.ServerTickEvent$Post");
        if (serverTickCls == null) {
            serverTickCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.event.TickEvent$ServerTickEvent$Post");
        }
        if (serverTickCls == null) {
            serverTickCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.event.tick.ServerTickEvent");
        }
        if (serverTickCls == null) {
            serverTickCls = NeoForgeReflection.neoClass("net.neoforged.neoforge.event.TickEvent$ServerTickEvent");
        }
        if (serverTickCls == null) return;
        // The tick phase is exposed as a public final FIELD `phase`
        // (TickEvent.Phase) on NeoForge 1.20.4, as getPhase() on older Forge,
        // and is absent on 26.2+ ServerTickEvent$Post (always END). Reading the
        // `phase` field works on 1.19-1.20.4 and avoids the getPhase() M-MISS;
        // on 26.2+ there is no phase field so we always run (END).
        NeoForgeReflection.registerEventListener(NeoForgeReflection.getMainEventBus(), serverTickCls, rawEvent -> {
            try {
                // If a `phase` field exists (1.19-1.20.4), skip the START phase.
                // 26.2+ ServerTickEvent$Post has no phase field → always END.
                try {
                    Object phase = NeoForgeReflection.getField(rawEvent, "phase");
                    if (phase != null && !phase.toString().contains("END")) return;
                } catch (Throwable t) { /* no phase field → treat as END */ }
                Object server = NeoForgeReflection.getServer();
                if (server == null) return;
                Object tickObj = NeoForgeReflection.callMigrated(server, "getTickCount", "getTicks", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                long tick = tickObj instanceof Number ? ((Number) tickObj).longValue() : 0L;
                if (lastGlobalDecayRunTick < 0 || tick - lastGlobalDecayRunTick >= globalDecayIntervalTicks) {
                    lastGlobalDecayRunTick = tick;
                    try { engine.processDecay(); } catch (Throwable t) { adapter.warning("Global VL decay tick failed: " + t.getMessage()); }
                }
            } catch (Throwable t) {}
        });
    }

    @Override public void cancelAllVLDecayTasks() { playerDecayTasks.clear(); }
    @Override public void cancelGlobalDecayTask() {}
    public void setWebhookEngine(WebhookEngine we) { engine.setWebhookEngine(we); }
    public void setLanguageBridge(LanguageBridge lb) { this.languageBridge = lb; }
    public CommonYaml getMainConfig() { return config; }
    public void reloadConfig() { try { File f = new File(adapter.getDataFolder(), "config.yml"); this.config = ConfigMerger.loadAndMerge(f, "config.yml", adapter, new NeoForgeYamlLoader()); } catch (Exception e) { adapter.info("Failed to reload config: " + e.getMessage()); } }

    @Override public int getViolationLevel(UUID pid) { return engine.getViolationLevel(pid); }
    @Override public void increaseViolationLevel(UUID pid, String pn, int inc, String bt, int count, int vein, CommonLocation loc) { cancelVLDecayTask(pid); engine.increaseViolation(pid, pn, inc, bt, count, vein, loc); scheduleVLDecayTask(pid); if (pn != null) playerNameCache.put(pid, pn); }
    @Override public void processVLDecay() { engine.processDecay(); }
    @Override public void scheduleVLDecayTask(UUID pid) { if (pid != null) playerDecayTasks.putIfAbsent(pid, System.currentTimeMillis()); }
    @Override public void cancelVLDecayTask(UUID pid) { if (pid != null) playerDecayTasks.remove(pid); }
    @Override public boolean isLogFileEnabled() { return config.getBoolean("log_file", false); }
    @Override public String getLogFormat() { if (languageBridge != null) return languageBridge.getLogFormat(); return "%year%-%month%-%day% %hour%-%minute%-%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%"; }
    @Override public void appendLogLine(String line) { writeLogLine(line); }
    @Override public void runConsoleCommand(String command) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return;
            Object cm = NeoForgeReflection.callAny(server, "getCommands", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (cm == null) cm = NeoForgeReflection.callAny(server, "getCommandManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (cm == null) return;
            Object css = NeoForgeReflection.callAny(server, "createCommandSourceStack", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (css == null) return;
            Class<?> cssCls = NeoForgeReflection.forName("net.minecraft.commands.CommandSourceStack");
            if (cssCls == null) cssCls = css.getClass();
            try {
                NeoForgeReflection.callAny(cm, "performPrefixedCommand", new Class<?>[]{cssCls, String.class}, new Object[]{css, command});
            } catch (Throwable t1) {
                try {
                    NeoForgeReflection.callAny(cm, "performCommand", new Class<?>[]{cssCls, String.class}, new Object[]{css, command});
                } catch (Throwable t2) {
                    try {
                        NeoForgeReflection.callAny(cm, "executeWithPrefix", new Class<?>[]{cssCls, String.class}, new Object[]{css, command});
                    } catch (Throwable t3) { /* silent */ }
                }
            }
        } catch (Throwable t) { /* silent */ }
    }
    @Override public Set<UUID> getVerbosePlayers() { return verbosePlayers; }
    private volatile boolean verboseConsole = false;
    @Override public boolean isVerboseConsoleEnabled() { return verboseConsole; }
    @Override public void setVerboseConsoleEnabled(boolean enabled) { this.verboseConsole = enabled; }
    @Override public String getDisplayWorldName(String wk) { return wk; }

    @Override public boolean hasPermission(UUID pid, String node) { try { Object server = NeoForgeReflection.getServer(); if (server == null) return false; Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (pm == null) return false; Object player = NeoForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{pid}); if (player == null) return false; if (NeoForgeCommandBridge.checkNeoForgePermission(player, node)) return true; return NeoForgeCommandBridge.isPlayerOperator(player); } catch (Throwable t) { return false; } }

    @Override public void sendMessageToPlayer(UUID pid, String msg) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return;
            Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (pm == null) return;
            Object player = NeoForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{pid});
            if (player == null) return;
            Object text = NeoForgeReflection.createText(msg);
            if (text == null) return;
            // Name-based routing via NeoForgeCommandBridge.sendFeedback (explicitly finds
            // ServerPlayer sendSystemMessage(Component)); avoids the signature-blind scan
            // that threw InvocationTargetException on NeoForge 1.21.1.
            NeoForgeCommandBridge.sendFeedback(player, text, true);
        } catch (Throwable t) { /* silent */ }
    }

    @Override public Object getConfigSection(String p) { return config.get(p); }
    @Override public Object getConfig(String p) { return config.get(p); }
    @Override public String getPrefixedMessage(String key) { if (languageBridge != null) return languageBridge.getPrefixedMessage(key); return "[" + key + "]"; }
    @Override public File getDataFolder() { return adapter.getDataFolder(); }
    @Override public void resetViolation(UUID pid) { if (!resetViolationRecursionGuard.add(pid)) return; try { engine.resetViolation(pid); } finally { resetViolationRecursionGuard.remove(pid); } }
    @Override public void clearPlayerState(UUID pid) {
        cancelVLDecayTask(pid); verbosePlayers.remove(pid);
        NeoForgeDetectionBridge bridge = NeoForgeDetectionBridge.getActive(); if (bridge != null) bridge.clearPlayerPath(pid);
        resetViolation(pid); playerNameCache.remove(pid);
    }
    @Override public void appendCommandLog(String cmd) { appendLogLine(cmd); }
    @Override public String getPlayerName(UUID pid) { String n = playerNameCache.get(pid); if (n != null) return n; try { Object server = NeoForgeReflection.getServer(); if (server != null) { Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (pm != null) { Object player = NeoForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{pid}); if (player != null) { Object name = NeoForgeReflection.callAny(player, "getName", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); String s = NeoForgeReflection.readString(name); if (s != null) { playerNameCache.put(pid, s); return s; } } } } } catch (Throwable t) {} return pid.toString(); }
    @Override public int getConfigInt(String p, int d) { return config.getInt(p, d); }
    @Override public boolean getConfigBoolean(String p, boolean d) { return config.getBoolean(p, d); }
    @Override public double getConfigDouble(String p, double d) { return config.getDouble(p, d); }

    private void writeLogLine(String line) { try { if (currentLogFileName == null) currentLogFileName = generateLogFileName(); File logDir = new File(adapter.getDataFolder(), "logs"); if (!logDir.exists()) logDir.mkdirs(); File logFile = new File(logDir, currentLogFileName); try (FileWriter fw = new FileWriter(logFile, true)) { fw.write(line + System.lineSeparator()); } } catch (IOException e) { adapter.warning("Failed to write violation log: " + e.getMessage()); } }
    private static String generateLogFileName() { return "violations-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".log"; }
}
