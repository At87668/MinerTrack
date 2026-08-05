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

public class ForgeCommandExecutor {
    private final ForgeAdapter adapter;
    private final ForgeLanguageBridge langBridge;
    private final ViolationManagerBridge vlBridge;
    private final ForgeUpdateManager updateManager;
    private final DetectionBridge detectionBridge;

    public ForgeCommandExecutor(ForgeAdapter adapter, ForgeLanguageBridge langBridge, ViolationManagerBridge vlBridge, ForgeUpdateManager updateManager, DetectionBridge detectionBridge) {
        this.adapter = adapter; this.langBridge = langBridge; this.vlBridge = vlBridge; this.updateManager = updateManager; this.detectionBridge = detectionBridge;
    }

    private MinerTrackCommandCore buildCore(Object source) {
        return new MinerTrackCommandCore(langBridge, vlBridge, new ForgeCommandBridge(source, vlBridge.getVerbosePlayers(), vlBridge), new PlayerLookupImpl(source), new KickBridgeImpl(), new ConfigReloadBridgeImpl(), new UpdateCheckBridgeImpl(), new LogViewerBridgeImpl());
    }

    public boolean onCommand(Object source, String[] args) { return buildCore(source).onCommand(args); }
    public List<String> onTabComplete(Object source, String[] args) { return buildCore(source).onTabComplete(args); }

