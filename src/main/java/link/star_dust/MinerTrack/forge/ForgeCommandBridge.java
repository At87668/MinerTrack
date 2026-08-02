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
        // Use getEntity() first: its Searge redirect (m_81373_) is correct for
        // CommandSourceStack. getPlayer() is NOT used here because its redirect
        // (m_11259_) is PlayerList.getPlayer(UUID) — the wrong signature — which
        // makes findMethodImpl fall through to scanMethod and match an unrelated
        // no-arg method (e.g. getPlayerNames() returning a KeySet).
        Object entity = ForgeReflection.callAny(css, "getEntity",
            ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
        if (entity != null) {
            Object uid = ForgeReflection.callUuid(entity);
            if (uid instanceof UUID) return (UUID) uid;
        }
        return null;
    }

    // ==================================================================
    // Permission checks via native Forge PermissionAPI
    //
    // Forge ships net.minecraftforge.server.permission.PermissionAPI
    // which integrates with LuckPerms or any Forge-compatible permission
    // plugin.  We call it reflectively to avoid compile-time coupling.
    // Fallback: vanilla op-level check via PlayerList.isOp().
    // ==================================================================

    static boolean checkForgePermission(Object player, String node) {
        try {
            // PermissionAPI.getPermission(ServerPlayer, String) → Tristate
            Class<?> apiCls = Class.forName("net.minecraftforge.server.permission.PermissionAPI");
            java.lang.reflect.Method m = apiCls.getMethod("getPermission",
                Class.forName("net.minecraft.server.level.ServerPlayer"), String.class);
            Object tristate = m.invoke(null, player, node);
            if (tristate != null) {
                // Tristate.TRUE, Tristate.FALSE, Tristate.DEFAULT
                String ts = tristate.toString();
                if ("TRUE".equals(ts)) return true;
                if ("FALSE".equals(ts)) return false;
                // DEFAULT → fall through to op-level
            }
        } catch (Throwable t) { /* PermissionAPI not present */ }
        return isPlayerOperator(player);
    }

    @Override
    public boolean hasPermission(String node) {
        if (source == null) return false;
        // Try native permission on underlying player entity
        UUID playerId = extractPlayerUuid(source);
        if (playerId != null) {
            Object player = resolvePlayer(playerId);
            if (player != null) return checkForgePermission(player, node);
        }
        return checkVanillaOpLevel(source, 2);
    }

    @Override
    public boolean hasPermissionForPlayer(UUID playerId, String node) {
        try {
            Object player = resolvePlayer(playerId);
            if (player == null) return false;
            return checkForgePermission(player, node);
        } catch (Throwable t) { return false; }
    }

    private Object resolvePlayer(UUID playerId) {
        try {
            Object server = ForgeReflection.getServer();
            if (server == null) return null;
            Object pm = ForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (pm == null) return null;
            return ForgeReflection.call(pm, "getPlayer",
                new Class<?>[]{UUID.class}, new Object[]{playerId});
        } catch (Throwable t) { return null; }
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
            return isConsole();
        } catch (Throwable t) { return false; }
    }
}
