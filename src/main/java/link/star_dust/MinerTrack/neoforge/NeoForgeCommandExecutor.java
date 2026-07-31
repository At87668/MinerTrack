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

import link.star_dust.MinerTrack.common.CommandBridge;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.command.MinerTrackCommandCore;
import link.star_dust.MinerTrack.fabric.FabricReflection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NeoForge command executor. Delegates to MinerTrackCommandCore.
 */
public class NeoForgeCommandExecutor {
    private final NeoForgeAdapter adapter;
    private final NeoForgeLanguageBridge langBridge;
    private final ViolationManagerBridge vlBridge;
    private final NeoForgeUpdateManager updateManager;
    private final DetectionBridge detectionBridge;

    public NeoForgeCommandExecutor(NeoForgeAdapter adapter,
                                     NeoForgeLanguageBridge langBridge,
                                     ViolationManagerBridge vlBridge,
                                     NeoForgeUpdateManager updateManager,
                                     DetectionBridge detectionBridge) {
        this.adapter = adapter;
        this.langBridge = langBridge;
        this.vlBridge = vlBridge;
        this.updateManager = updateManager;
        this.detectionBridge = detectionBridge;
    }

    private MinerTrackCommandCore buildCore(Object source) {
        CommandBridge cmdBridge = new NeoForgeCommandBridge(source, vlBridge.getVerbosePlayers());
        return new MinerTrackCommandCore(langBridge, vlBridge, cmdBridge,
            new PlayerLookupImpl(source), new KickBridgeImpl(), new ConfigReloadBridgeImpl(),
            new UpdateCheckBridgeImpl(), new LogViewerBridgeImpl());
    }

    public boolean onCommand(Object source, String[] args) { return buildCore(source).onCommand(args); }
    public List<String> onTabComplete(Object source, String[] args) { return buildCore(source).onTabComplete(args); }

    private static Object server() { return FabricReflection.getServer(); }

    private static Object playerByUuid(Object source, UUID uuid) {
        if (source == null) return playerByUuid(uuid);
        try {
            Object server = FabricReflection.callAny(source, "getServer", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (server == null) return playerByUuid(uuid);
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (pm == null) return playerByUuid(uuid);
            return FabricReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{uuid});
        } catch (Throwable t) { return playerByUuid(uuid); }
    }

    private static Object playerByUuid(UUID uuid) {
        Object srv = server();
        if (srv == null) return null;
        Object pm = FabricReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
        if (pm == null) return null;
        return FabricReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{uuid});
    }

    private static Object playerByName(Object source, String name) {
        if (source == null) return playerByName(name);
        try {
            Object server = FabricReflection.callAny(source, "getServer", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (server == null) return playerByName(name);
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (pm == null) return playerByName(name);
            return FabricReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name});
        } catch (Throwable t) { return playerByName(name); }
    }

    private static Object playerByName(String name) {
        Object srv = server();
        if (srv == null) return null;
        Object pm = FabricReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
        if (pm == null) return null;
        return FabricReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name});
    }

    private class PlayerLookupImpl implements MinerTrackCommandCore.PlayerLookup {
        private final Object commandSource;
        PlayerLookupImpl(Object commandSource) { this.commandSource = commandSource; }

        @Override public UUID getPlayerUUID(String name) {
            try { Object player = playerByName(commandSource, name); if (player == null) return null; Object uuid = FabricReflection.callUuid(player); return uuid instanceof UUID ? (UUID) uuid : null; }
            catch (Throwable t) { return null; }
        }
        @Override public String getPlayerName(UUID uuid) {
            try { Object player = playerByUuid(commandSource, uuid); if (player == null) return uuid.toString(); Object name = FabricReflection.callAny(player, "getName", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS); String s = FabricReflection.readString(name); return s == null ? uuid.toString() : s; }
            catch (Throwable t) { return uuid.toString(); }
        }
        @Override public boolean isOnline(UUID uuid) { return playerByUuid(commandSource, uuid) != null; }
        @Override public List<String> getOnlinePlayerNames() {
            List<String> names = new ArrayList<>();
            try {
                Object server = commandSource != null ? FabricReflection.callAny(commandSource, "getServer", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS) : server();
                if (server == null) return names;
                Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                if (pm == null) return names;
                Object players = FabricReflection.callAny(pm, "getPlayers", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                if (players instanceof List) for (Object p : (List<?>) players) { Object name = FabricReflection.callAny(p, "getName", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS); String s = FabricReflection.readString(name); if (s != null) names.add(s); }
            } catch (Throwable t) {}
            return names;
        }
    }

    private class KickBridgeImpl implements MinerTrackCommandCore.KickBridge {
        @Override public void kickPlayer(UUID playerId, String reason) {
            try {
                Object server = server(); if (server == null) return;
                Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                if (pm == null) return;
                Object player = FabricReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{playerId});
                if (player == null) return;
                Object text = FabricReflection.createText(reason); if (text == null) return;
                Object connection = FabricReflection.callAny(player, "connection", FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                if (connection == null) return;
                Class<?> textCls = FabricReflection.resolveTextComponentClass(); if (textCls == null) return;
                try { FabricReflection.callAny(connection, "disconnect", new Class<?>[]{textCls}, new Object[]{text}); }
                catch (Throwable t) { FabricReflection.invokeBySigOrThrow(connection, new Class<?>[]{textCls}, new Object[]{text}); }
            } catch (Throwable t) { adapter.warning("Failed to kick player " + playerId + ": " + t.getMessage()); }
        }
    }

    private class ConfigReloadBridgeImpl implements MinerTrackCommandCore.ConfigReloadBridge {
        @Override public void reloadConfig() {
            adapter.reloadConfig();
            if (detectionBridge instanceof NeoForgeDetectionBridge) ((NeoForgeDetectionBridge) detectionBridge).loadGroupConfigs();
            violationManager.reloadConfig();
            langBridge.reloadLanguage();
        }
    }

    private class UpdateCheckBridgeImpl implements MinerTrackCommandCore.UpdateCheckBridge {
        @Override public void checkForUpdates(CommandBridge sender) { updateManager.checkForUpdates(sender); }
    }

    private class LogViewerBridgeImpl implements MinerTrackCommandCore.LogViewerBridge {
        @Override public List<String> getRecentLogLines(int count) {
            List<String> lines = new ArrayList<>();
            try { File logFile = new File(adapter.getDataFolder(), "violations.log"); if (!logFile.exists()) return lines; List<String> all = Files.readAllLines(logFile.toPath()); int start = Math.max(0, all.size() - count); for (int i = start; i < all.size(); i++) lines.add(all.get(i)); }
            catch (IOException e) {}
            return lines;
        }
        @Override public void clearLog() {
            try { File logFile = new File(adapter.getDataFolder(), "violations.log"); if (logFile.exists()) Files.write(logFile.toPath(), new byte[0]); }
            catch (IOException ignored) {}
        }
    }
}
