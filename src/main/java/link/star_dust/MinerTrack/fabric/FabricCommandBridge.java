package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;
import me.lucko.fabric.api.permissions.v0.Permissions;

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

    /** Delegates to {@link FabricReflection#createText} (handles intermediary name resolution). */
    private static Object createText(String message) {
        return FabricReflection.createText(message);
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
     *   <li>{@code sendSuccess(Supplier<Component>, boolean)} — 1.20.2+
     *   <li>{@code sendSuccess(Component, boolean)} — 1.18.2–1.20.1
     *   <li>{@code sendFailure(Component)} — all versions (error path)
     *   <li>{@code sendSystemMessage(Component)} — 1.19.3+ CommandSourceStack
     *   <li>{@code sendMessage(Component, UUID)} — 1.18.2 CommandSourceStack
     *   <li>{@code sendMessage(Text, boolean)} — player entity fallback
     * </ol>
     *
     * @return {@code true} if the message was delivered via any path
     */
    private static boolean sendFeedback(Object target, Object text, boolean isSuccess) {
        if (text == null || target == null) return false;
        Class<?> textCls = resolveTextComponentClass();
        if (textCls == null) return false;
        Class<?> targetCls = target.getClass();

        // 1a) sendSuccess(Supplier<Component>, boolean) — 1.20.2+ / 1.21.1+
        // 1b) sendSuccess(Component, boolean) — 1.18.2–1.20.1
        //
        // These two overloads are mutually exclusive across MC versions;
        // one will always fail (producing an M-MISS under debug mode).
        // Temporarily suppress debug logging during this trial phase so
        // that the expected miss does not pollute the console.
        if (isSuccess) {
            boolean oldDebug = FabricReflection.DEBUG_REFLECTION;
            FabricReflection.DEBUG_REFLECTION = false;
            try {
                // 1a) Supplier overload (1.20.2+)
                try {
                    Method m = FabricReflection.findMethod(targetCls, "sendSuccess",
                        new Class<?>[]{Supplier.class, boolean.class});
                    if (m != null) {
                        final Object t = text;
                        m.invoke(target, (Supplier<?>) () -> t, false);
                        return true;
                    }
                } catch (Throwable t) { /* fall through */ }

                // 1b) Component overload (1.18.2–1.20.1)
                try {
                    Method m = FabricReflection.findMethod(targetCls, "sendSuccess",
                        new Class<?>[]{textCls, boolean.class});
                    if (m != null) {
                        m.invoke(target, text, false);
                        return true;
                    }
                } catch (Throwable t) { /* fall through */ }
            } finally {
                FabricReflection.DEBUG_REFLECTION = oldDebug;
            }
        }

        // 2) sendFailure(Component) — same signature on all versions
        if (!isSuccess) {
            try {
                Method m = FabricReflection.findMethod(targetCls, "sendFailure",
                    new Class<?>[]{textCls});
                if (m != null) {
                    m.invoke(target, text);
                    return true;
                }
            } catch (Throwable t) { /* fall through */ }
        }

        // 3) sendSystemMessage(Component) — 1.19.3+ CommandSourceStack
        try {
            Method m = FabricReflection.findMethod(targetCls, "sendSystemMessage",
                new Class<?>[]{textCls});
            if (m != null) {
                m.invoke(target, text);
                return true;
            }
        } catch (Throwable t) { /* fall through */ }

        // 4) sendMessage(Component, UUID) — 1.18.2 sendSuccess delegates to this
        try {
            Method m = FabricReflection.findMethod(targetCls, "sendMessage",
                new Class<?>[]{textCls, UUID.class});
            if (m != null) {
                m.invoke(target, text, java.util.UUID.randomUUID());
                return true;
            }
        } catch (Throwable t) { /* fall through */ }

        // 5) sendMessage(Text, boolean) — player entity / legacy
        try {
            Method m = FabricReflection.findMethod(targetCls, "sendMessage",
                new Class<?>[]{textCls, boolean.class});
            if (m != null) {
                m.invoke(target, text, false);
                return true;
            }
        } catch (Throwable t) { /* fall through */ }

        // 6) sendMessage(Text) — last-resort legacy
        try {
            Method m = FabricReflection.findMethod(targetCls, "sendMessage",
                new Class<?>[]{textCls});
            if (m != null) {
                m.invoke(target, text);
                return true;
            }
        } catch (Throwable t) { /* fall through */ }

        return false;
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
        // Mojang: net.minecraft.commands.CommandSourceStack
        return FabricReflection.forName(FabricReflectionConstants.CLS_COMMAND_SOURCE_STACK);
    }

    @Override public boolean isPlayer() {
        try {
            // 1.21.1+: isPlayer()
            // Mojang: CommandSourceStack.isPlayer() → boolean
            Object r = FabricReflection.callAny(source, FabricReflectionConstants.M_IS_PLAYER,
                FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (r instanceof Boolean && (Boolean) r) return true;
        } catch (Throwable t) { /* fall through */ }
        // 1.18.2: check if getEntity() returns a non-null player
        try {
            // Mojang: CommandSourceStack.getEntity() → Entity
            Object entity = FabricReflection.callAny(source, FabricReflectionConstants.M_GET_ENTITY,
                FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (entity != null) {
                // Mojang: net.minecraft.server.level.ServerPlayer
                Class<?> serverPlayer = FabricReflection.forName(FabricReflectionConstants.CLS_SERVER_PLAYER);
                if (serverPlayer != null && serverPlayer.isInstance(entity)) return true;
            }
        } catch (Throwable t) { /* fall through */ }
        // Legacy fallback: isExecutedByPlayer
        try {
            Object r = FabricReflection.callAny(source, "isExecutedByPlayer",
                FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) { return false; }
    }

    @Override public boolean isConsole() { return !isPlayer(); }

    @Override public Object getSender() { return source; }

    @Override
    public void sendMessage(String message) {
        if (source == null) {
            System.out.println("[MinerTrack] " + message);
            return;
        }
        Object text = createText(message);
        if (text == null) {
            System.out.println("[MinerTrack] " + message);
            return;
        }
        if (sendFeedback(source, text, true)) return;
        // All reflection paths failed — final fallback
        System.out.println("[MinerTrack] " + message);
    }

    @Override
    public void sendSuccess(String message) {
        if (source == null) {
            System.out.println("[MinerTrack] " + message);
            return;
        }
        Object text = createText(message);
        if (text == null) {
            System.out.println("[MinerTrack] " + message);
            return;
        }
        if (sendFeedback(source, text, true)) return;
        System.out.println("[MinerTrack] " + message);
    }

    @Override
    public void sendFailure(String message) {
        if (source == null) {
            System.out.println("[MinerTrack] " + message);
            return;
        }
        Object text = createText(message);
        if (text == null) {
            System.out.println("[MinerTrack] " + message);
            return;
        }
        if (sendFeedback(source, text, false)) return;
        System.out.println("[MinerTrack] " + message);
    }

    @Override public void sendMessageToPlayer(UUID playerId, String message) {
        try {
            Object server = FabricReflection.getServer();
            if (server == null) return;
            Object pm = FabricReflection.callMigrated(server,
                FabricReflectionConstants.M_GET_PLAYER_LIST, "getPlayerManager",
                FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
            if (pm == null) return;
            Object player = FabricReflection.call(pm, FabricReflectionConstants.M_GET_PLAYER_UUID,
                new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            Object text = createText(message);
            if (text == null) return;
            FabricReflection.sendMessageToPlayer(player, text);
        } catch (Throwable t) { /* silent */ }
    }

    @Override public void sendMessageToConsole(String message) {
        try {
            Object server = FabricReflection.getServer();
            if (server == null) return;
            Object text = createText(message);
            if (text == null) return;
            FabricReflection.sendMessageToConsole(server, text);
        } catch (Throwable t) { System.out.println("[MinerTrack] " + message); }
    }

    @Override public boolean toggleVerbose() {
        if (source != null) {
            // Try to obtain a player UUID from the source regardless of what
            // isPlayer() reports.  isPlayer() may return false negatives on
            // some MC versions (e.g. when ServerPlayer class loading fails
            // on old intermediary runtimes), which would cause the verbose
            // toggle to hit the console branch instead of the player branch.
            //
            // Strategy: try getPlayer() first (MC 26.1+), then getEntity()
            // (all versions).  On 1.18.2 getPlayer() does not exist, so
            // scanMethod matches getDisplayName() and returns a Component —
            // but callUuid() then fails to extract a UUID, so we safely
            // fall through to getEntity() which works on every version.
            UUID id = lookUpPlayerId(source);
            if (id != null) {
                if (verbosePlayers.contains(id)) {
                    verbosePlayers.remove(id);
                    return false;
                } else {
                    verbosePlayers.add(id);
                    return true;
                }
            }
        }
        verboseConsole = !verboseConsole;
        return verboseConsole;
    }

    /**
     * Extract a player UUID from a CommandSourceStack, or null if the
     * source is not backed by a player entity.
     *
     * <p>Try order:
     * <ol>
     *   <li>{@code getPlayer()} — MC 26.1+ (direct, no ambiguity)</li>
     *   <li>{@code getEntity()} — 1.18.2 through 26.1+ (always works)</li>
     * </ol>
     */
    private static UUID lookUpPlayerId(Object css) {
        if (css == null) return null;
        // 1) getPlayer() — MC 26.1+
        try {
            Object p = FabricReflection.callAny(css, "getPlayer",
                new Class<?>[0], new Object[0]);
            Object uid = FabricReflection.callUuid(p);
            if (uid instanceof UUID) return (UUID) uid;
        } catch (Throwable t) { /* fall through */ }
        // 2) getEntity() — all versions
        try {
            Object p = FabricReflection.callAny(css, "getEntity",
                new Class<?>[0], new Object[0]);
            Object uid = FabricReflection.callUuid(p);
            if (uid instanceof UUID) return (UUID) uid;
        } catch (Throwable t) { /* fall through */ }
        return null;
    }

    // ── Permission checks ──────────────────────────────────────────
    //
    // Use Lucko's fabric-permissions-api (me.lucko.fabric.api.permissions.v0.Permissions).
    // Permissions.check() has typed overloads (CommandSourceStack, Entity, etc.)
    // whose signatures reference Minecraft types not on our compile classpath,
    // so we use reflection. The root cause of previous failures: a single cached
    // Method with a fixed parameter type (e.g. Entity) would be invoked with a
    // different runtime type (e.g. CommandSourceStack), throwing IAE silently.
    //
    // Fix: cache ALL candidate check() overloads at init time, then at each
    // call iterate all of them and invoke the one whose param[0].isInstance(source).
    //
    // fabric-permissions-api is compileOnly — NOT bundled in the JAR.
    // LuckPerms ships its own version-matched copy at runtime. When LP is absent,
    // we fall back to a layered vanilla check that works on all MC versions.

    /** Cached list of all Permissions.check() overloads matching signature (?, String, int). */
    private static volatile java.util.List<Method> allCheckMethods;

    private static java.util.List<Method> findAllCheckMethods() {
        if (allCheckMethods != null) return allCheckMethods;
        java.util.List<Method> result = new java.util.ArrayList<>();
        try {
            Class<?> permsCls = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            // Gather from both declared and public methods (inherited static imports).
            for (Method m : permsCls.getMethods()) {
                if (m.getName().equals("check") && m.getParameterCount() == 3) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts[1] == String.class && (pts[2] == int.class || pts[2] == Integer.class)) {
                        result.add(m);
                    }
                }
            }
            for (Method m : permsCls.getDeclaredMethods()) {
                if (m.getName().equals("check") && m.getParameterCount() == 3) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts[1] == String.class && (pts[2] == int.class || pts[2] == Integer.class)) {
                        // Avoid duplicates
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

    /**
     * Call {@code Permissions.check(source, node, defaultOpLevel)} via reflection,
     * trying each cached overload until param[0].isInstance(source) matches.
     * Returns true if LP grants the permission, false if LP is absent / invocation
     * fails / LP explicitly denies.
     *
     * <p>Package-private so that {@link FabricDetectionBridge} can reuse it
     * for the {@code hasPermission(UUID, String)} bridge method.
     */
    static boolean checkLPPermission(Object source, String node, int defaultOpLevel) {
        for (Method m : findAllCheckMethods()) {
            Class<?> sourceType = m.getParameterTypes()[0];
            if (!sourceType.isInstance(source)) continue;
            try {
                Object result = m.invoke(null, source, node, defaultOpLevel);
                return result instanceof Boolean && (Boolean) result;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean hasPermission(String node) {
        if (source == null) return false;
        // 1) Try LP via fabric-permissions-api (isInstance-matched reflection)
        if (checkLPPermission(source, node, 2)) return true;
        // 2) Fall back to vanilla op-level / operator check
        return checkVanillaOpLevel(source, 2);
    }

    @Override
    public boolean hasPermissionForPlayer(UUID playerId, String node) {
        try {
            Object server = FabricReflection.getServer();
            if (server == null) return false;
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return false;
            Object player = FabricReflection.call(pm, "getPlayerByUUID", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return false;
            // 1) Try LP via fabric-permissions-api
            if (checkLPPermission(player, node, 2)) return true;
            // 2) Fall back to vanilla operator check via getProfilePermissions
            try {
                Object gameProfile = FabricReflection.callAny(player, "getGameProfile",
                    new Class<?>[0], new Object[0]);
                if (gameProfile != null) {
                    Object permLevel = FabricReflection.callAny(server, "getProfilePermissions",
                        new Class<?>[]{gameProfile.getClass()}, new Object[]{gameProfile});
                    if (permLevel instanceof Number) return ((Number) permLevel).intValue() >= 2;
                }
            } catch (Throwable t2) { /* fall through */ }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Vanilla op-level / operator check — the catch-all fallback.
     *
     * <p>Layered approach:
     * <ol>
     *   <li>If the source is NOT a player (console / command block / RCON),
     *       always return {@code true}.</li>
     *   <li>Try {@code hasPermission(int)} / {@code hasPermissionLevel(int)}
     *       via plain {@link Class#getMethod} (works on 1.18–1.21).</li>
     *   <li>Check player operator status via PlayerList (works on all versions
     *       including MC 26.x where the old permission methods are gone).</li>
     * </ol>
     *
     * <p>Package-private so that {@link FabricDetectionBridge} can reuse it
     * for the {@code hasPermission(UUID, String)} bridge method.
     */
    static boolean checkVanillaOpLevel(Object source, int minLevel) {
        if (source == null) return false;

        // ── Layer 1: non-player → always allowed ──────────────────
        if (!isSourcePlayer(source)) return true;

        // ── Layer 2: hasPermission(int) / hasPermissionLevel(int) ──
        // Use FabricReflection.callAny() (NOT source.getClass().getMethod())
        // so intermediary names (e.g. method_9259 on 1.18.2) are resolved.
        for (String name : new String[]{"hasPermission", "hasPermissionLevel"}) {
            try {
                Object r = FabricReflection.callAny(source, name,
                    new Class<?>[]{int.class}, new Object[]{minLevel});
                if (r instanceof Boolean && (Boolean) r) return true;
            } catch (Throwable t) { /* try next name */ }
        }

        // ── Layer 3: PlayerList.isOp() (works on all versions) ─────
        return isSourceOperator(source);
    }

    /** Check if the command source is a player (not console / command block). */
    private static boolean isSourcePlayer(Object source) {
        // MC 26.1+: isPlayer()
        try {
            Method m = source.getClass().getMethod("isPlayer");
            Object r = m.invoke(source);
            if (r instanceof Boolean && (Boolean) r) return true;
        } catch (Throwable t) { /* fall through */ }
        // 1.18-1.21: use getEntity() + instanceof ServerPlayer.
        // isExecutedByPlayer() is not available on CommandSourceStack
        // in MC 1.18.2, and using getMethod() without intermediary
        // resolution would fail on production servers anyway.
        try {
            Object entity = FabricReflection.callAny(source, "getEntity",
                new Class<?>[0], new Object[0]);
            if (entity != null) {
                Class<?> spCls = FabricReflection.forName(
                    FabricReflectionConstants.CLS_SERVER_PLAYER);
                if (spCls != null && spCls.isInstance(entity)) return true;
            }
        } catch (Throwable t) { return false; }
        return false;
    }

    /** Check if the player behind this command source is an operator (>= level 2). */
    private static boolean isSourceOperator(Object source) {
        try {
            // Use getEntity() directly — on 1.18.2 CommandSourceStack
            // does not have getPlayer(), so calling getPlayer() first
            // would trigger scanMethod to match getDisplayName() (a
            // Component), breaking the entire operator check.
            // getEntity() works on all MC versions 1.18.2 through 26.1+.
            Object player = FabricReflection.callAny(source, "getEntity",
                new Class<?>[0], new Object[0]);
            if (player == null) return false;

            // Use MinecraftServer.getProfilePermissions(GameProfile) instead
            // of PlayerList.isOp(GameProfile). PlayerList has three methods
            // with the (GameProfile)Z signature (isWhiteListed, isOp,
            // canBypassPlayerLimit) in that declaration order, and scanMethod
            // matches isWhiteListed first — returning false for any player
            // not on the whitelist. getProfilePermissions returns int and has
            // a unique signature, avoiding any scanMethod ambiguity.
            Object gameProfile = FabricReflection.callAny(player, "getGameProfile",
                new Class<?>[0], new Object[0]);
            if (gameProfile == null) return false;
            Object server = FabricReflection.getServer();
            if (server == null) return false;
            try {
                Object permLevel = FabricReflection.callAny(server, "getProfilePermissions",
                    new Class<?>[]{gameProfile.getClass()}, new Object[]{gameProfile});
                if (permLevel instanceof Number) return ((Number) permLevel).intValue() >= 2;
            } catch (Throwable t) { /* fall through */ }
            return false;
        } catch (Throwable t) { /* fall through */ }
        return false;
    }

    // ── Text/Component class resolution ─────────────────────────────

    /** Delegates to {@link FabricReflection#resolveTextComponentClass()} (handles intermediary). */
    private static Class<?> resolveTextComponentClass() {
        return FabricReflection.resolveTextComponentClass();
    }
}