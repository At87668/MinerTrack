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

package link.star_dust.MinerTrack.forge;

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
 * Forge ViolationManagerBridge. Uses Forge tick event for decay.
 */
public class ForgeViolationManager implements ViolationManagerBridge {
    private static volatile ForgeViolationManager active;
    public static ForgeViolationManager getActive() { return active; }

    private final ForgeAdapter adapter;
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

    public ForgeViolationManager(ForgeAdapter adapter) {
        this.adapter = adapter;
        this.engine = new ViolationEngine(this);
        active = this;
        this.currentLogFileName = generateLogFileName();
        File f = new File(adapter.getDataFolder(), "config.yml");
        this.config = ConfigMerger.loadAndMerge(f, "config.yml", adapter, new ForgeYamlLoader());
    }

    public void scheduleGlobalDecayTask(long decayIntervalTicks) {
        this.globalDecayIntervalTicks = decayIntervalTicks;
        ForgeReflection.registerEventListener(
            ForgeReflection.getMainEventBus(),
            ForgeReflection.forgeClass("net.minecraftforge.event.TickEvent$ServerTickEvent"),
            rawEvent -> {
                try {
                    Object phase = ForgeReflection.callAny(rawEvent, "getPhase", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    if (phase == null || !phase.toString().contains("END")) return;
                    Object server = ForgeReflection.getServer(); if (server == null) return;
                    Object tickObj = ForgeReflection.callMigrated(server, "getTickCount", "getTicks", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    long tick = tickObj instanceof Number ? ((Number) tickObj).longValue() : 0L;
                    if (lastGlobalDecayRunTick < 0 || tick - lastGlobalDecayRunTick >= globalDecayIntervalTicks) {
                        lastGlobalDecayRunTick = tick; try { engine.processDecay(); } catch (Throwable t) { adapter.warning("Global VL decay tick failed: " + t.getMessage()); }
                    }
                } catch (Throwable t) {}
            });
    }

    @Override public void cancelAllVLDecayTasks() { playerDecayTasks.clear(); }
    @Override public void cancelGlobalDecayTask() {}
    public void setWebhookEngine(WebhookEngine we) { engine.setWebhookEngine(we); }
    public void setLanguageBridge(LanguageBridge lb) { this.languageBridge = lb; }
    public CommonYaml getMainConfig() { return config; }

    public void reloadConfig() { try { File f = new File(adapter.getDataFolder(), "config.yml"); this.config = ConfigMerger.loadAndMerge(f, "config.yml", adapter, new ForgeYamlLoader()); } catch (Exception e) { adapter.info("Failed to reload config: " + e.getMessage()); } }

    @Override public int getViolationLevel(UUID pid) { return engine.getViolationLevel(pid); }
    @Override public void increaseViolationLevel(UUID pid, String pn, int inc, String bt, int count, int vein, CommonLocation loc) { cancelVLDecayTask(pid); engine.increaseViolation(pid, pn, inc, bt, count, vein, loc); scheduleVLDecayTask(pid); if (pn != null) playerNameCache.put(pid, pn); }
    @Override public void processVLDecay() { engine.processDecay(); }
    @Override public void scheduleVLDecayTask(UUID pid) { if (pid != null) playerDecayTasks.putIfAbsent(pid, System.currentTimeMillis()); }
    @Override public void cancelVLDecayTask(UUID pid) { if (pid != null) playerDecayTasks.remove(pid); }
    @Override public boolean isLogFileEnabled() { return config.getBoolean("log_file", false); }
    @Override public String getLogFormat() { if (languageBridge != null) return languageBridge.getLogFormat(); return "%year%-%month%-%day% %hour%-%minute%-%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%"; }
    @Override public void appendLogLine(String line) { writeLogLine(line); }
    @Override public void runConsoleCommand(String command) { try { Object server = ForgeReflection.getServer(); if (server == null) return; Object cm = ForgeReflection.callAny(server, "getCommands", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (cm == null) cm = ForgeReflection.callAny(server, "getCommandManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (cm == null) return; Object css = ForgeReflection.callAny(server, "createCommandSourceStack", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (css == null) return; Class<?> cssCls = css.getClass(); try { ForgeReflection.callAny(cm, "performPrefixedCommand", new Class<?>[]{cssCls, String.class}, new Object[]{css, command}); } catch (Throwable t1) { try { ForgeReflection.callAny(cm, "performCommand", new Class<?>[]{cssCls, String.class}, new Object[]{css, command}); } catch (Throwable t2) {} } } catch (Throwable t) {} }
    @Override public Set<UUID> getVerbosePlayers() { return verbosePlayers; }
    @Override public boolean isVerboseConsoleEnabled() { return false; }
    @Override public String getDisplayWorldName(String wk) { return wk; }

    @Override public boolean hasPermission(UUID pid, String node) { try { Object server = ForgeReflection.getServer(); if (server == null) return false; Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm == null) return false; Object player = ForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{pid}); if (player == null) return false; if (ForgeCommandBridge.checkForgePermission(player, node)) return true; return ForgeCommandBridge.isPlayerOperator(player); } catch (Throwable t) { return false; } }

    @Override public void sendMessageToPlayer(UUID pid, String msg) {
        try { Object server = ForgeReflection.getServer(); if (server == null) return; Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm == null) return; Object player = ForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{pid}); if (player == null) return; Object text = ForgeReflection.createText(msg); if (text == null) return; Class<?> tc = ForgeReflection.resolveTextComponentClass(); if (tc == null) return; try { ForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{tc, UUID.class}, new Object[]{text, UUID.randomUUID()}); } catch (Throwable t1) { try { ForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{tc}, new Object[]{text}); } catch (Throwable t2) { try { ForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{tc, boolean.class}, new Object[]{text, false}); } catch (Throwable t3) {} } } } catch (Throwable t) {} }

    @Override public Object getConfigSection(String p) { return config.get(p); }
    @Override public Object getConfig(String p) { return config.get(p); }
    @Override public String getPrefixedMessage(String key) { if (languageBridge != null) return languageBridge.getPrefixedMessage(key); return "[" + key + "]"; }
    @Override public File getDataFolder() { return adapter.getDataFolder(); }
    @Override public void resetViolation(UUID pid) { if (!resetViolationRecursionGuard.add(pid)) return; try { engine.resetViolation(pid); } finally { resetViolationRecursionGuard.remove(pid); } }
    @Override public void clearPlayerState(UUID pid) {
        cancelVLDecayTask(pid); verbosePlayers.remove(pid);
        ForgeDetectionBridge bridge = ForgeDetectionBridge.getActive(); if (bridge != null) bridge.clearPlayerPath(pid);
        resetViolation(pid); playerNameCache.remove(pid);
    }
    @Override public void appendCommandLog(String cmd) { appendLogLine("[CMD] " + cmd); }
    @Override public String getPlayerName(UUID pid) { String n = playerNameCache.get(pid); if (n != null) return n; try { Object server = ForgeReflection.getServer(); if (server != null) { Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm != null) { Object player = ForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{pid}); if (player != null) { Object name = ForgeReflection.callAny(player, "getName", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); String s = ForgeReflection.readString(name); if (s != null) { playerNameCache.put(pid, s); return s; } } } } } catch (Throwable t) {} return pid.toString(); }
    @Override public int getConfigInt(String p, int d) { return config.getInt(p, d); }
    @Override public boolean getConfigBoolean(String p, boolean d) { return config.getBoolean(p, d); }
    @Override public double getConfigDouble(String p, double d) { return config.getDouble(p, d); }

    private void writeLogLine(String line) {
        try {
            if (currentLogFileName == null) currentLogFileName = generateLogFileName();
            File logDir = new File(adapter.getDataFolder(), "logs"); if (!logDir.exists()) logDir.mkdirs();
            File logFile = new File(logDir, currentLogFileName);
            try (FileWriter fw = new FileWriter(logFile, true)) { fw.write(line + System.lineSeparator()); }
        } catch (IOException e) { adapter.warning("Failed to write violation log: " + e.getMessage()); }
    }
    private static String generateLogFileName() { return "violations-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".log"; }
}
