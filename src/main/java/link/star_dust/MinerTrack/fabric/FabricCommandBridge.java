package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

/**
 * Fabric implementation of {@link CommandBridge}.
 *
 * <p>Wraps a Fabric {@code ServerCommandSource} (held as
 * {@code Object} to avoid pulling {@code net.minecraft.*} onto
 * the compile classpath) so the platform-neutral command core
 * can dispatch commands, query permissions, and route chat
 * without depending on the Fabric API directly.
 *
 * <p>All {@code net.minecraft.*} access goes through
 * {@link FabricReflection}.
 *
 * <p><b>Permission model:</b> Fabric / vanilla Minecraft has
 * no built-in permission-node system (unlike Bukkit). The
 * permission check hierarchy is:
 * <ol>
 *   <li>Fabric Permission API ({@code
 *       net.fabricmc.fabric.api.permission.v1.Actor}), if
 *       present at runtime (1.19.3+ with recent Fabric API).</li>
 *   <li>Server operator level (op level ≥ 2 for
 *       {@code hasPermission} — equivalent to Bukkit
 *       {@code isOp()} with a safe default).</li>
 * </ol>
 *
 * <p><b>Text API compatibility:</b> Minecraft's chat-text API
 * changed between versions:
 * <ul>
 *   <li>1.18 – 1.19.2: {@code new LiteralText(string)}</li>
 *   <li>1.19.3+: {@code Text.literal(string)}</li>
 * </ul>
 * The {@link #createText(String)} helper tries both and caches
 * the result so every subsequent call is a single reflection
 * dispatch.
 *
 * <p><b>sendMessage signature:</b> also changed:
 * <ul>
 *   <li>1.18 – 1.19.3: {@code sendMessage(Text, boolean)}</li>
 *   <li>1.19.4+: {@code sendMessage(Text)} (overload without
 *       boolean)</li>
 * </ul>
 * {@link #sendMessage0} tries the two-arg variant first, then
 * falls back to the single-arg variant.
 */
public class FabricCommandBridge implements CommandBridge {
    private final Object source; // net.minecraft.server.command.ServerCommandSource
    private final Set<UUID> verbosePlayers;
    private volatile boolean verboseConsole = false;

    // ── Cross-version Text helper cache ──────────────────────────
    // java.lang.reflect.Executable is the common superclass of
    // Method and Constructor (Java 8+). We cache whichever we
    // resolved (static factory Method or Constructor) so the
    // hot path is a single reflective dispatch.
    private static volatile java.lang.reflect.Executable cachedTextFactory;
    private static volatile boolean textFactoryIsStatic; // true = Method, false = Constructor

    // ── Cross-version sendMessage helper cache ───────────────────
    private static volatile boolean twoArgSendSupported = true; // assume 2-arg first

    public FabricCommandBridge(Object source, Set<UUID> verbosePlayers) {
        this.source = source;
        this.verbosePlayers = verbosePlayers;
    }

    // ─────────────────────────────────────────────────────────────
    //  Text creation (cross-version)
    // ─────────────────────────────────────────────────────────────

