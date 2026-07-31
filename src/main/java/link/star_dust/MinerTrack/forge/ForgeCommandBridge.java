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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Forge CommandBridge wrapping CommandSourceStack (held as Object).
 *
 * <p>Mirrors FabricCommandBridge, using the same reflection-based approach
 * for sending messages, dispatching commands, and checking permissions.
 * Forge uses Mojang names as Minecraft class names are transparently
 * resolved through ForgeReflection.
 */
public class ForgeCommandBridge implements CommandBridge {
    private final Object source;
    private final Set<UUID> verbosePlayers;
    private volatile boolean verboseConsole = false;

    public ForgeCommandBridge(Object source, Set<UUID> verbosePlayers) {
        this.source = source;
        this.verbosePlayers = verbosePlayers;
    }

    private static Object createText(String message) {
        return ForgeReflection.createText(message);
    }

    @Override public void dispatchCommand(String command) {
        try {
            if (source == null) return;
            Object server = ForgeReflection.callAny(source, "getServer",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (server == null) return;
            Object cmdManager = ForgeReflection.callAny(server, "getCommands",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (cmdManager == null) {
                cmdManager = ForgeReflection.callAny(server, "getCommandManager",
                    ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            }
            if (cmdManager == null) return;
            Class<?> cssCls = ForgeReflection.forName("net.minecraft.commands.CommandSourceStack");
            if (cssCls == null) return;
            try {
                ForgeReflection.callAny(cmdManager, "performPrefixedCommand",
                    new Class<?>[]{cssCls, String.class},
                    new Object[]{source, command});
            } catch (Throwable t1) {
                try {
                    ForgeReflection.callAny(cmdManager, "performCommand",
                        new Class<?>[]{cssCls, String.class},
                        new Object[]{source, command});
                } catch (Throwable t2) {
                    ForgeReflection.callAny(cmdManager, "executeWithPrefix",
                        new Class<?>[]{cssCls, String.class},
                        new Object[]{source, command});
                }
            }
        } catch (Throwable t) { /* silent */ }
    }

    @Override public boolean isPlayer() {
        try {
            Object r = ForgeReflection.callAny(source, "isPlayer",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (r instanceof Boolean && (Boolean) r) return true;
        } catch (Throwable t) {}
        try {
            Object entity = ForgeReflection.callAny(source, "getEntity",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (entity != null) {
                Class<?> serverPlayer = ForgeReflection.forName("net.minecraft.server.level.ServerPlayer");
                if (serverPlayer != null && serverPlayer.isInstance(entity)) return true;
            }
        } catch (Throwable t) {}
        try {
            Object r = ForgeReflection.callAny(source, "isExecutedByPlayer",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) { return false; }
    }

    @Override public boolean isConsole() { return !isPlayer(); }

    @Override public Object getSender() { return source; }

    @Override
    public void sendMessage(String message) {
        if (source == null) { System.out.println("[MinerTrack] " + message); return; }
        Object text = createText(message);
        if (text == null) { System.out.println("[MinerTrack] " + message); return; }
        if (!fabricSendFeedback(source, text, true))
            System.out.println("[MinerTrack] " + message);
    }

    @Override
    public void sendSuccess(String message) {
        if (source == null) { System.out.println("[MinerTrack] " + message); return; }
        Object text = createText(message);
        if (text == null) { System.out.println("[MinerTrack] " + message); return; }
        if (!fabricSendFeedback(source, text, true))
            System.out.println("[MinerTrack] " + message);
    }

    @Override
    public void sendFailure(String message) {
        if (source == null) { System.out.println("[MinerTrack] " + message); return; }
        Object text = createText(message);
        if (text == null) { System.out.println("[MinerTrack] " + message); return; }
        if (!fabricSendFeedback(source, text, false))
            System.out.println("[MinerTrack] " + message);
    }

    /**
     * Replicated from FabricCommandBridge.sendFeedback -> uses the same
     * multi-version fallback strategy.
     */
    private static boolean fabricSendFeedback(Object target, Object text, boolean isSuccess) {
        if (text == null || target == null) return false;
        Class<?> textCls = ForgeReflection.resolveTextComponentClass();
        if (textCls == null) return false;
        Class<?> targetCls = target.getClass();

        if (isSuccess) {
            boolean oldDebug = ForgeReflection.DEBUG_REFLECTION;
            ForgeReflection.DEBUG_REFLECTION = false;
            try {
                try {
                    Method m = ForgeReflection.findMethod(targetCls, "sendSuccess",
                        new Class<?>[]{Supplier.class, boolean.class});
                    if (m != null) {
                        final Object t = text;
                        m.invoke(target, (Supplier<?>) () -> t, false);
                        return true;
                    }
                } catch (Throwable t) {}
                try {
                    Method m = ForgeReflection.findMethod(targetCls, "sendSuccess",
                        new Class<?>[]{textCls, boolean.class});
                    if (m != null) {
                        m.invoke(target, text, false);
                        return true;
                    }
                } catch (Throwable t) {}
            } finally {
                ForgeReflection.DEBUG_REFLECTION = oldDebug;
            }
        }
        if (!isSuccess) {
            try {
                Method m = ForgeReflection.findMethod(targetCls, "sendFailure",
                    new Class<?>[]{textCls});
                if (m != null) {
                    m.invoke(target, text);
                    return true;
                }
            } catch (Throwable t) {}
        }
        try {
            Method m = ForgeReflection.findMethod(targetCls, "sendSystemMessage",
                new Class<?>[]{textCls});
            if (m != null) { m.invoke(target, text); return true; }
        } catch (Throwable t) {}
        try {
            Method m = ForgeReflection.findMethod(targetCls, "sendMessage",
                new Class<?>[]{textCls, UUID.class});
            if (m != null) { m.invoke(target, text, UUID.randomUUID()); return true; }
        } catch (Throwable t) {}
        try {
            Method m = ForgeReflection.findMethod(targetCls, "sendMessage",
                new Class<?>[]{textCls, boolean.class});
            if (m != null) { m.invoke(target, text, false); return true; }
        } catch (Throwable t) {}
        try {
            Method m = ForgeReflection.findMethod(targetCls, "sendMessage",
                new Class<?>[]{textCls});
            if (m != null) { m.invoke(target, text); return true; }
        } catch (Throwable t) {}
        return false;
    }

    @Override public void sendMessageToPlayer(UUID playerId, String message) {
        try {
            Object server = ForgeReflection.getServer();
            if (server == null) return;
            Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (pm == null) return;
            Object player = ForgeReflection.call(pm, "getPlayer",
                new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            Object text = createText(message);
            if (text == null) return;
            Class<?> textCls = ForgeReflection.resolveTextComponentClass();
            if (textCls == null) return;
            try {
                ForgeReflection.invokeBySigOrThrow(player,
                    new Class<?>[]{textCls, UUID.class},
                    new Object[]{text, UUID.randomUUID()});
            } catch (Throwable t1) {
                try {
                    ForgeReflection.invokeBySigOrThrow(player,
                        new Class<?>[]{textCls}, new Object[]{text});
                } catch (Throwable t2) {
                    try {
                        ForgeReflection.invokeBySigOrThrow(player,
                            new Class<?>[]{textCls, boolean.class},
                            new Object[]{text, false});
                    } catch (Throwable t3) {}
                }
            }
        } catch (Throwable t) {}
    }

    @Override public void sendMessageToConsole(String message) {
        try {
            Object server = ForgeReflection.getServer();
            if (server == null) return;
            Object text = createText(message);
            if (text == null) return;
            Class<?> textCls = ForgeReflection.resolveTextComponentClass();
            if (textCls == null) return;
            try {
                ForgeReflection.invokeBySigOrThrow(server,
                    new Class<?>[]{textCls}, new Object[]{text});
            } catch (Throwable t1) {
                try {
                    ForgeReflection.invokeBySigOrThrow(server,
                        new Class<?>[]{textCls, UUID.class},
                        new Object[]{text, UUID.randomUUID()});
                } catch (Throwable t2) {}
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
        verboseConsole = !verboseConsole; return verboseConsole;
    }

    private static UUID extractPlayerUuid(Object css) {
        if (css == null) return null;
        for (String methodName : new String[]{"getPlayer", "getEntity"}) {
            Object entity = ForgeReflection.callAny(css, methodName,
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (entity != null) {
                Object uid = ForgeReflection.callUuid(entity);
                if (uid instanceof UUID) return (UUID) uid;
            }
        }
        return null;
    }

    /** Cached list of all Permissions.check() overloads. */
    private static volatile List<Method> allCheckMethods;

    private static List<Method> findAllCheckMethods() {
        if (allCheckMethods != null) return allCheckMethods;
        List<Method> result = new ArrayList<>();
        try {
            Class<?> permsCls = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            for (Method m : permsCls.getMethods()) {
                if (m.getName().equals("check") && m.getParameterCount() == 3) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts[1] == String.class && (pts[2] == int.class || pts[2] == Integer.class))
                        result.add(m);
                }
            }
            for (Method m : permsCls.getDeclaredMethods()) {
                if (m.getName().equals("check") && m.getParameterCount() == 3) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts[1] == String.class && (pts[2] == int.class || pts[2] == Integer.class)) {
                        boolean dup = false;
                        for (Method existing : result) {
                            if (existing.equals(m)) { dup = true; break; }
                        }
                        if (!dup) result.add(m);
                    }
                }
            }
        } catch (Throwable t) {
            result = java.util.Collections.emptyList();
        }
        allCheckMethods = result;
        return result;
    }

    static boolean checkLPPermission(Object source, String node, int defaultOpLevel) {
        for (Method m : findAllCheckMethods()) {
            Class<?> sourceType = m.getParameterTypes()[0];
            if (!sourceType.isInstance(source)) continue;
            try {
                Object result = m.invoke(null, source, node, defaultOpLevel);
                return result instanceof Boolean && (Boolean) result;
            } catch (Throwable t) { return false; }
        }
        return false;
    }

    @Override
    public boolean hasPermission(String node) {
        if (source == null) return false;
        if (checkLPPermission(source, node, 2)) return true;
        return checkVanillaOpLevel(source, 2);
    }

    @Override
    public boolean hasPermissionForPlayer(UUID playerId, String node) {
        try {
            Object server = ForgeReflection.getServer();
            if (server == null) return false;
            Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (pm == null) return false;
            Object player = ForgeReflection.call(pm, "getPlayer",
                new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return false;
            if (checkLPPermission(player, node, 2)) return true;
            return isPlayerOperator(player);
        } catch (Throwable t) { return false; }
    }

    static boolean isPlayerOperator(Object player) {
        try {
            Object server = ForgeReflection.getServer();
            if (server == null) return false;
            Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (pm == null) return false;
            Object profile = ForgeReflection.callAny(player, "getGameProfile",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (profile == null) return false;
            Object result = ForgeReflection.callAny(pm, "isOp",
                new Class<?>[]{profile.getClass()}, new Object[]{profile});
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) { return false; }
    }

    private boolean checkVanillaOpLevel(Object source, int requiredLevel) {
        try {
            Object server = ForgeReflection.getServer();
            if (server == null) return false;
            if (isPlayer()) {
                UUID id = extractPlayerUuid(source);
                if (id != null) {
                    Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    if (pm != null) {
                        Object player = ForgeReflection.call(pm, "getPlayer",
                            new Class<?>[]{UUID.class}, new Object[]{id});
                        if (player != null) return isPlayerOperator(player);
                    }
                }
            }
            // Console always has full permission
            return isConsole();
        } catch (Throwable t) { return false; }
    }
}
