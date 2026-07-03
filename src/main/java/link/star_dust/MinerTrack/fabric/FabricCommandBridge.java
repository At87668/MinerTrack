package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

/**
 * Fabric CommandBridge wrapping ServerCommandSource / CommandSourceStack (held as Object).
 * Permission: Fabric Permission API (LP) -> op-level -> console bypass.
 * Text: Text.literal() (1.19.3+) / LiteralText (1.18-1.19.2) fallback.
 * sendMessage: 1-arg / 2-arg fallback for cross-version compatibility.
 */
public class FabricCommandBridge implements CommandBridge {
    private final Object source;
    private final Set<UUID> verbosePlayers;
    private volatile boolean verboseConsole = false;

    public FabricCommandBridge(Object source, Set<UUID> verbosePlayers) {
        this.source = source;
        this.verbosePlayers = verbosePlayers;
    }

    /**
     * Create a Minecraft Text component from a plain string.
     * Uses Text.literal() on 1.19.3+ and LiteralText constructor on 1.18-1.19.2.
     * Returns null if neither approach works.
     */
    private static Object createText(String message) {
        try {
            Class<?> textCls = Class.forName("net.minecraft.text.Text");
            try {
                // 1.19.3+: Text.literal(String)
                Method literal = textCls.getMethod("literal", String.class);
                return literal.invoke(null, message);
            } catch (NoSuchMethodException e) {
                // 1.18 - 1.19.2: new LiteralText(String)
                Class<?> ltCls = Class.forName("net.minecraft.text.LiteralText");
                return ltCls.getDeclaredConstructor(String.class).newInstance(message);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Send a Text component to a target (ServerCommandSource / CommandSourceStack or PlayerEntity).
     * Tries sendMessage(Text) first, then sendMessage(Text, boolean) as fallback.
     */
    private static void sendMessage0(Object target, Object text) {
        if (text == null || target == null) return;
        try {
            Class<?> textCls = Class.forName("net.minecraft.text.Text");
            // Try single-arg sendMessage(Text) first (1.18+, works on both ServerCommandSource and newer CommandSourceStack)
            try {
                Method m = target.getClass().getMethod("sendMessage", textCls);
                m.invoke(target, text);
                return;
            } catch (NoSuchMethodException ignored) {}
            // Fallback: sendMessage(Text, boolean) (1.18-1.19.3 player entities)
            Method m = target.getClass().getMethod("sendMessage", textCls, boolean.class);
            m.invoke(target, text, false);
        } catch (Throwable ignored) {}
    }

    @Override public void dispatchCommand(String command) {
        try {
            if (source == null) return;
            Object server = FabricReflection.callAny(source, "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            Object cmdManager = FabricReflection.callAny(server, "getCommandManager", new Class<?>[0], new Object[0]);
            if (cmdManager == null) return;
            FabricReflection.callAny(cmdManager, "executeWithPrefix",
                new Class<?>[]{source.getClass(), String.class},
                new Object[]{source, command});
        } catch (Throwable t) { /* silent */ }
    }
    @Override public boolean isPlayer() {
        try {
            Object r = FabricReflection.callAny(source, "isExecutedByPlayer", new Class<?>[0], new Object[0]);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) { return false; }
    }
    @Override public boolean isConsole() { return !isPlayer(); }
    @Override public Object getSender() { return source; }
    @Override public void sendMessage(String message) {
        if (source == null) return;
        try {
            Object text = createText(message);
            if (text != null) { sendMessage0(source, text); return; }
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
        } catch (Throwable t) { /* silent */ }
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
        if (source != null) {
            Object r = FabricReflection.callAny(source, "isExecutedByPlayer", new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) {
                UUID id = null;
                try {
                    Object player = FabricReflection.callAny(source, "getPlayer", new Class<?>[0], new Object[0]);
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

    /** Resolve Fabric Permission API Actor class (lazy lookup). LP hooks in automatically. */
    private static Class<?> resolveActorCls() {
        return FabricReflection.forName("net.fabricmc.fabric.api.permission.v1.Actor");
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
        if (source == null) return false;
        // 1) Fabric Permission API (LuckPerms if installed)
        if (checkFabricPermission(source, node)) return true;
        // 2) op-level >= 2 or console bypass
        try {
            Object r = FabricReflection.callAny(source, "isExecutedByPlayer", new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) {
                Object lvl = FabricReflection.callAny(source, "hasPermissionLevel",
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
}