    private static Object server() { return ForgeReflection.getServer(); }
    private static Object playerByUuid(Object source, UUID uuid) {
        if (source == null) return playerByUuid(uuid);
        try { Object srv = ForgeReflection.callAny(source, "getServer", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (srv != null) { Object pm = ForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm != null) { Object p = ForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{uuid}); if (p != null) return p; } } } catch (Throwable t) {}
        return playerByUuid(uuid);
    }
    private static Object playerByUuid(UUID uuid) { Object srv = server(); if (srv == null) return null; Object pm = ForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm == null) return null; return ForgeReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{uuid}); }
    private static Object playerByName(Object source, String name) {
        if (source == null) return playerByName(name);
        try { Object srv = ForgeReflection.callAny(source, "getServer", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (srv != null) { Object pm = ForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm != null) { Object p = ForgeReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name}); if (p != null) return p; } } } catch (Throwable t) {}
        return playerByName(name);
    }
    private static Object playerByName(String name) { Object srv = server(); if (srv == null) return null; Object pm = ForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm == null) return null; return ForgeReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name}); }

    private class PlayerLookupImpl implements MinerTrackCommandCore.PlayerLookup {
        private final Object commandSource;
        PlayerLookupImpl(Object cs) { this.commandSource = cs; }
        @Override public UUID getPlayerUUID(String name) { try { Object p = playerByName(commandSource, name); if (p == null) return null; Object u = ForgeReflection.callUuid(p); return u instanceof UUID ? (UUID) u : null; } catch (Throwable t) { return null; } }
        @Override public String getPlayerName(UUID uuid) { try { Object p = playerByUuid(commandSource, uuid); if (p == null) return uuid.toString(); Object n = ForgeReflection.callAny(p, "getName", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); String s = ForgeReflection.readString(n); return s == null ? uuid.toString() : s; } catch (Throwable t) { return uuid.toString(); } }
        @Override public boolean isOnline(UUID uuid) { return playerByUuid(commandSource, uuid) != null; }
        @Override public List<String> getOnlinePlayerNames() { List<String> names = new ArrayList<>(); try { Object srv = commandSource != null ? ForgeReflection.callAny(commandSource, "getServer", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS) : server(); if (srv == null) return names; Object pm = ForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm == null) return names; Object players = ForgeReflection.callAny(pm, "getPlayers", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (players instanceof List) for (Object p : (List<?>) players) { Object n = ForgeReflection.callAny(p, "getName", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); String s = ForgeReflection.readString(n); if (s != null) names.add(s); } } catch (Throwable t) {} return names; }
    }

    private class KickBridgeImpl implements MinerTrackCommandCore.KickBridge {
        @Override public void kickPlayer(UUID pid, String reason) {
            try {
                Object player = playerByUuid(pid);
                if (player == null) return;
                Object text = ForgeReflection.createText(reason == null ? "Kicked by MinerTrack" : reason);
                if (text == null) return;
                Class<?> tc = ForgeReflection.resolveTextComponentClass();
                if (tc == null) return;
                Object network = ForgeReflection.getField(player, "connection");
                if (network == null) network = ForgeReflection.getField(player, "networkHandler");
                if (network == null) return;
                if (disconnect(network, tc, text)) return;
                // Last resort: name-independent signature scan.
                ForgeReflection.invokeBySigOrThrow(network, new Class<?>[]{tc}, new Object[]{text});
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
         * (reached via getMethods), and Forge/Arclight may run with Searge names
         * (M_DISCONNECT_NEW = m_3233_, M_DISCONNECT = m_9942_).
         */
        private static boolean disconnect(Object network, Class<?> tc, Object text) {
            String[] names = {ForgeReflectionConstants.M_DISCONNECT_NEW, ForgeReflectionConstants.M_DISCONNECT};
            try {
                for (String name : names) {
                    for (java.lang.reflect.Method m : network.getClass().getMethods()) {
                        if (!m.getName().equals(name)) continue;
                        if (m.getParameterCount() != 1) continue;
                        if (!m.getParameterTypes()[0].isAssignableFrom(tc)) continue;
                        m.invoke(network, text);
                        return true;
                    }
                }
            } catch (Throwable t) { /* fall through */ }
            return false;
        }

        @Override public boolean isKickStrikeLightning() { try { return detectionBridge.getConfigBoolean("kick_strike_lightning", true); } catch (Throwable t) { return true; } }

        @Override public void strikeLightningEffect(UUID pid) { try { Object player = playerByUuid(pid); if (player == null) return; Object world = null; try { world = ForgeReflection.getField(player, "level"); } catch (Throwable t) {} if (world == null) world = ForgeReflection.callMigrated(player, "level", "getWorld", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (world == null) return; Object rk = ForgeReflection.callDimension(world); if (rk == null) return; Object srv = server(); if (srv == null) return; Object sw = ForgeReflection.callMigrated(srv, "getLevel", "getWorld", new Class<?>[]{rk.getClass()}, new Object[]{rk}); if (sw == null) return; Object l = ForgeReflection.newInstance("net.minecraft.world.entity.LightningBolt", new Class<?>[]{Class.forName("net.minecraft.world.entity.EntityType"), Class.forName("net.minecraft.server.level.ServerLevel")}, new Object[]{Class.forName("net.minecraft.world.entity.EntityType").getField("LIGHTNING_BOLT").get(null), sw}); if (l == null) { l = ForgeReflection.newInstance("net.minecraft.world.entity.LightningBolt", new Class<?>[]{Class.forName("net.minecraft.world.entity.EntityType"), Class.forName("net.minecraft.world.level.Level")}, new Object[]{Class.forName("net.minecraft.world.entity.EntityType").getField("LIGHTNING_BOLT").get(null), sw}); } if (l == null) return; Object x = ForgeReflection.callAny(player, "getX", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); Object y = ForgeReflection.callAny(player, "getY", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); Object z = ForgeReflection.callAny(player, "getZ", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); try { ForgeReflection.callAny(l, "setPos", new Class<?>[]{double.class, double.class, double.class}, new Object[]{x, y, z}); } catch (Throwable t) { ForgeReflection.callAny(l, "refreshPositionAfterTeleport", new Class<?>[]{double.class, double.class, double.class}, new Object[]{x, y, z}); } } catch (Throwable t) {} }

        @Override public void broadcastMessage(String msg) { try { Object srv = server(); if (srv == null) return; Object pm = ForgeReflection.callMigrated(srv, "getPlayerList", "getPlayerManager", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS); if (pm == null) return; String full = langBridge.getPrefix().trim() + " " + adapter.applyColors(msg); Object text = ForgeReflection.createText(full); if (text == null) return; Class<?> tc = ForgeReflection.resolveTextComponentClass(); boolean old = ForgeReflection.DEBUG_REFLECTION; ForgeReflection.DEBUG_REFLECTION = false; try { ForgeReflection.call(pm, "broadcastSystemMessage", new Class<?>[]{tc, boolean.class}, new Object[]{text, false}); return; } catch (Throwable t) {} finally { ForgeReflection.DEBUG_REFLECTION = old; } try { Class<?> ct = ForgeReflection.forName("net.minecraft.network.chat.ChatType"); if (ct != null) { Object chatType = ForgeReflection.getField(ct, "CHAT"); if (chatType == null) chatType = ForgeReflection.getField(ct, "SYSTEM"); if (chatType != null) { ForgeReflection.call(pm, "broadcastMessage", new Class<?>[]{tc, ct, UUID.class}, new Object[]{text, chatType, UUID.randomUUID()}); return; } } } catch (Throwable t) {} try { ForgeReflection.call(pm, "broadcast", new Class<?>[]{tc, boolean.class}, new Object[]{text, false}); } catch (Throwable t) {} } catch (Throwable t) {} }
    }

    private class ConfigReloadBridgeImpl implements MinerTrackCommandCore.ConfigReloadBridge {
        @Override public void reloadConfig() { adapter.reloadConfig(); if (detectionBridge != null) detectionBridge.clearConfigCache(); if (detectionBridge != null) detectionBridge.loadGroupConfigs(); try { ForgeViolationManager vm = ForgeViolationManager.getActive(); if (vm != null) { vm.reloadConfig(); link.star_dust.MinerTrack.core.config.WebhookConfig fresh = link.star_dust.MinerTrack.core.config.WebhookConfig.from(vm.getMainConfig()); vm.setWebhookEngine(new link.star_dust.MinerTrack.core.violation.WebhookEngine(fresh, new ForgeWebhookSender(adapter))); } } catch (Throwable t) { adapter.info("Failed to refresh webhook engine on reload: " + t.getMessage()); } }
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
