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
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return;
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            Object text = createText(message);
            if (text == null) return;
            Class<?> textCls = resolveTextComponentClass();
            if (textCls == null) return;
            // Multi-version fallback via signature-only scanning (no METHOD_REDIRECT).
            // Priority: try the chat-specific signature first so scanMethod
            // doesn't match the inherited single-param sendMessage(Text) which
            // sends to the action bar instead of chat on 1.18.2.
            try {
                // 1.18.2 chat: sendMessage(Component, UUID)
                FabricReflection.invokeBySigOrThrow(player,
                    new Class<?>[]{textCls, java.util.UUID.class},
                    new Object[]{text, java.util.UUID.randomUUID()});
            } catch (Throwable t1) {
                try {
                    // MC 26.1+: sendSystemMessage(Component)
                    FabricReflection.invokeBySigOrThrow(player,
                        new Class<?>[]{textCls}, new Object[]{text});
                } catch (Throwable t2) {
                    try {
                        // 1.18.2 action bar fallback: displayClientMessage(Component, boolean)
                        FabricReflection.invokeBySigOrThrow(player,
                            new Class<?>[]{textCls, boolean.class},
                            new Object[]{text, false});
                    } catch (Throwable t3) { /* silent */ }
                }
            }
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
            // Multi-version fallback via signature-only scanning.
            try {
                // MC 26.1+: sendSystemMessage(Component)
                FabricReflection.invokeBySigOrThrow(server,
                    new Class<?>[]{textCls}, new Object[]{text});
            } catch (Throwable t1) {
                try {
                    // 1.18.2: sendMessage(Component, UUID)
                    FabricReflection.invokeBySigOrThrow(server,
                        new Class<?>[]{textCls, java.util.UUID.class},
                        new Object[]{text, java.util.UUID.randomUUID()});
                } catch (Throwable t2) { /* silent */ }
            }
        } catch (Throwable t) { System.out.println("[MinerTrack] " + message); }
    }

    @Override public boolean toggleVerbose() {
        if (source != null) {
            // Determine whether the source is a player by trying to get
            // an entity that has a UUID.  Do NOT use callMigrated(isPlayer,..)
            // — on 1.18.2 both names fail and scanMethod matches a 0-param
            // method like getDisplayName(), returning a Component instead of
            // Boolean, causing the entire player branch to be skipped.
            UUID id = extractPlayerUuid(source);
            if (id != null) {
                if (verbosePlayers.contains(id)) { verbosePlayers.remove(id); return false; }
                else { verbosePlayers.add(id); return true; }
            }
        }
        verboseConsole = !verboseConsole; return verboseConsole;
    }

    /**
     * Try to extract a player UUID from a CommandSourceStack.
     * Returns null if the source is not backed by a player.
     *
     * <p>Avoids METHOD_REDIRECT by using signature-only scanning:
     * <ol>
     *   <li>{@code getPlayer()} — detected by 0-param, non-null return with UUID</li>
     *   <li>{@code getEntity()} — detected by 0-param, non-null return with UUID</li>
     * </ol>
     */
    private static UUID extractPlayerUuid(Object css) {
        if (css == null) return null;
        // On 1.18.2 getPlayer() does not exist — scanMethod matches
        // getDisplayName() and returns a Component instead.  That makes
        // the null-check pass but callUuid() fails.  We must keep trying
        // getEntity() even when getPlayer() returned a non-null object
        // without a UUID.
        for (String methodName : new String[]{"getPlayer", "getEntity"}) {
            Object entity = FabricReflection.callAny(css, methodName,
                new Class<?>[0], new Object[0]);
            if (entity != null) {
                Object uid = FabricReflection.callUuid(entity);
                if (uid instanceof UUID) return (UUID) uid;
            }
        }
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
            // 2) Fall back to vanilla op-level check.
            //    Use checkVanillaOpLevel(player) directly — it resolves
            //    isSourcePlayer() then tries hasPermission(int) and
            //    isSourceOperator().  This is the same path used by
            //    FabricDetectionBridge.hasPermission() and works across
            //    all MC versions including 1.18.2 without LP.
            return checkVanillaOpLevel(player, 2);
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

        // Determine if source is a CommandSourceStack or a direct player entity.
        // method-probing (callAny) is unreliable because scanMethod matches
        // wrong methods on mismatched classes.  Class-name heuristics are the
        // only safe discriminator when we can't import Minecraft types.
        String clsName = source.getClass().getName();
        boolean isCSS = clsName.contains("CommandSourceStack") || clsName.contains("class_2168");

        // ── Layer 1: non-player → always allowed ──────────────────
        if (isCSS && !isSourcePlayer(source)) return true;

        // ── Layer 2: hasPermission(int) / hasPermissionLevel(int) ──
        // Use FabricReflection.callAny() so intermediary names are resolved.
        // On MC 26.1+ neither alias exists — the fallback (Layer 3) handles
        // permissions correctly.  Suppress debug during the trial to avoid
        // harmless M-MISS diagnostics.
        boolean oldDebug = FabricReflection.DEBUG_REFLECTION;
        FabricReflection.DEBUG_REFLECTION = false;
        try {
            for (String name : new String[]{"hasPermission", "hasPermissionLevel"}) {
                try {
                    Object r = FabricReflection.callAny(source, name,
                        new Class<?>[]{int.class}, new Object[]{minLevel});
                    if (r instanceof Boolean && (Boolean) r) return true;
                } catch (Throwable t) { /* try next name */ }
            }
        } finally {
            FabricReflection.DEBUG_REFLECTION = oldDebug;
        }

        // ── Layer 3: operator check ─────────────────────────────────
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
        // 1.18-1.21: use getEntity() + game-profile probe.
        try {
            Object entity = FabricReflection.callAny(source, "getEntity",
                new Class<?>[0], new Object[0]);
            if (entity != null) {
                Object gp = FabricReflection.callAny(entity, "getGameProfile",
                    new Class<?>[0], new Object[0]);
                if (gp != null) return true;
            }
        } catch (Throwable t) { return false; }
        return false;
    }

    /** Check if the player behind this command source is an operator (>= level 2). */
    private static boolean isSourceOperator(Object source) {
        try {
            // Resolve the player entity from source: it may be a
            // CommandSourceStack or a direct ServerPlayer object.
            // Class-name heuristics are fragile across MC versions
            // (intermediary names differ).  Instead, probe functionally:
            // if source itself has getGameProfile(), it IS the player entity.
            Object player = source;
            Object gameProfile = FabricReflection.callAny(source, "getGameProfile",
                new Class<?>[0], new Object[0]);
            if (gameProfile == null) {
                // source is not a player entity — likely a CommandSourceStack.
                player = FabricReflection.callAny(source, "getEntity",
                    new Class<?>[0], new Object[0]);
                if (player == null) return false;
                gameProfile = FabricReflection.callAny(player, "getGameProfile",
                    new Class<?>[0], new Object[0]);
            }
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