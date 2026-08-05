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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class NeoForgeCommandExecutor {
    private final NeoForgeAdapter adapter;
    private final NeoForgeLanguageBridge langBridge;
    private final ViolationManagerBridge vlBridge;
    private final NeoForgeUpdateManager updateManager;
    private final DetectionBridge detectionBridge;

    public NeoForgeCommandExecutor(NeoForgeAdapter adapter, NeoForgeLanguageBridge langBridge, ViolationManagerBridge vlBridge, NeoForgeUpdateManager updateManager, DetectionBridge detectionBridge) {
        this.adapter = adapter; this.langBridge = langBridge; this.vlBridge = vlBridge; this.updateManager = updateManager; this.detectionBridge = detectionBridge;
    }

    private MinerTrackCommandCore buildCore(Object source) {
        return new MinerTrackCommandCore(langBridge, vlBridge, new NeoForgeCommandBridge(source, vlBridge.getVerbosePlayers(), vlBridge), new PlayerLookupImpl(source), new KickBridgeImpl(), new ConfigReloadBridgeImpl(), new UpdateCheckBridgeImpl(), new LogViewerBridgeImpl());
    }

    public boolean onCommand(Object source, String[] args) {
        try {
            return buildCore(source).onCommand(args);
        } catch (Throwable t) {
            return true;
        }
    }
    public List<String> onTabComplete(Object source, String[] args) { return buildCore(source).onTabComplete(args); }

    private static Object server() { return NeoForgeReflection.getServer(); }
    private static Object playerByUuid(Object source, UUID uuid) { if (source == null) return playerByUuid(uuid); try { Object srv = NeoForgeReflection.callAny(source, "getServer", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (srv != null) { Object pm = NeoForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (pm != null) { Object p = NeoForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{uuid}); if (p != null) return p; } } } catch (Throwable t) {} return playerByUuid(uuid); }
    private static Object playerByUuid(UUID uuid) { Object srv = server(); if (srv == null) return null; Object pm = NeoForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (pm == null) return null; return NeoForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{uuid}); }
    private static Object playerByName(Object source, String name) { if (source == null) return playerByName(name); try { Object srv = NeoForgeReflection.callAny(source, "getServer", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (srv != null) { Object pm = NeoForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (pm != null) { Object p = NeoForgeReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name}); if (p != null) return p; } } } catch (Throwable t) {} return playerByName(name); }
    private static Object playerByName(String name) { Object srv = server(); if (srv == null) return null; Object pm = NeoForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (pm == null) return null; return NeoForgeReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name}); }

    private class PlayerLookupImpl implements MinerTrackCommandCore.PlayerLookup {
        private final Object cs;
        PlayerLookupImpl(Object cs) { this.cs = cs; }
        @Override public UUID getPlayerUUID(String name) { try { Object p = playerByName(cs, name); if (p == null) return null; Object u = NeoForgeReflection.callUuid(p); return u instanceof UUID ? (UUID) u : null; } catch (Throwable t) { return null; } }
        @Override public String getPlayerName(UUID uuid) { try { Object p = playerByUuid(cs, uuid); if (p == null) return uuid.toString(); Object n = NeoForgeReflection.callAny(p, "getName", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); String s = NeoForgeReflection.readString(n); return s == null ? uuid.toString() : s; } catch (Throwable t) { return uuid.toString(); } }
        @Override public boolean isOnline(UUID uuid) { return playerByUuid(cs, uuid) != null; }
        @Override public List<String> getOnlinePlayerNames() { List<String> names = new ArrayList<>(); try { Object srv = cs != null ? NeoForgeReflection.callAny(cs, "getServer", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS) : server(); if (srv == null) return names; Object pm = NeoForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (pm == null) return names; Object players = NeoForgeReflection.callAny(pm, "getPlayers", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (players instanceof List) for (Object p : (List<?>) players) { Object n = NeoForgeReflection.callAny(p, "getName", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); String s = NeoForgeReflection.readString(n); if (s != null) names.add(s); } } catch (Throwable t) {} return names; }
    }

    private class KickBridgeImpl implements MinerTrackCommandCore.KickBridge {
        @Override public void kickPlayer(UUID pid, String reason) {
            try {
                Object player = playerByUuid(pid);
                if (player == null) return;
                Object text = NeoForgeReflection.createText(reason == null ? "Kicked by MinerTrack" : reason);
                if (text == null) return;
                Class<?> tc = NeoForgeReflection.resolveTextComponentClass();
                if (tc == null) return;
                Object network = NeoForgeReflection.getField(player, "connection");
                if (network == null) network = NeoForgeReflection.getField(player, "networkHandler");
                if (network == null) return;
                if (disconnect(network, tc, text)) return;
                // Last resort: name-independent signature scan.
                NeoForgeReflection.invokeBySigOrThrow(network, new Class<?>[]{tc}, new Object[]{text});
            } catch (Throwable t) {
                adapter.warning("Failed to kick " + pid + ": " + t.getMessage());
            }
        }

        /**
         * Disconnect a packet listener using the redirect-mapped
         * {@code M_DISCONNECT_NEW} / {@code M_DISCONNECT} constant values as the
         * candidate method names, matched EXACTLY over {@code getMethods()}
         * (which includes inherited public methods).
         *
         * <p>We deliberately bypass {@code callAny}/{@code findMethodImpl}: its
         * blind scanMethod fallback can match an unrelated (Component)-&gt;void
         * method on ServerGamePacketListenerImpl (not the real disconnect), which
         * removes the player server-side but never sends the
         * ClientboundDisconnectPacket — leaving the client half-connected.
         * 1.20.4+ moved disconnect(Component) to ServerCommonPacketListenerImpl
         * (reached via getMethods).
         */
        private static boolean disconnect(Object network, Class<?> tc, Object text) {
            String[] names = {NeoForgeReflectionConstants.M_DISCONNECT_NEW, NeoForgeReflectionConstants.M_DISCONNECT};
            for (String name : names) {
                for (java.lang.reflect.Method m : network.getClass().getMethods()) {
                    if (!m.getName().equals(name)) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (!m.getParameterTypes()[0].isAssignableFrom(tc)) continue;
                    // Invoke the real disconnect. Its internal handleDisconnection
                    // may throw once the connection is torn down; that must NOT
                    // make us return false (which would trigger a second
                    // invokeBySigOrThrow kick → "handleDisconnection() called twice").
                    try {
                        m.invoke(network, text);
                    } catch (Throwable t) { /* disconnect already initiated; ignore */ }
                    return true;
                }
            }
            return false;
        }

        @Override public boolean isKickStrikeLightning() { try { return detectionBridge.getConfigBoolean("kick_strike_lightning", true); } catch (Throwable t) { return true; } }
        @Override public void strikeLightningEffect(UUID pid) { try { Object player = playerByUuid(pid); if (player == null) return; Object world = NeoForgeReflection.callMigrated(player, "level", "getWorld", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (world == null) return; Object rk = NeoForgeReflection.callDimension(world); if (rk == null) return; Object srv = server(); if (srv == null) return; Object sw = NeoForgeReflection.callMigrated(srv, "getLevel", "getWorld", new Class<?>[]{rk.getClass()}, new Object[]{rk}); if (sw == null) return; Object l = NeoForgeReflection.newInstance("net.minecraft.world.entity.LightningBolt", new Class<?>[]{Class.forName("net.minecraft.world.entity.EntityType"), Class.forName("net.minecraft.server.level.ServerLevel")}, new Object[]{Class.forName("net.minecraft.world.entity.EntityType").getField("LIGHTNING_BOLT").get(null), sw}); if (l == null) { l = NeoForgeReflection.newInstance("net.minecraft.world.entity.LightningBolt", new Class<?>[]{Class.forName("net.minecraft.world.entity.EntityType"), Class.forName("net.minecraft.world.level.Level")}, new Object[]{Class.forName("net.minecraft.world.entity.EntityType").getField("LIGHTNING_BOLT").get(null), sw}); } if (l == null) return; Object x = NeoForgeReflection.callAny(player, "getX", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); Object y = NeoForgeReflection.callAny(player, "getY", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); Object z = NeoForgeReflection.callAny(player, "getZ", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); try { NeoForgeReflection.callAny(l, "setPos", new Class<?>[]{double.class, double.class, double.class}, new Object[]{x, y, z}); } catch (Throwable t) { NeoForgeReflection.callAny(l, "refreshPositionAfterTeleport", new Class<?>[]{double.class, double.class, double.class}, new Object[]{x, y, z}); } } catch (Throwable t) {} }
        @Override public void broadcastMessage(String msg) { try { Object srv = server(); if (srv == null) return; Object pm = NeoForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS); if (pm == null) return; String full = langBridge.getPrefix().trim() + " " + adapter.applyColors(msg); Object text = NeoForgeReflection.createText(full); if (text == null) return; Class<?> tc = NeoForgeReflection.resolveTextComponentClass(); boolean old = NeoForgeReflection.DEBUG_REFLECTION; NeoForgeReflection.DEBUG_REFLECTION = false; try { NeoForgeReflection.call(pm, "broadcastSystemMessage", new Class<?>[]{tc, boolean.class}, new Object[]{text, false}); return; } catch (Throwable t) {} finally { NeoForgeReflection.DEBUG_REFLECTION = old; } try { Class<?> ct = NeoForgeReflection.forName("net.minecraft.network.chat.ChatType"); if (ct != null) { Object chatType = NeoForgeReflection.getField(ct, "CHAT"); if (chatType == null) chatType = NeoForgeReflection.getField(ct, "SYSTEM"); if (chatType != null) { NeoForgeReflection.call(pm, "broadcastMessage", new Class<?>[]{tc, ct, UUID.class}, new Object[]{text, chatType, UUID.randomUUID()}); return; } } } catch (Throwable t) {} try { NeoForgeReflection.call(pm, "broadcast", new Class<?>[]{tc, boolean.class}, new Object[]{text, false}); } catch (Throwable t) {} } catch (Throwable t) {} }
    }

    private class ConfigReloadBridgeImpl implements MinerTrackCommandCore.ConfigReloadBridge {
        @Override public void reloadConfig() { adapter.reloadConfig(); if (detectionBridge != null) detectionBridge.clearConfigCache(); if (detectionBridge != null) detectionBridge.loadGroupConfigs(); try { NeoForgeViolationManager vm = NeoForgeViolationManager.getActive(); if (vm != null) { vm.reloadConfig(); link.star_dust.MinerTrack.core.config.WebhookConfig fresh = link.star_dust.MinerTrack.core.config.WebhookConfig.from(vm.getMainConfig()); vm.setWebhookEngine(new link.star_dust.MinerTrack.core.violation.WebhookEngine(fresh, new NeoForgeWebhookSender(adapter))); } } catch (Throwable t) { adapter.info("Failed to refresh webhook engine on reload: " + t.getMessage()); } }
        @Override public void reloadLanguage() { langBridge.reloadLanguage(); }
    }

    private class UpdateCheckBridgeImpl implements MinerTrackCommandCore.UpdateCheckBridge {
        @Override public void checkForUpdates(CommandBridge sender) { updateManager.checkForUpdates(sender); }
    }

    private class LogViewerBridgeImpl implements MinerTrackCommandCore.LogViewerBridge {
        @Override public List<String> getLogFileNames(int maxFiles) { File logDir = new File(adapter.getDataFolder(), "logs"); if (!logDir.exists()) return new ArrayList<>(); File[] files = logDir.listFiles((d, n) -> n.toLowerCase().endsWith(".log")); if (files == null) return new ArrayList<>(); Arrays.sort(files, Comparator.comparing(File::getName).reversed()); List<String> names = new ArrayList<>(); for (int i = 0; i < files.length && i < maxFiles; i++) names.add(files[i].getName()); return names; }
        @Override public byte[] readLogFile(String fileName) { File logDir = new File(adapter.getDataFolder(), "logs"); File target = new File(logDir, fileName); if (!target.exists() || !target.isFile()) return new byte[0]; try { return Files.readAllBytes(target.toPath()); } catch (IOException e) { return new byte[0]; } }
        @Override public int getLogViewerLinesPerPage() { return 10; }
        @Override public String getLogFormat() { return vlBridge.getLogFormat(); }
    }
}
