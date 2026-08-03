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
import link.star_dust.MinerTrack.common.ViolationManagerBridge;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * NeoForge CommandBridge wrapping CommandSourceStack (held as Object).
 * Mirrors ForgeCommandBridge and FabricCommandBridge.
 */
public class NeoForgeCommandBridge implements CommandBridge {
    private final Object source;
    private final Set<UUID> verbosePlayers;
    private final ViolationManagerBridge vlBridge;

    public NeoForgeCommandBridge(Object source, Set<UUID> verbosePlayers) {
        this(source, verbosePlayers, null);
    }

    public NeoForgeCommandBridge(Object source, Set<UUID> verbosePlayers, ViolationManagerBridge vlBridge) {
        this.source = source;
        this.verbosePlayers = verbosePlayers;
        this.vlBridge = vlBridge;
    }

    private static Object createText(String message) {
        return NeoForgeReflection.createText(message);
    }

    @Override public void dispatchCommand(String command) {
        try {
            if (source == null) return;
            Object server = NeoForgeReflection.callAny(source, "getServer",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (server == null) return;
            Object cmdManager = NeoForgeReflection.callAny(server, "getCommands",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (cmdManager == null)
                cmdManager = NeoForgeReflection.callAny(server, "getCommandManager",
                    NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (cmdManager == null) return;
            Class<?> cssCls = NeoForgeReflection.forName("net.minecraft.commands.CommandSourceStack");
            if (cssCls == null) return;
            try {
                NeoForgeReflection.callAny(cmdManager, "performPrefixedCommand",
                    new Class<?>[]{cssCls, String.class}, new Object[]{source, command});
            } catch (Throwable t1) {
                try {
                    NeoForgeReflection.callAny(cmdManager, "performCommand",
                        new Class<?>[]{cssCls, String.class}, new Object[]{source, command});
                } catch (Throwable t2) {
                    NeoForgeReflection.callAny(cmdManager, "executeWithPrefix",
                        new Class<?>[]{cssCls, String.class}, new Object[]{source, command});
                }
            }
        } catch (Throwable t) {}
    }

    @Override public boolean isPlayer() {
        try {
            Object r = NeoForgeReflection.callAny(source, "isPlayer",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (r instanceof Boolean && (Boolean) r) return true;
        } catch (Throwable t) {}
        try {
            Object entity = NeoForgeReflection.callAny(source, "getEntity",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (entity != null) {
                Class<?> serverPlayer = NeoForgeReflection.forName("net.minecraft.server.level.ServerPlayer");
                if (serverPlayer != null && serverPlayer.isInstance(entity)) return true;
            }
        } catch (Throwable t) {}
        try {
            Object r = NeoForgeReflection.callAny(source, "isExecutedByPlayer",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) { return false; }
    }

    @Override public boolean isConsole() { return !isPlayer(); }
    @Override public Object getSender() { return source; }

    @Override public void sendMessage(String message) {
        if (source == null) { System.out.println("[MinerTrack] " + message); return; }
        Object text = createText(message);
        if (text == null) { System.out.println("[MinerTrack] " + message); return; }
        if (!sendFeedback(source, text, true)) System.out.println("[MinerTrack] " + message);
    }

    @Override public void sendSuccess(String message) {
        if (source == null) { System.out.println("[MinerTrack] " + message); return; }
        Object text = createText(message);
        if (text == null) { System.out.println("[MinerTrack] " + message); return; }
        if (!sendFeedback(source, text, true)) System.out.println("[MinerTrack] " + message);
    }

    @Override public void sendFailure(String message) {
        if (source == null) { System.out.println("[MinerTrack] " + message); return; }
        Object text = createText(message);
        if (text == null) { System.out.println("[MinerTrack] " + message); return; }
        if (!sendFeedback(source, text, false)) System.out.println("[MinerTrack] " + message);
    }

    private static boolean sendFeedback(Object target, Object text, boolean isSuccess) {
        if (text == null || target == null) return false;
        Class<?> textCls = NeoForgeReflection.resolveTextComponentClass();
        if (textCls == null) return false;
        Class<?> targetCls = target.getClass();
        if (isSuccess) {
            boolean oldDebug = NeoForgeReflection.DEBUG_REFLECTION;
            NeoForgeReflection.DEBUG_REFLECTION = false;
            try {
                try {
                    Method m = NeoForgeReflection.findMethod(targetCls, "sendSuccess",
                        new Class<?>[]{Supplier.class, boolean.class});
                    if (m != null) { final Object t = text; m.invoke(target, (Supplier<?>) () -> t, false); return true; }
                } catch (Throwable t) {}
                try {
                    Method m = NeoForgeReflection.findMethod(targetCls, "sendSuccess",
                        new Class<?>[]{textCls, boolean.class});
                    if (m != null) { m.invoke(target, text, false); return true; }
                } catch (Throwable t) {}
            } finally { NeoForgeReflection.DEBUG_REFLECTION = oldDebug; }
        }
        if (!isSuccess) {
            try {
                Method m = NeoForgeReflection.findMethod(targetCls, "sendFailure", new Class<?>[]{textCls});
                if (m != null) { m.invoke(target, text); return true; }
            } catch (Throwable t) {}
        }
        try { Method m = NeoForgeReflection.findMethod(targetCls, "sendSystemMessage", new Class<?>[]{textCls}); if (m != null) { m.invoke(target, text); return true; } } catch (Throwable t) {}
        try { Method m = NeoForgeReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{textCls, UUID.class}); if (m != null) { m.invoke(target, text, UUID.randomUUID()); return true; } } catch (Throwable t) {}
        try { Method m = NeoForgeReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{textCls, boolean.class}); if (m != null) { m.invoke(target, text, false); return true; } } catch (Throwable t) {}
        try { Method m = NeoForgeReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{textCls}); if (m != null) { m.invoke(target, text); return true; } } catch (Throwable t) {}
        return false;
    }

    @Override public void sendMessageToPlayer(UUID playerId, String message) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return;
            Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (pm == null) return;
            Object player = NeoForgeReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            Object text = createText(message);
            if (text == null) return;
            Class<?> textCls = NeoForgeReflection.resolveTextComponentClass();
            if (textCls == null) return;
            try { NeoForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls, UUID.class}, new Object[]{text, UUID.randomUUID()}); }
            catch (Throwable t1) {
                try { NeoForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls}, new Object[]{text}); }
                catch (Throwable t2) {
                    try { NeoForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls, boolean.class}, new Object[]{text, false}); }
                    catch (Throwable t3) {}
                }
            }
        } catch (Throwable t) {}
    }

    @Override public void sendMessageToConsole(String message) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return;
            Object text = createText(message);
            if (text == null) return;
            Class<?> textCls = NeoForgeReflection.resolveTextComponentClass();
            if (textCls == null) return;
            try { NeoForgeReflection.invokeBySigOrThrow(server, new Class<?>[]{textCls}, new Object[]{text}); }
            catch (Throwable t1) {
                try { NeoForgeReflection.invokeBySigOrThrow(server, new Class<?>[]{textCls, UUID.class}, new Object[]{text, UUID.randomUUID()}); }
                catch (Throwable t2) {}
            }
        } catch (Throwable t) { System.out.println("[MinerTrack] " + message); }
    }

    @Override public boolean toggleVerbose() {
        if (source != null) {
            UUID id = extractPlayerUuid(source);
            if (id != null) {
                if (verbosePlayers.contains(id)) { verbosePlayers.remove(id); return false; }
                else { verbosePlayers.add(id); return true; }
            }
        }
        // Console: persist the toggle in the shared ViolationManager so it
        // survives across command invocations (a fresh bridge is created per
        // command). Fall back to a local field if no manager is available.
        if (vlBridge != null) {
            boolean next = !vlBridge.isVerboseConsoleEnabled();
            vlBridge.setVerboseConsoleEnabled(next);
            return next;
        }
        return false;
    }

    private static UUID extractPlayerUuid(Object css) {
        if (css == null) return null;
        for (String methodName : new String[]{"getPlayer", "getEntity"}) {
            Object entity = NeoForgeReflection.callAny(css, methodName, NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (entity != null) { Object uid = NeoForgeReflection.callUuid(entity); if (uid instanceof UUID) return (UUID) uid; }
        }
        return null;
    }

    // ==================================================================
    // Permission checks via native NeoForge PermissionAPI
    //
    // NeoForge ships net.neoforged.neoforge.server.permission.PermissionAPI
    // which integrates with LuckPerms or any permission plugin.
    // Called reflectively to avoid compile-time coupling.
    // Fallback: vanilla op-level check via PlayerList.isOp().
    // ==================================================================

    static boolean checkNeoForgePermission(Object player, String node) {
        try {
            Class<?> apiCls = Class.forName("net.neoforged.neoforge.server.permission.PermissionAPI");
            java.lang.reflect.Method m = apiCls.getMethod("getPermission",
                Class.forName("net.minecraft.server.level.ServerPlayer"), String.class);
            Object tristate = m.invoke(null, player, node);
            if (tristate != null) {
                String ts = tristate.toString();
                if ("TRUE".equals(ts)) return true;
                if ("FALSE".equals(ts)) return false;
            }
        } catch (Throwable t) { /* PermissionAPI not present */ }
        return isPlayerOperator(player);
    }

    @Override public boolean hasPermission(String node) {
        if (source == null) return false;
        UUID playerId = extractPlayerUuid(source);
        if (playerId != null) {
            Object player = resolvePlayer(playerId);
            if (player != null) return checkNeoForgePermission(player, node);
        }
        return checkVanillaOpLevel(source, 2);
    }

    @Override public boolean hasPermissionForPlayer(UUID playerId, String node) {
        try {
            Object player = resolvePlayer(playerId);
            if (player == null) return false;
            return checkNeoForgePermission(player, node);
        } catch (Throwable t) { return false; }
    }

    private Object resolvePlayer(UUID playerId) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return null;
            Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (pm == null) return null;
            return NeoForgeReflection.call(pm, "getPlayer",
                new Class<?>[]{UUID.class}, new Object[]{playerId});
        } catch (Throwable t) { return null; }
    }

    static boolean isPlayerOperator(Object player) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return false;
            Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (pm == null) return false;
            Object profile = NeoForgeReflection.callAny(player, "getGameProfile",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (profile == null) return false;
            Object result = NeoForgeReflection.callAny(pm, "isOp",
                new Class<?>[]{profile.getClass()}, new Object[]{profile});
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) { return false; }
    }

    private boolean checkVanillaOpLevel(Object source, int requiredLevel) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return false;
            if (isPlayer()) {
                UUID id = extractPlayerUuid(source);
                if (id != null) {
                    Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                        NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                    if (pm != null) {
                        Object player = NeoForgeReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{id});
                        if (player != null) return isPlayerOperator(player);
                    }
                }
            }
            return isConsole();
        } catch (Throwable t) { return false; }
    }
}
