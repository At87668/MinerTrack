package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

/**
 * Fabric CommandBridge wrapping ServerCommandSource (held as Object to avoid compile-time dep).
 * Permission: Fabric Permission API (LP) -> op-level -> console bypass.
 * Text: Text.literal() / LiteralText fallback. sendMessage: 2-arg / 1-arg fallback.
 */
public class FabricCommandBridge implements CommandBridge {
    private final Object source;
    private final Set<UUID> verbosePlayers;
    private volatile boolean verboseConsole = false;

    private static volatile java.lang.reflect.Executable cachedTextFactory;
    private static volatile boolean textFactoryIsStatic;
    private static volatile Boolean fabricPermissionApiChecked;
    private static volatile Class<?> cachedActorCls;

    public FabricCommandBridge(Object source, Set<UUID> verbosePlayers) {
        this.source = source;
        this.verbosePlayers = verbosePlayers;
    }

    private static Object createText(String message) {
        if (cachedTextFactory != null) {
            try {
                if (textFactoryIsStatic) return ((Method) cachedTextFactory).invoke(null, message);
                else return ((java.lang.reflect.Constructor<?>) cachedTextFactory).newInstance(message);
            } catch (Throwable ignored) { cachedTextFactory = null; }
        }
        Class<?> textCls = FabricReflection.forName("net.minecraft.text.Text");
        if (textCls != null) {
            Method m = FabricReflection.findMethod(textCls, "literal", new Class<?>[]{String.class});
            if (m != null) {
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(null, message);
                    cachedTextFactory = m; textFactoryIsStatic = true; return result;
                } catch (Throwable ignored) {}
            }
        }
        Class<?> ltCls = FabricReflection.forName("net.minecraft.text.LiteralText");
        if (ltCls != null) {
            try {
                java.lang.reflect.Constructor<?> ctor = ltCls.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                Object result = ctor.newInstance(message);
                cachedTextFactory = ctor; textFactoryIsStatic = false; return result;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void sendMessage0(Object target, Object text) {
        if (text == null || target == null) return;
        Class<?> textCls = FabricReflection.forName("net.minecraft.text.Text");
        if (textCls == null) return;
        try {
            // sendMessage(Text) — works on ServerCommandSource (console) and 1.19.4+ players
            Method m = FabricReflection.findMethod(target.getClass(), "sendMessage", new Class<?>[]{textCls});
            if (m != null) {
                m.setAccessible(true);
                m.invoke(target, text);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            // sendMessage(Text, boolean) — 1.18-1.19.3 player entities
            Method m = FabricReflection.findMethod(target.getClass(), "sendMessage", new Class<?>[]{textCls, boolean.class});
            if (m != null) {
                m.setAccessible(true);
                m.invoke(target, text, false);
            }
        } catch (Throwable ignored) {}
    }

    @Override public void dispatchCommand(String command) {
        try {
            Object s = source(); if (s == null) return;
            Object server = FabricReflection.callAny(s, "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            Object cmdManager = FabricReflection.callAny(server, "getCommandManager", new Class<?>[0], new Object[0]);
            if (cmdManager == null) return;
            FabricReflection.callAny(cmdManager, "executeWithPrefix",
                new Class<?>[]{FabricReflection.forName("net.minecraft.server.command.ServerCommandSource"), String.class},
                new Object[]{s, command});
        } catch (Throwable t) {}
    }
    @Override public boolean isPlayer() {
        Object s = source(); if (s == null) return false;
        try {
            Object r = FabricReflection.callAny(s, "isExecutedByPlayer", new Class<?>[0], new Object[0]);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) { return false; }
    }
    @Override public boolean isConsole() { return !isPlayer(); }
    @Override public Object getSender() { return source; }
    @Override public void sendMessage(String message) {
        Object s = source(); if (s == null) return;
        try {
            Object text = createText(message);
            if (text != null) { sendMessage0(s, text); return; }
        } catch (Throwable ignored) {}
        System.out.println("[MinerTrack] " + message);
    }
    @Override public void sendMessageToPlayer(UUID playerId, String message) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
            if (pm == null) return;
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            sendMessage0(player, createText(message));
        } catch (Throwable t) {}
    }
    @Override public void sendMessageToConsole(String message) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            sendMessage0(server, createText(message));
        } catch (Throwable t) { System.out.println("[MinerTrack] " + message); }
    }
    @Override public boolean toggleVerbose() {
        Object s = source();
        if (s != null) {
            Object r = FabricReflection.callAny(s, "isExecutedByPlayer", new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) {
                UUID id = null;
                try {
                    Object player = FabricReflection.callAny(s, "getPlayer", new Class<?>[0], new Object[0]);
                    if (player != null) {
                        Object uuid = FabricReflection.callAny(player, "getUuid", new Class<?>[0], new Object[0]);
                        if (uuid instanceof UUID) id = (UUID) uuid;
                    }
                } catch (Throwable t) { return false; }
                if (id == null) return false;
                if (verbosePlayers.contains(id)) { verbosePlayers.remove(id); return false; }
                else { verbosePlayers.add(id); return true; }
            }
        }
        verboseConsole = !verboseConsole; return verboseConsole;
    }

    // ── Permission checks ──────────────────────────────────────────

    /** Resolve Fabric Permission API Actor class (lazy, cached). LP hooks in automatically. */
    private static Class<?> resolveActorCls() {
        if (fabricPermissionApiChecked != null) return cachedActorCls;
        Class<?> actor = FabricReflection.forName("net.fabricmc.fabric.api.permission.v1.Actor");
        fabricPermissionApiChecked = true; cachedActorCls = actor;
        return actor;
    }

    private boolean checkFabricPermission(Object target, String node) {
        Class<?> actorCls = resolveActorCls();
        if (actorCls == null) return false;
        try {
            Object ctx = FabricReflection.callStatic(actorCls.getName(), "suspendingFallback",
                new Class<?>[]{Object.class}, new Object[]{target});
            if (ctx == null) return false;
            Object result = FabricReflection.callAny(ctx, "checkPermission",
                new Class<?>[]{String.class, int.class}, new Object[]{node, 2});
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) { return false; }
    }

    @Override
    public boolean hasPermission(String node) {
        Object s = source(); if (s == null) return false;
        // 1) Fabric Permission API (LuckPerms if installed)
        if (checkFabricPermission(s, node)) return true;
        // 2) op-level >= 2 or console bypass
        try {
            Object r = FabricReflection.callAny(s, "isExecutedByPlayer", new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) {
                Object lvl = FabricReflection.callAny(s, "hasPermissionLevel",
                    new Class<?>[]{int.class}, new Object[]{2});
                return lvl instanceof Boolean && (Boolean) lvl;
            }
            return true; // console
        } catch (Throwable t) { return false; }
    }

    @Override
    public boolean hasPermissionForPlayer(UUID playerId, String node) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return false;
            Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
            if (pm == null) return false;
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return false;
            if (checkFabricPermission(player, node)) return true;
            Object gameProfile = FabricReflection.callAny(player, "getGameProfile", new Class<?>[0], new Object[0]);
            if (gameProfile != null) {
                Object isOp = FabricReflection.call(pm, "isOperator",
                    new Class<?>[]{gameProfile.getClass()}, new Object[]{gameProfile});
                if (isOp instanceof Boolean) return (Boolean) isOp;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    private Object source() {
        try {
            Class<?> cls = Class.forName("net.minecraft.server.command.ServerCommandSource");
            if (source != null && cls.isInstance(source)) return source;
            return null;
        } catch (Throwable t) { return null; }
    }
}
