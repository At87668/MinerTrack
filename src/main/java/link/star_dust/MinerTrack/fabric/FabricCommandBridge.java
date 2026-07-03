package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fabric CommandBridge wrapping CommandSourceStack (held as Object).
 *
 * <p>Follows the LuckPerms {@code SenderFactory} abstraction pattern:
 * the platform-specific implementation handles message routing using the
 * correct Fabric API — {@code sendSuccess(Supplier{@literal <Text>}, boolean)}
 * for player-facing info, {@code sendFailure(Text)} for errors, and
 * {@code sendMessage(Text, boolean)} for player entities.
 *
 * <p>Permission: Fabric Permission API (LP) -> op-level -> console bypass.
 * Text: Text.literal() (1.19.3+) / LiteralText (1.18-1.19.2) fallback.
 */
public class FabricCommandBridge implements CommandBridge {
    private final Object source;
    private final Set<UUID> verbosePlayers;
    private volatile boolean verboseConsole = false;

    public FabricCommandBridge(Object source, Set<UUID> verbosePlayers) {
        this.source = source;
        this.verbosePlayers = verbosePlayers;
    }

    // ── Text creation ─────────────────────────────────────────────

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
     * Create a {@code Supplier<Text>} from a message string. Needed for
     * {@code CommandSourceStack.sendSuccess(Supplier<Text>, boolean)}.
     */
    private static Supplier<?> createTextSupplier(String message) {
        Object text = createText(message);
        if (text == null) return null;
        return () -> text;
    }

    // ── Message routing (the core fix) ────────────────────────────

    /**
     * Send a Text component to a CommandSourceStack using the correct
     * Fabric feedback API, with fallbacks for older versions and player
     * entities.
     *
     * <p>Priority order (1.19+ CommandSourceStack):
     * <ol>
     *   <li>{@code sendSuccess(Supplier<Text>, boolean)} — player-facing info
     *   <li>{@code sendFailure(Text)} — error messages (red text)
     *   <li>{@code sendMessage(Text, boolean)} — player entities
     *   <li>{@code sendMessage(Text)} — legacy fallback
     * </ol>
     */
    private static void sendFeedback(Object target, Object text, boolean isSuccess) {
        if (text == null || target == null) return;
        try {
            Class<?> textCls = Class.forName("net.minecraft.text.Text");
            Class<?> targetCls = target.getClass();

            // 1) Try sendSuccess(Supplier<Text>, boolean) — 1.19+ CommandSourceStack
            //    This is the correct method for player-facing messages. When the
            //    source is a player, the message appears in their chat. When it's
            //    the console, it also goes to the server log.
            if (isSuccess) {
                try {
                    Method m = targetCls.getMethod("sendSuccess", Supplier.class, boolean.class);
                    Object supplier = createTextSupplier((String) invokeToString(text));
                    if (supplier != null) {
                        m.invoke(target, supplier, false);
                        return;
                    }
                } catch (NoSuchMethodException ignored) {}
            }

            // 2) Try sendFailure(Text) — 1.19+ CommandSourceStack error messages
            //    This sends red-colored text to the command source (player or console).
            if (!isSuccess) {
                try {
                    Method m = targetCls.getMethod("sendFailure", textCls);
                    m.invoke(target, text);
                    return;
                } catch (NoSuchMethodException ignored) {}
            }

            // 3) Try sendMessage(Text, boolean) — 1.18-1.19.3 player entities
            //    The boolean parameter is the 'overlay' flag (false = chat).
            try {
                Method m = targetCls.getMethod("sendMessage", textCls, boolean.class);
                m.invoke(target, text, false);
                return;
            } catch (NoSuchMethodException ignored) {}

            // 4) Try sendMessage(Text) — 1.18 ServerCommandSource / MinecraftServer
            try {
                Method m = targetCls.getMethod("sendMessage", textCls);
                m.invoke(target, text);
                return;
            } catch (NoSuchMethodException ignored) {}

            // 5) Try sendMessage(Component) — 1.19.3+ Adventure API
            try {
                Class<?> componentCls = Class.forName("net.minecraft.text.Component");
                Method m = targetCls.getMethod("sendMessage", componentCls);
                m.invoke(target, text);
                return;
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    /** Extract a String representation from a Text object via reflection. */
    private static String invokeToString(Object text) {
        try {
            Method m = text.getClass().getMethod("getString");
            Object result = m.invoke(text);
            return result != null ? result.toString() : "";
        } catch (Throwable t) {
            return text.toString();
        }
    }

    // ── CommandBridge implementation ──────────────────────────────

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

    @Override
    public void sendMessage(String message) {
        if (source == null) return;
        try {
            Object text = createText(message);
            if (text != null) {
                // Use sendSuccess (player-facing) for general messages.
                // This routes through CommandSourceStack.sendFeedback()
                // which shows in player chat, not just console.
                sendFeedback(source, text, true);
                return;
            }
        } catch (Throwable ignored) {}
        // Final fallback: write to STDOUT so the server log at least
        // captures the message (better than silent failure).
        System.out.println("[MinerTrack] " + message);
    }

    @Override
    public void sendSuccess(String message) {
        if (source == null) return;
        try {
            Object text = createText(message);
            if (text != null) {
                sendFeedback(source, text, true);
                return;
            }
        } catch (Throwable ignored) {}
        System.out.println("[MinerTrack] " + message);
    }

    @Override
    public void sendFailure(String message) {
        if (source == null) return;
        try {
            Object text = createText(message);
            if (text != null) {
                sendFeedback(source, text, false);
                return;
            }
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
            sendFeedback(player, createText(message), true);
        } catch (Throwable t) { /* silent */ }
    }

    @Override public void sendMessageToConsole(String message) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            sendFeedback(server, createText(message), true);
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