    /**
     * Create a {@code Text} object from a plain string.
     * Tries {@code Text.literal()} (1.19.3+), then falls back
     * to {@code new LiteralText()} (1.18 – 1.19.2). The result
     * is cached for the lifetime of the JVM.
     */
    private static Object createText(String message) {
        if (cachedTextFactory != null) {
            try {
                if (textFactoryIsStatic) {
                    return ((Method) cachedTextFactory).invoke(null, message);
                } else {
                    return ((java.lang.reflect.Constructor<?>) cachedTextFactory).newInstance(message);
                }
            } catch (Throwable ignored) {
                // Cache stale after reload — reset and retry below.
                cachedTextFactory = null;
            }
        }
        Class<?> textCls = FabricReflection.forName("net.minecraft.text.Text");
        // 1. Try static factory: Text.literal(String)
        if (textCls != null) {
            Method m = FabricReflection.findMethod(textCls, "literal",
                new Class<?>[]{String.class});
            if (m != null) {
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(null, message);
                    cachedTextFactory = m;
                    textFactoryIsStatic = true;
                    return result;
                } catch (Throwable ignored) {}
            }
        }
        // 2. Fallback: new LiteralText(String) (1.18 – 1.19.2)
        Class<?> ltCls = FabricReflection.forName("net.minecraft.text.LiteralText");
        if (ltCls != null) {
            try {
                java.lang.reflect.Constructor<?> ctor = ltCls.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                Object result = ctor.newInstance(message);
                cachedTextFactory = ctor;
                textFactoryIsStatic = false;
                return result;
            } catch (Throwable ignored) {}
        }
        // 3. Last resort — try Text.literal anyway (maybe the
        //    class resolution was transiently flaky).
        if (textCls != null) {
            try {
                Method m = textCls.getMethod("literal", String.class);
                Object result = m.invoke(null, message);
                cachedTextFactory = m;
                textFactoryIsStatic = true;
                return result;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Send a {@code Text} message to a target via
     * {@code sendMessage}. Handles the signature change between
     * Minecraft versions:
     * <ul>
     *   <li>1.18 – 1.19.3: {@code sendMessage(Text, boolean)}</li>
     *   <li>1.19.4+: {@code sendMessage(Text)} (single-arg)</li>
     * </ul>
     * Tries the two-arg variant first; on failure, falls back
     * to single-arg.
     */
    private static void sendMessage0(Object target, Object text) {
        if (text == null || target == null) return;
        Class<?> textCls = FabricReflection.forName("net.minecraft.text.Text");
        if (textCls == null) return;
        try {
            if (twoArgSendSupported) {
                FabricReflection.callAny(target, "sendMessage",
                    new Class<?>[]{textCls, boolean.class},
                    new Object[]{text, false});
                return;
            }
            // Two-arg path previously failed — go straight to
            // single-arg.
            FabricReflection.callAny(target, "sendMessage",
                new Class<?>[]{textCls}, new Object[]{text});
        } catch (Throwable t1) {
            try {
                // Two-arg failed — switch to single-arg for all
                // future calls.
                twoArgSendSupported = false;
                FabricReflection.callAny(target, "sendMessage",
                    new Class<?>[]{textCls}, new Object[]{text});
            } catch (Throwable t2) {
                // Both signatures failed — target may be
                // disconnected or an incompatible version.
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  CommandBridge implementation
    // ─────────────────────────────────────────────────────────────

    @Override
    public void dispatchCommand(String command) {
        try {
            Object s = source();
            if (s == null) return;
            Object server = FabricReflection.callAny(s, "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            Object cmdManager = FabricReflection.callAny(server, "getCommandManager", new Class<?>[0], new Object[0]);
            if (cmdManager == null) return;
            FabricReflection.callAny(cmdManager, "executeWithPrefix",
                new Class<?>[]{FabricReflection.forName("net.minecraft.server.command.ServerCommandSource"), String.class},
                new Object[]{s, command});
        } catch (Throwable t) {
            // Silent — the engine still logs via appendCommandLog.
        }
    }

    @Override
    public boolean isPlayer() {
        Object s = source();
        if (s == null) return false;
        try {
            Object r = FabricReflection.callAny(s, "isExecutedByPlayer", new Class<?>[0], new Object[0]);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isConsole() {
        return !isPlayer();
    }

    @Override
    public Object getSender() {
        return source;
    }

    @Override
    public void sendMessage(String message) {
        Object s = source();
        if (s == null) return;
        try {
            Object text = createText(message);
            sendMessage0(s, text);
        } catch (Throwable t) {
            // Sender disconnected.
        }
    }

    @Override
    public void sendMessageToPlayer(UUID playerId, String message) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
            if (pm == null) return;
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{playerId});
            if (player == null) return;
            Object text = createText(message);
            sendMessage0(player, text);
        } catch (Throwable t) {
            // Offline player.
        }
    }

    @Override
    public void sendMessageToConsole(String message) {
        try {
            Object server = FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return;
            Object text = createText(message);
            sendMessage0(server, text);
        } catch (Throwable t) {
            System.out.println("[MinerTrack] " + message);
        }
    }

    @Override
    public boolean toggleVerbose() {
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
                } catch (Throwable t) {
                    return false;
                }
                if (id == null) return false;
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

    // ─────────────────────────────────────────────────────────────
    //  Permission checks
    // ─────────────────────────────────────────────────────────────

    /**
     * Check whether the command source has a specific
     * permission node. The check hierarchy is:
     *
     * <ol>
     *   <li>Fabric Permission API (if available at runtime):
     *       {@code Actor.suspendingFallback(source).checkPermission(node, 2)}
     *       or the equivalent static check.</li>
     *   <li>Player op-level ≥ 2 (equivalent to Bukkit's
     *       {@code isOp()} for admin-level commands).</li>
     *   <li>Console always has permission.</li>
     * </ol>
     *
     * <p>The node string is passed through to the Fabric
     * Permission API; on the op-level fallback path it is
     * ignored because vanilla Minecraft has no concept of
     * named permission nodes.
     */
    @Override
    public boolean hasPermission(String node) {
        Object s = source();
        if (s == null) return false;

        // ── 1. Fabric Permission API (if available at runtime) ──
        //    net.fabricmc.fabric.api.permission.v1.Actor has:
    	//      static PermissionContext<T> suspendingFallback(T actor)
    	//      boolean checkPermission(String permission, int defaultRequiredLevel)
    	//    Available in Fabric API ≥ 0.58.0 (MC 1.20.1) and
    	//    back-ported to some 1.19.x builds. We try via
    	//    reflection; if the class is absent the fallback below
    	//    kicks in silently.
        try {
            Class<?> actorCls = FabricReflection.forName(
                "net.fabricmc.fabric.api.permission.v1.Actor");
            if (actorCls != null) {
                // Actor.suspendingFallback(source).checkPermission(node, 2)
                Object ctx = FabricReflection.callStatic(
                    actorCls.getName(), "suspendingFallback",
                    new Class<?>[]{Object.class}, new Object[]{s});
                if (ctx != null) {
                    Object result = FabricReflection.callAny(ctx,
                        "checkPermission",
                        new Class<?>[]{String.class, int.class},
                        new Object[]{node, 2});
                    if (result instanceof Boolean) return (Boolean) result;
                }
            }
        } catch (Throwable ignored) {
            // Permission API not available on this server —
            // fall through to op-level check.
        }

        // ── 2. Op-level fallback (1.18.x / no Permission API) ──
        try {
            Object r = FabricReflection.callAny(s, "isExecutedByPlayer",
                new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) {
                Object lvl = FabricReflection.callAny(s,
                    "hasPermissionLevel",
                    new Class<?>[]{int.class}, new Object[]{2});
                return lvl instanceof Boolean && (Boolean) lvl;
            }
            // Console always has permission to run admin commands.
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Check whether a specific online player has a permission
     * node. The check hierarchy is:
     *
     * <ol>
     *   <li>Fabric Permission API (if available): check the
     *       player entity against the permission node.</li>
     *   <li>Is the player a server operator? (op = has all
     *       permission nodes for admin commands).</li>
     * </ol>
     */
    @Override
    public boolean hasPermissionForPlayer(UUID playerId, String node) {
        try {
            Object server = FabricReflection.callStatic(
                "net.minecraft.server.MinecraftServer",
                "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return false;
            Object pm = FabricReflection.call(server,
                "getPlayerManager", new Class<?>[0], new Object[0]);
            if (pm == null) return false;
            Object player = FabricReflection.call(pm,
                "getPlayer",
                new Class<?>[]{UUID.class},
                new Object[]{playerId});
            if (player == null) return false;

            // ── 1. Fabric Permission API (if available) ────────
            try {
                Class<?> actorCls = FabricReflection.forName(
                    "net.fabricmc.fabric.api.permission.v1.Actor");
                if (actorCls != null) {
                    Object ctx = FabricReflection.callStatic(
                        actorCls.getName(), "suspendingFallback",
                        new Class<?>[]{Object.class},
                        new Object[]{player});
                    if (ctx != null) {
                        Object result = FabricReflection.callAny(ctx,
                            "checkPermission",
                            new Class<?>[]{String.class, int.class},
                            new Object[]{node, 2});
                        if (result instanceof Boolean) return (Boolean) result;
                    }
                }
            } catch (Throwable ignored) {}

            // ── 2. Op-level fallback ────────────────────────────
            //    ServerManager.isOperator(GameProfile) — works
            //    across all 1.18+ versions.
            Object gameProfile = FabricReflection.callAny(player,
                "getGameProfile", new Class<?>[0], new Object[0]);
            if (gameProfile != null) {
                Object isOp = FabricReflection.call(pm, "isOperator",
                    new Class<?>[]{gameProfile.getClass()},
                    new Object[]{gameProfile});
                if (isOp instanceof Boolean) return (Boolean) isOp;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private Object source() {
        try {
            Class<?> cls = Class.forName("net.minecraft.server.command.ServerCommandSource");
            if (source != null && cls.isInstance(source)) return source;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
