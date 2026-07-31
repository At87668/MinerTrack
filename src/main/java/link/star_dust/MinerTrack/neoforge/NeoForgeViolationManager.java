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
import link.star_dust.MinerTrack.fabric.FabricReflection;

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
 * NeoForge ViolationManagerBridge. Mirrors ForgeViolationManager.
 * Uses NeoForge's ServerTickEvent for decay scheduling.
 */
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

    public NeoForgeViolationManager(NeoForgeAdapter adapter) {
        this.adapter = adapter;
        this.engine = new ViolationEngine(this);
        active = this;
        this.currentLogFileName = generateLogFileName();
        File f = new File(adapter.getDataFolder(), "config.yml");
        this.config = ConfigMerger.loadAndMerge(f, "config.yml", adapter, new NeoForgeYamlLoader());
    }

    public void scheduleGlobalDecayTask(long decayIntervalTicks) {
        this.globalDecayIntervalTicks = decayIntervalTicks;
        NeoForgeReflection.registerEventListener(
            NeoForgeReflection.getMainEventBus(),
            NeoForgeReflection.neoClass("net.neoforged.neoforge.event.TickEvent$ServerTickEvent"),
            rawEvent -> {
                try {
                    Object phase = FabricReflection.callAny(rawEvent, "getPhase",
                        FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                    if (phase == null || !phase.toString().contains("END")) return;
                    Object server = FabricReflection.getServer(); if (server == null) return;
                    Object tickObj = FabricReflection.callMigrated(server, "getTickCount", "getTicks",
                        FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                    long tick = tickObj instanceof Number ? ((Number) tickObj).longValue() : 0L;
                    if (lastGlobalDecayRunTick < 0 || tick - lastGlobalDecayRunTick >= globalDecayIntervalTicks) {
                        lastGlobalDecayRunTick = tick;
                        try { engine.processDecay(); } catch (Throwable t) { adapter.warning("Global VL decay tick failed: " + t.getMessage()); }
                    }
                } catch (Throwable t) { adapter.warning("ServerTick handler error: " + t.getMessage()); }
            });
    }

    public void cancelAllVLDecayTasks() { playerDecayTasks.clear(); }
    public void cancelGlobalDecayTask() {}
    public void setWebhookEngine(WebhookEngine webhookEngine) { engine.setWebhookEngine(webhookEngine); }
    public void setLanguageBridge(LanguageBridge languageBridge) { this.languageBridge = languageBridge; }
    public CommonYaml getMainConfig() { return config; }

    public void reloadConfig() {
        try { File f = new File(adapter.getDataFolder(), "config.yml"); this.config = ConfigMerger.loadAndMerge(f, "config.yml", adapter, new NeoForgeYamlLoader()); }
        catch (Exception e) { adapter.info("Failed to reload config: " + e.getMessage()); }
    }

    @Override public int getViolationLevel(UUID playerId) { return engine.getViolationLevel(playerId); }
    @Override public void increaseViolationLevel(UUID playerId, String playerName, int increment, String blockType, int count, int vein, CommonLocation location) { cancelVLDecayTask(playerId); engine.increaseViolation(playerId, playerName, increment, blockType, count, vein, location); scheduleVLDecayTask(playerId); }
    @Override public void processVLDecay() { engine.processDecay(); }
    @Override public void scheduleVLDecayTask(UUID playerId) { if (!playerDecayTasks.containsKey(playerId)) playerDecayTasks.put(playerId, System.currentTimeMillis()); }
    @Override public void cancelVLDecayTask(UUID playerId) { if (playerId != null) playerDecayTasks.remove(playerId); }
    @Override public boolean isLogFileEnabled() { return config.getBoolean("log_file", false); }
    @Override public String getLogFormat() { if (languageBridge != null) return languageBridge.getLogFormat(); return "%year%-%month%-%day% %hour%-%minute%-%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%"; }
    @Override public String getDisplayWorldName(String worldKey) { return worldKey; }
    @Override public String getPrefixedMessage(String key) { if (languageBridge != null) return languageBridge.getPrefixedMessage(key); return "[" + key + "]"; }
    @Override public Set<UUID> getVerbosePlayers() { return verbosePlayers; }
    @Override public boolean isVerboseConsoleEnabled() { return false; }

    @Override public boolean hasPermission(UUID playerId, String node) {
        try {
            Object server = FabricReflection.getServer(); if (server == null) return false;
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS); if (pm == null) return false;
            Object player = FabricReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{playerId}); if (player == null) return false;
            if (NeoForgeCommandBridge.checkLPPermission(player, node, 2)) return true;
            return NeoForgeCommandBridge.isPlayerOperator(player);
        } catch (Throwable t) { return false; }
    }

    @Override public void sendMessageToPlayer(UUID playerId, String message) {
        try {
            Object server = FabricReflection.getServer(); if (server == null) return;
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS); if (pm == null) return;
            Object player = FabricReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{playerId}); if (player == null) return;
            Object text = FabricReflection.createText(message); if (text == null) return;
            Class<?> textCls = FabricReflection.resolveTextComponentClass(); if (textCls == null) return;
            try { FabricReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls, UUID.class}, new Object[]{text, UUID.randomUUID()}); }
            catch (Throwable t1) {
                try { FabricReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls}, new Object[]{text}); }
                catch (Throwable t2) { try { FabricReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls, boolean.class}, new Object[]{text, false}); } catch (Throwable t3) {} }
            }
        } catch (Throwable t) {}
    }

    @Override public Object getConfigSection(String path) { return config.get(path); }
    @Override public Object getConfig(String path) { return config.get(path); }
    @Override public File getDataFolder() { return adapter.getDataFolder(); }

    @Override public void resetViolation(UUID playerId) {
        if (!resetViolationRecursionGuard.add(playerId)) return;
        try { engine.resetViolation(playerId); } finally { resetViolationRecursionGuard.remove(playerId); }
    }

    @Override public void writeToLogFile(String line) {
        try {
            if (currentLogFileName == null) currentLogFileName = generateLogFileName();
            File logDir = new File(adapter.getDataFolder(), "logs"); if (!logDir.exists()) logDir.mkdirs();
            File logFile = new File(logDir, currentLogFileName);
            try (FileWriter fw = new FileWriter(logFile, true)) { fw.write(line + System.lineSeparator()); }
        } catch (IOException e) { adapter.warning("Failed to write violation log: " + e.getMessage()); }
    }

    private static String generateLogFileName() { return "violations-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".log"; }
}
