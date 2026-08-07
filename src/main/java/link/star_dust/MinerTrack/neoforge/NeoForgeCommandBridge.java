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
        if (!sendFeedback(source, text, true)) {
            System.out.println("[MinerTrack] (sendFeedback failed, source=" + source.getClass().getName() + ") " + message);
        }
    }

    @Override public void sendSuccess(String message) {
        if (source == null) { System.out.println("[MinerTrack] " + message); return; }
        Object text = createText(message);
        if (text == null) { System.out.println("[MinerTrack] " + message); return; }
        if (!sendFeedback(source, text, true)) {
            System.out.println("[MinerTrack] (sendSuccess feedback failed, source=" + source.getClass().getName() + ") " + message);
        }
    }

    @Override public void sendFailure(String message) {
        if (source == null) { System.out.println("[MinerTrack] " + message); return; }
        Object text = createText(message);
        if (text == null) { System.out.println("[MinerTrack] " + message); return; }
        if (!sendFeedback(source, text, false)) {
            System.out.println("[MinerTrack] (sendFailure feedback failed, source=" + source.getClass().getName() + ") " + message);
        }
    }

    private static boolean sendFeedback(Object target, Object text, boolean isSuccess) {
        if (text == null || target == null) return false;
        Class<?> textCls = NeoForgeReflection.resolveTextComponentClass();
        if (textCls == null) return false;
        Class<?> targetCls = target.getClass();

        // sendSystemMessage(Component) FIRST — direct and reliable on MC 1.19.3+
        // / 26.x. sendSuccess(Supplier,boolean) can silently no-op when
        // source.acceptsSuccess() returns false (it returns void regardless),
        // which made command feedback appear to do nothing on 26.2.
        try {
            Method m = NeoForgeReflection.findMethod(targetCls, "sendSystemMessage",
                new Class<?>[]{textCls});
            if (m != null) { m.invoke(target, text); return true; }
        } catch (Throwable t) { /* fall through */ }

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
                Method m = NeoForgeReflection.findMethod(targetCls, "sendFailure",
                    new Class<?>[]{textCls});
                if (m != null) { m.invoke(target, text); return true; }
            } catch (Throwable t) {}
        }
        try { Method m = NeoForgeReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{textCls, UUID.class}); if (m != null) { m.invoke(target, text, UUID.randomUUID()); return true; } } catch (Throwable t) {}
        try { Method m = NeoForgeReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{textCls, boolean.class}); if (m != null) { m.invoke(target, text, false); return true; } } catch (Throwable t) {}
        try { Method m = NeoForgeReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{textCls}); if (m != null) { m.invoke(target, text); return true; } } catch (Throwable t) {}
        return false;
    }

    @Override public void sendMessageToPlayer(UUID playerId, String message) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: server null"); return; }
            Object pm = NeoForgeReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (pm == null) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: pm null"); return; }
            Object player = NeoForgeReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: player null for " + playerId); return; }
            Object text = createText(message);
            if (text == null) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: createText null for " + message); return; }
            Class<?> textCls = NeoForgeReflection.resolveTextComponentClass();
            if (textCls == null) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: textCls null"); return; }
            // MC 26.x ServerPlayer no longer has sendMessage(Component[,UUID]);
            // it uses sendSystemMessage(Component). Try that first, then the
            // legacy sendMessage variants for older versions.
            try { NeoForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls}, new Object[]{text}); System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: OK (Component)"); }
            catch (Throwable t1) {
                System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: (Component) FAIL " + t1);
                try { NeoForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls, UUID.class}, new Object[]{text, UUID.randomUUID()}); System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: OK (Component,UUID)"); }
                catch (Throwable t2) {
                    try { NeoForgeReflection.invokeBySigOrThrow(player, new Class<?>[]{textCls, boolean.class}, new Object[]{text, false}); System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: OK (Component,boolean)"); }
                    catch (Throwable t3) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: ALL FAIL " + t3); }
                }
            }
        } catch (Throwable t) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToPlayer: outer FAIL " + t); }
    }

    @Override public void sendMessageToConsole(String message) {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToConsole: server null"); return; }
            Object text = createText(message);
            if (text == null) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToConsole: createText null for " + message); return; }
            Class<?> textCls = NeoForgeReflection.resolveTextComponentClass();
            if (textCls == null) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToConsole: textCls null"); return; }
            // MC 26.x MinecraftServer uses sendSystemMessage(Component).
            try { NeoForgeReflection.invokeBySigOrThrow(server, new Class<?>[]{textCls}, new Object[]{text}); System.out.println("[MinerTrack:DIAG] bridge sendMessageToConsole: OK (Component)"); }
            catch (Throwable t1) {
                System.out.println("[MinerTrack:DIAG] bridge sendMessageToConsole: (Component) FAIL " + t1);
                try { NeoForgeReflection.invokeBySigOrThrow(server, new Class<?>[]{textCls, UUID.class}, new Object[]{text, UUID.randomUUID()}); System.out.println("[MinerTrack:DIAG] bridge sendMessageToConsole: OK (Component,UUID)"); }
                catch (Throwable t2) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToConsole: ALL FAIL " + t2); }
            }
        } catch (Throwable t) { System.out.println("[MinerTrack:DIAG] bridge sendMessageToConsole: outer FAIL " + t); }
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
    // which integrates with any NeoForge-compatible permission handler.
    // We register the minertrack.* PermissionNodes via NeoForgePermissionRegistry
    // and query through the native API, so the active permission handler
    // decides.  Called reflectively to avoid compile-time coupling.
    // Fallback: vanilla op-level check via PlayerList.isOp().
    // ==================================================================

    static boolean checkNeoForgePermission(Object player, String node) {
        // 1) LuckPerms direct — works on hybrid servers (Arclight/Mohist) where
        //    the active NeoForge permission handler is replaced by the hybrid's
        //    own handler which forwards to Bukkit, hiding LuckPerms grants from
        //    the native PermissionAPI. Querying LuckPerms directly always
        //    reflects the /lp grants. Optional, so a missing LuckPerms is
        //    silently skipped.
        Boolean lp = checkNeoForgeLuckPerms(player, node);
        if (lp != null) return lp;
        // 2) Native PermissionAPI using a registered PermissionNode (1.20.4+).
        Object cachedNode = NeoForgePermissionRegistry.getNode(node);
        if (cachedNode != null) {
            try {
                Class<?> apiCls = Class.forName("net.neoforged.neoforge.server.permission.PermissionAPI");
                Class<?> playerCls = Class.forName("net.minecraft.server.level.ServerPlayer");
                java.lang.reflect.Method m = apiCls.getMethod("getPermission", playerCls, cachedNode.getClass());
                Object result = m.invoke(null, player, cachedNode);
                if (result instanceof Boolean) return (Boolean) result;
                if (result != null) {
                    String ts = result.toString();
                    if ("TRUE".equals(ts)) return true;
                    if ("FALSE".equals(ts)) return false;
                }
            } catch (Throwable t) { /* PermissionAPI not present */ }
        }
        // 3) Fallback: scan getRegisteredNodes() for a node whose name matches
        //    (in case the handler filters nodes or the cache was empty).
        try {
            Class<?> apiCls = Class.forName("net.neoforged.neoforge.server.permission.PermissionAPI");
            Class<?> playerCls = Class.forName("net.minecraft.server.level.ServerPlayer");
            Object nodes = apiCls.getMethod("getRegisteredNodes").invoke(null);
            if (nodes instanceof java.util.Collection) {
                for (Object n : (java.util.Collection<?>) nodes) {
                    String nodeName = (String) n.getClass().getMethod("getNodeName").invoke(n);
                    if (node != null && node.equals(nodeName)) {
                        java.lang.reflect.Method m = apiCls.getMethod("getPermission", playerCls, n.getClass());
                        Object result = m.invoke(null, player, n);
                        if (result instanceof Boolean) return (Boolean) result;
                        if (result != null) {
                            String ts = result.toString();
                            if ("TRUE".equals(ts)) return true;
                            if ("FALSE".equals(ts)) return false;
                        }
                    }
                }
            }
        } catch (Throwable t) { /* PermissionAPI not present */ }
        return isPlayerOperator(player);
    }

    /**
     * Query LuckPerms directly for a node and player. Returns {@code null}
     * when LuckPerms is absent, the user is not loaded, or the result is
     * undefined — the caller then falls through to the native API / op level.
     */
    private static Boolean checkNeoForgeLuckPerms(Object player, String node) {
        try {
            UUID uuid = NeoForgePlayerUuid(player);
            if (uuid == null) return null;
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = provider.getMethod("get").invoke(null);
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null) return null;
            Object cached = user.getClass().getMethod("getCachedData").invoke(user);
            Object permData = cached.getClass().getMethod("getPermissionData").invoke(cached);
            Object tristate = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, node);
            if (tristate == null) return null;
            try {
                Object asBool = tristate.getClass().getMethod("asBoolean").invoke(tristate);
                if (asBool instanceof Boolean) return (Boolean) asBool;
            } catch (Throwable t) { /* no asBoolean */ }
            String ts = tristate.toString();
            if (ts != null && ts.contains("TRUE")) return true;
            if (ts != null && ts.contains("FALSE")) return false;
            return null; // DEFAULT / undefined → fall through
        } catch (Throwable t) { return null; } // LuckPerms not present
    }

    /** Resolve a player's UUID (needed for the LuckPerms API lookup). */
    private static UUID NeoForgePlayerUuid(Object player) {
        try { Object u = NeoForgeReflection.callUuid(player); return u instanceof UUID ? (UUID) u : null; }
        catch (Throwable t) { return null; }
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
            // MC 26.x: Player.nameAndId() → NameAndId; PlayerList.isOp(NameAndId)
            // (1.18-1.20 used PlayerList.isOp(GameProfile)).
            Object nameAndId = NeoForgeReflection.callAny(player, "nameAndId",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (nameAndId != null) {
                Object result = NeoForgeReflection.callAny(pm, "isOp",
                    new Class<?>[]{nameAndId.getClass()}, new Object[]{nameAndId});
                if (result instanceof Boolean) return (Boolean) result;
            }
            // Fallback: 1.18-1.20 PlayerList.isOp(GameProfile)
            Object profile = NeoForgeReflection.callAny(player, "getGameProfile",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (profile != null) {
                Object result = NeoForgeReflection.callAny(pm, "isOp",
                    new Class<?>[]{profile.getClass()}, new Object[]{profile});
                if (result instanceof Boolean) return (Boolean) result;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    /**
     * Check vanilla op level, mirroring FabricCommandBridge.checkVanillaOpLevel
     * (the 26.1 reference implementation). The key difference from the old
     * NeoForge code: instead of relying on {@code isPlayer()/isConsole()}
     * (which can return the wrong result when reflection-based player probing
     * fails), we detect a player by {@code getGameProfile()}. If the source
     * is not backed by a player entity (console / command block / RCON), we
     * allow it directly.
     */
    private boolean checkVanillaOpLevel(Object source, int requiredLevel) {
        if (source == null) return false;
        // Console / command block / RCON → always allowed.
        // Detect by trying the player-specific probe first:
        // if source resolves to a player entity, it IS a player.
        Object player = resolvePlayerEntity(source);
        if (player == null) return true; // not a player → console → allowed

        // Player → operator check. NOTE: MC 26.x CommandSourceStack no longer
        // has hasPermission(int) (it uses PermissionSet), so we go straight to
        // the PlayerList.isOp check (which handles NameAndId on 26.x and
        // GameProfile on 1.18-1.20).
        return isPlayerOperator(player);
    }

    /**
     * Extract the ServerPlayer entity from a CommandSourceStack, or return
     * {@code source} itself if it already is a player entity.
     *
     * <p>Uses {@code ServerPlayer.isInstance()} rather than probing
     * {@code getGameProfile()} on the source — CommandSourceStack has no
     * getGameProfile(), so a blind parameter-scan would match an unrelated
     * no-arg method and falsely identify the source as a player (breaking the
     * console detection in checkVanillaOpLevel).
     */
    private static Object resolvePlayerEntity(Object source) {
        if (source == null) return null;
        Class<?> serverPlayerCls = NeoForgeReflection.forName("net.minecraft.server.level.ServerPlayer");
        // 1. Source itself is already a ServerPlayer (DetectionBridge path).
        if (serverPlayerCls != null && serverPlayerCls.isInstance(source)) return source;

        // 2. Extract entity from CommandSourceStack via getEntity() (all versions).
        Object entity = NeoForgeReflection.callAny(source, "getEntity",
            NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
        if (entity != null && serverPlayerCls != null && serverPlayerCls.isInstance(entity)) return entity;

        // 3. getPlayer() on 1.19.1+ (exists on 26.x; blind-scans getDisplayName
        //    on 1.18.2 but that is not a ServerPlayer so it is rejected).
        Object sp = NeoForgeReflection.callAny(source, "getPlayer",
            NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
        if (sp != null && serverPlayerCls != null && serverPlayerCls.isInstance(sp)) return sp;
        return null;
    }
}
