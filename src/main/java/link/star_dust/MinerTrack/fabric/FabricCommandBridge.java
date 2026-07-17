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
 * <p>Permission: fabric-permissions-api (delegates to LuckPerms if installed,
 * falls back to op-level >= 2 otherwise). Called via reflection to avoid
 * compile-time coupling to Minecraft types that aren't on our classpath.
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
     * Create a Minecraft Text/Component from a plain string.
     * Uses Component.literal() (MC 26.1+/1.19.3+), Text.literal() (1.19.3+),
     * or LiteralText constructor (1.18-1.19.2).
     * Returns null if no approach works.
     */
    private static Object createText(String message) {
        // 1. Try Component.literal(String) — MC 26.1+ (Text → Component)
        try {
            Class<?> compCls = Class.forName("net.minecraft.network.chat.Component");
            Method literal = compCls.getMethod("literal", String.class);
            return literal.invoke(null, message);
        } catch (Throwable t) { /* fall through */ }

        // 2. Try Component.literal(String) — also handles MC 1.19.3+ via tryMcMigration
        try {
            Class<?> textCls = FabricReflection.forName("net.minecraft.network.chat.Component");
            if (textCls != null) {
                Method literal = textCls.getMethod("literal", String.class);
                return literal.invoke(null, message);
            }
        } catch (Throwable t) { /* fall through */ }

        // 3. Fallback: new TextComponent(String) — MC 1.18-1.19.2
        try {
            Class<?> ltCls = FabricReflection.forName("net.minecraft.network.chat.TextComponent");
            return ltCls.getDeclaredConstructor(String.class).newInstance(message);
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
     * Fabric feedback API, with fallbacks for older versions.
     *
     * <p>Priority order:
     * <ol>
     *   <li>{@code sendSuccess(Supplier<Component>, boolean)} — 1.21.1+
     *   <li>{@code sendSuccess(Component, boolean)} — 1.18.2
     *   <li>{@code sendFailure(Component)} — all versions
     *   <li>{@code sendSystemMessage(Component)} — 1.21.1+ CommandSourceStack
     *   <li>{@code sendMessage(Component, UUID)} — 1.18.2 CommandSourceStack
     *   <li>{@code sendMessage(Text, boolean)} — player entity fallback
     * </ol>
     */
    private static void sendFeedback(Object target, Object text, boolean isSuccess) {
        if (text == null || target == null) return;
        try {
            Class<?> textCls = resolveTextComponentClass();
            if (textCls == null) return;
            Class<?> targetCls = target.getClass();

            // 1a) sendSuccess(Supplier<Component>, boolean) — 1.21.1+
            if (isSuccess) {
                try {
                    Method m = FabricReflection.findMethod(targetCls, "sendSuccess",
                        new Class<?>[]{Supplier.class, boolean.class});
                    if (m != null) {
                        Object supplier = createTextSupplier((String) invokeToString(text));
                        if (supplier != null) {
                            m.invoke(target, supplier, false);
                            return;
                        }
                    }
                } catch (Throwable t) { /* fall through */ }

                // 1b) sendSuccess(Component, boolean) — 1.18.2
                try {
                    Method m = FabricReflection.findMethod(targetCls, "sendSuccess",
                        new Class<?>[]{textCls, boolean.class});
                    if (m != null) {
                        m.invoke(target, text, false);
                        return;
                    }
                } catch (Throwable t) { /* fall through */ }
            }

            // 2) sendFailure(Component) — same signature on all versions
            if (!isSuccess) {
                try {
                    Method m = FabricReflection.findMethod(targetCls, "sendFailure",
                        new Class<?>[]{textCls});
                    if (m != null) {
                        m.invoke(target, text);
                        return;
                    }
                } catch (Throwable t) { /* fall through */ }
            }

            // 3) sendSystemMessage(Component) — 1.21.1+ CommandSourceStack
            try {
                Method m = FabricReflection.findMethod(targetCls, "sendSystemMessage",
                    new Class<?>[]{textCls});
                if (m != null) {
                    m.invoke(target, text);
                    return;
                }
            } catch (Throwable t) { /* fall through */ }

            // 4) sendMessage(Component, UUID) — 1.18.2 sendSuccess delegates to this
            try {
                Method m = FabricReflection.findMethod(targetCls, "sendMessage",
                    new Class<?>[]{textCls, UUID.class});
                if (m != null) {
                    m.invoke(target, text, java.util.UUID.randomUUID());
                    return;
                }
            } catch (Throwable t) { /* fall through */ }

            // 5) sendMessage(Text, boolean) — player entity / legacy
            try {
                Method m = FabricReflection.findMethod(targetCls, "sendMessage",
                    new Class<?>[]{textCls, boolean.class});
                if (m != null) {
                    m.invoke(target, text, false);
                    return;
                }
            } catch (Throwable t) { /* fall through */ }

            // 6) sendMessage(Text) — last-resort legacy
            try {
                Method m = FabricReflection.findMethod(targetCls, "sendMessage",
                    new Class<?>[]{textCls});
                if (m != null) {
                    m.invoke(target, text);
                    return;
                }
            } catch (Throwable t) { /* fall through */ }

        } catch (Throwable t) {
            // Outer catch: unexpected errors, fall through to println
        }
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
            // MC 26.1+: getCommands(); 1.18-1.21: getCommands() — same name on ALL versions!
            Object cmdManager = FabricReflection.callAny(server, "getCommands", new Class<?>[0], new Object[0]);
            if (cmdManager == null) {
                // Legacy fallback for old mappings that reported getCommandManager
                cmdManager = FabricReflection.callAny(server, "getCommandManager", new Class<?>[0], new Object[0]);
            }
            if (cmdManager == null) return;
            // MC 1.21.1+: performPrefixedCommand(CommandSourceStack, String) — void
            // MC 1.18.2:  performCommand(CommandSourceStack, String) — int
            Class<?> cssCls = resolveCommandSourceStackClass();
            if (cssCls == null) return;
            try {
                FabricReflection.callAny(cmdManager, "performPrefixedCommand",
                    new Class<?>[]{cssCls, String.class},
                    new Object[]{source, command});
            } catch (Throwable t1) {
                try {
                    FabricReflection.callAny(cmdManager, "performCommand",
                        new Class<?>[]{cssCls, String.class},
                        new Object[]{source, command});
                } catch (Throwable t2) {
                    FabricReflection.callAny(cmdManager, "executeWithPrefix",
                        new Class<?>[]{cssCls, String.class},
                        new Object[]{source, command});
                }
            }
        } catch (Throwable t) { /* silent */ }
    }

    private static Class<?> resolveCommandSourceStackClass() {
        return FabricReflection.forName("net.minecraft.commands.CommandSourceStack");
    }

    @Override public boolean isPlayer() {
        try {
            // 1.21.1+: isPlayer()
            Object r = FabricReflection.callAny(source, "isPlayer",
                new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) return true;
        } catch (Throwable t) { /* fall through */ }
        // 1.18.2: check if getEntity() returns a non-null player
        try {
            Object entity = FabricReflection.callAny(source, "getEntity",
                new Class<?>[0], new Object[0]);
            if (entity != null) {
                Class<?> serverPlayer = FabricReflection.forName("net.minecraft.server.level.ServerPlayer");
                if (serverPlayer != null && serverPlayer.isInstance(entity)) return true;
            }
        } catch (Throwable t) { /* fall through */ }
        // Legacy fallback
        try {
            Object r = FabricReflection.callAny(source, "isExecutedByPlayer",
                new Class<?>[0], new Object[0]);
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
            Object server = FabricReflection.getServer();
            if (server == null) return;
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return;
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            Object text = createText(message);
            if (text == null) return;
            Class<?> textCls = resolveTextComponentClass();
            if (textCls == null) return;

            // 1. 1.21.1+: sendSystemMessage(Component)
            try {
                FabricReflection.callAny(player, "sendSystemMessage",
                    new Class<?>[]{textCls}, new Object[]{text});
                return;
            } catch (Throwable t1) { /* fall through */ }

            // 2. 1.18.2: sendMessage(Component, UUID)
            try {
                FabricReflection.callAny(player, "sendMessage",
                    new Class<?>[]{textCls, UUID.class},
                    new Object[]{text, playerId});
                return;
            } catch (Throwable t1) { /* fall through */ }

            // 3. Legacy: sendMessage(Text, boolean)
            try {
                FabricReflection.callAny(player, "sendMessage",
                    new Class<?>[]{textCls, boolean.class},
                    new Object[]{text, false});
            } catch (Throwable t2) { /* silent */ }
        } catch (Throwable t) { /* silent */ }
    }

    @Override public void sendMessageToConsole(String message) {
        try {
            Object server = FabricReflection.getServer();
            if (server == null) return;
            Object text = createText(message);
            if (text == null) return;
            Class<?> textCls = resolveTextComponentClass();
            if (textCls == null) return;
            // 1. 1.21.1+: sendSystemMessage(Component)
            try {
                FabricReflection.callAny(server, "sendSystemMessage",
                    new Class<?>[]{textCls}, new Object[]{text});
                return;
            } catch (Throwable t1) { /* fall through */ }
            // 2. 1.18.2: sendMessage(Component, UUID)
            try {
                FabricReflection.callAny(server, "sendMessage",
                    new Class<?>[]{textCls, UUID.class},
                    new Object[]{text, java.util.UUID.randomUUID()});
                return;
            } catch (Throwable t1) { /* fall through */ }
            // 3. Legacy: sendMessage(Text, boolean)
            FabricReflection.callAny(server, "sendMessage",
                new Class<?>[]{textCls, boolean.class},
                new Object[]{text, false});
        } catch (Throwable t) { System.out.println("[MinerTrack] " + message); }
    }

    @Override public boolean toggleVerbose() {
        if (source != null) {
            if (isPlayer()) {
                UUID id = null;
                try {
                    // 1.21.1+: getPlayer()
                    Object player = FabricReflection.callAny(source, "getPlayer",
                        new Class<?>[0], new Object[0]);
                    // 1.18.2: getEntity() returns Entity
                    if (player == null) {
                        player = FabricReflection.callAny(source, "getEntity",
                            new Class<?>[0], new Object[0]);
                    }
                    if (player != null) {
                        Object uuid = FabricReflection.callUuid(player);
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
    //
    // Use Lucko's fabric-permissions-api (me.lucko.fabric.api.permissions.v0.Permissions)
    // via reflection. This is a zero-weight API that delegates to LuckPerms when
    // installed, or falls back to op-level >= 2. Called via reflection to avoid
    // compile-time coupling to Minecraft types (GameProfile, ServerCommandSource)
    // that aren't on our classpath.
    //
    // fabric-permissions-api is compileOnly — NOT bundled in the JAR.
    // LuckPerms ships its own version-matched copy at runtime. When LP is absent,
    // we fall back to vanilla op-level checks.

    /** Cached: true if me.lucko.fabric.api.permissions.v0.Permissions is on the classpath. */
    private static volatile Boolean permissionsApiAvailable;

    private static boolean isPermissionsApiAvailable() {
        if (permissionsApiAvailable != null) return permissionsApiAvailable;
        try {
            Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            permissionsApiAvailable = true;
        } catch (Throwable t) {
            permissionsApiAvailable = false;
        }
        return permissionsApiAvailable;
    }

    /** Permissions.check(Object source, String node, int defaultOpLevel) — cached Method ref. */
    private static volatile Method permissionsCheckMethod;

    private static Method getPermissionsCheckMethod() {
        if (permissionsCheckMethod != null) return permissionsCheckMethod;
        if (!isPermissionsApiAvailable()) return null;
        try {
            Class<?> permsCls = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            permissionsCheckMethod = permsCls.getMethod("check", Object.class, String.class, int.class);
        } catch (Throwable t) {
            permissionsCheckMethod = null;
        }
        return permissionsCheckMethod;
    }

    /**
     * Call {@code Permissions.check(source, node, defaultOpLevel)} reflectively.
     * Returns true if the source has the permission (via LP or op-level fallback).
     * Falls back to vanilla op-level when the API is absent.
     */
    private static boolean checkPermission(Object source, String node, int defaultOpLevel) {
        Method m = getPermissionsCheckMethod();
        if (m != null) {
            try {
                Object result = m.invoke(null, source, node, defaultOpLevel);
                return result instanceof Boolean && (Boolean) result;
            } catch (Throwable t) {
                return false;
            }
        }
        // fabric-permissions-api not on classpath — fall back to vanilla op-level.
        return checkVanillaOpLevel(source, defaultOpLevel);
    }

    /** Vanilla op-level check used when fabric-permissions-api is absent. */
    private static boolean checkVanillaOpLevel(Object source, int minLevel) {
        try {
            // 1.18-1.21: hasPermissionLevel(int) / hasPermission(int)
            Object r = FabricReflection.callAny(source, "hasPermissionLevel",
                new Class<?>[]{int.class}, new Object[]{minLevel});
            if (r instanceof Boolean && (Boolean) r) return true;
        } catch (Throwable t) { /* fall through */ }
        try {
            Object r = FabricReflection.callAny(source, "hasPermission",
                new Class<?>[]{int.class}, new Object[]{minLevel});
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean hasPermission(String node) {
        if (source == null) return false;
        return checkPermission(source, node, 2);
    }

    @Override
    public boolean hasPermissionForPlayer(UUID playerId, String node) {
        try {
            Object server = FabricReflection.getServer();
            if (server == null) return false;
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return false;
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return false;
            return checkPermission(player, node, 2);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Text/Component class resolution ─────────────────────────────

    /**
     * Resolve the Minecraft text component class at runtime.
     * Returns {@code Component} (MC 26.1+) or {@code Text} (MC 1.18-1.21).
     */
    private static Class<?> resolveTextComponentClass() {
        try {
            return Class.forName("net.minecraft.network.chat.Component");
        } catch (ClassNotFoundException e) {
            try {
                return FabricReflection.forName("net.minecraft.network.chat.Component");
            } catch (Throwable ex) {
                return null;
            }
        }
    }
}