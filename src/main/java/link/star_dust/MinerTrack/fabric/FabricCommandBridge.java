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
            Class<?> textCls = resolveTextComponentClass();
            if (textCls == null) return;
            Class<?> targetCls = target.getClass();

            // 1) Try sendSuccess(Supplier<Text>, boolean) — 1.19+ CommandSourceStack
            //    This is the correct method for player-facing messages. When the
            //    source is a player, the message appears in their chat. When it's
            //    the console, it also goes to the server log.
            if (isSuccess) {
                try {
                    Method m = FabricReflection.findMethod(targetCls, "sendSuccess", new Class<?>[]{Supplier.class, boolean.class});
                    if (m != null) {
                        Object supplier = createTextSupplier((String) invokeToString(text));
                        if (supplier != null) {
                            m.invoke(target, supplier, false);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    // Reflection failed, try next fallback
                }
            }

            // 2) Try sendFailure(Text) — 1.19+ CommandSourceStack error messages
            //    This sends red-colored text to the command source (player or console).
            if (!isSuccess) {
                try {
                    Method m = FabricReflection.findMethod(targetCls, "sendFailure", new Class<?>[]{textCls});
                    if (m != null) {
                        m.invoke(target, text);
                        return;
                    }
                } catch (Throwable t) {
                    // Reflection failed, try next fallback
                }
            }

            // 3) Try sendMessage(Text, boolean) — 1.18-1.19.3 player entities
            //    The boolean parameter is the 'overlay' flag (false = chat).
            try {
                Method m = FabricReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{textCls, boolean.class});
                if (m != null) {
                    m.invoke(target, text, false);
                    return;
                }
            } catch (Throwable t) {
                // Reflection failed, try next fallback
            }

            // 4) Try sendMessage(Text) — 1.18 ServerCommandSource / MinecraftServer
            try {
                Method m = FabricReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{textCls});
                if (m != null) {
                    m.invoke(target, text);
                    return;
                }
            } catch (Throwable t) {
                // Reflection failed, try next fallback
            }

            // 5) Try sendMessage(Component) — 26.1+ Adventure API (Component in network.chat)
            try {
                Class<?> componentCls = resolveTextComponentClass();
                if (componentCls != null && !componentCls.equals(textCls)) {
                    Method m = FabricReflection.findMethod(targetCls, "sendMessage", new Class<?>[]{componentCls});
                    if (m != null) {
                        m.invoke(target, text);
                        return;
                    }
                }
            } catch (Throwable t) {
                // Reflection failed, will fall through to println
            }

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
            // MC 26.1+: getCommands(); 1.18-1.21: getCommandManager()
            Object cmdManager = FabricReflection.callMigrated(server, "getCommands", "getCommandManager",
                new Class<?>[0], new Object[0]);
            if (cmdManager == null) return;
            // MC 26.1+: performPrefixedCommand(CommandSourceStack, String)
            // 1.18-1.21: executeWithPrefix(CommandSourceStack, String)
            try {
                FabricReflection.callAny(cmdManager, "performPrefixedCommand",
                    new Class<?>[]{source.getClass(), String.class},
                    new Object[]{source, command});
            } catch (Throwable t1) {
                FabricReflection.callAny(cmdManager, "executeWithPrefix",
                    new Class<?>[]{source.getClass(), String.class},
                    new Object[]{source, command});
            }
        } catch (Throwable t) { /* silent */ }
    }

    @Override public boolean isPlayer() {
        try {
            // MC 26.1+: isPlayer(); 1.18-1.21: isExecutedByPlayer()
            Object r = FabricReflection.callMigrated(source, "isPlayer", "isExecutedByPlayer",
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
            // Direct: MC 26.1+ Player only has sendSystemMessage(Component);
            // sendSuccess/sendFailure/sendMessage are CommandSourceStack-only.
            Object text = createText(message);
            if (text == null) return;
            Class<?> textCls = resolveTextComponentClass();
            if (textCls == null) return;
            try {
                FabricReflection.callAny(player, "sendSystemMessage",
                    new Class<?>[]{textCls}, new Object[]{text});
            } catch (Throwable t1) {
                try {
                    FabricReflection.callAny(player, "sendMessage",
                        new Class<?>[]{textCls, boolean.class},
                        new Object[]{text, false});
                } catch (Throwable t2) { /* silent */ }
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
            // Direct: MC 26.1+ has sendSystemMessage(Component); earlier has sendMessage(Text)
            try {
                FabricReflection.callAny(server, "sendSystemMessage",
                    new Class<?>[]{textCls}, new Object[]{text});
            } catch (Throwable t1) {
                FabricReflection.callAny(server, "sendMessage",
                    new Class<?>[]{textCls, boolean.class},
                    new Object[]{text, false});
            }
        } catch (Throwable t) { System.out.println("[MinerTrack] " + message); }
    }

    @Override public boolean toggleVerbose() {
        if (source != null) {
            Object r = FabricReflection.callMigrated(source, "isPlayer", "isExecutedByPlayer",
                new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) {
                UUID id = null;
                try {
                    Object player = FabricReflection.callAny(source, "getPlayer", new Class<?>[0], new Object[0]);
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
        // 2) op-level check
        try {
            Object r = FabricReflection.callMigrated(source, "isPlayer", "isExecutedByPlayer",
                new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) {
                // MC 26.1+: permissions() returns PermissionSet; check via Permission objects.
                Object permSet = FabricReflection.callAny(source, "permissions", new Class<?>[0], new Object[0]);
                if (permSet != null) {
                    try {
                        // Check GAMEMASTER level (which is what "op-level 2" maps to).
                        Object perm = createGamerMasterPermission();
                        if (perm != null) {
                            Method m = permSet.getClass().getMethod("hasPermission", perm.getClass());
                            Object granted = m.invoke(permSet, perm);
                            if (granted instanceof Boolean && (Boolean) granted) return true;
                        }
                    } catch (Throwable t) { /* fall through */ }
                }
                // 1.18-1.21 fallback: hasPermissionLevel(int) / hasPermission(int)
                try {
                    Object lvl = FabricReflection.callAny(source, "hasPermissionLevel",
                        new Class<?>[]{int.class}, new Object[]{2});
                    if (lvl instanceof Boolean && (Boolean) lvl) return true;
                } catch (Throwable t2) {
                    try {
                        Object lvl2 = FabricReflection.callAny(source, "hasPermission",
                            new Class<?>[]{int.class}, new Object[]{2});
                        if (lvl2 instanceof Boolean && (Boolean) lvl2) return true;
                    } catch (Throwable t3) { /* fall through */ }
                }
            }
            return true; // console
        } catch (Throwable t) { return false; }
    }

    /** Create a {@code Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)} for op-level >= 2 checks. */
    private static Object createGamerMasterPermission() {
        try {
            Class<?> permCls = FabricReflection.forName("net.minecraft.server.permissions.Permission");
            Class<?> levelCls = FabricReflection.forName("net.minecraft.server.permissions.PermissionLevel");
            if (permCls == null || levelCls == null) return null;
            // PermissionLevel.GAMEMASTERS
            java.lang.reflect.Field gmField = levelCls.getField("GAMEMASTERS");
            Object gmLevel = gmField.get(null);
            if (gmLevel == null) return null;
            // Permission.HasCommandLevel(PermissionLevel) record constructor
            Class<?> hasCmdCls = FabricReflection.forName(
                "net.minecraft.server.permissions.Permission$HasCommandLevel");
            if (hasCmdCls == null) return null;
            return hasCmdCls.getDeclaredConstructor(levelCls).newInstance(gmLevel);
        } catch (Throwable t) {
            return null;
        }
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
            if (checkFabricPermission(player, node)) return true;
            // MC 26.1+: isOp(NameAndId), uses nameAndId(); 1.18-1.21: getGameProfile()→isOp()
            try {
                Object nameAndId = FabricReflection.callAny(player, "nameAndId", new Class<?>[0], new Object[0]);
                if (nameAndId != null) {
                    Object isOp = FabricReflection.call(pm, "isOp",
                        new Class<?>[]{nameAndId.getClass()}, new Object[]{nameAndId});
                    if (isOp instanceof Boolean) return (Boolean) isOp;
                }
            } catch (Throwable t1) {
                // Fallback: getGameProfile() → isOp(GameProfile)
                try {
                    Object gameProfile = FabricReflection.callAny(player, "getGameProfile",
                        new Class<?>[0], new Object[0]);
                    if (gameProfile != null) {
                        Object isOp = FabricReflection.call(pm, "isOp",
                            new Class<?>[]{gameProfile.getClass()}, new Object[]{gameProfile});
                        if (isOp instanceof Boolean) return (Boolean) isOp;
                    }
                } catch (Throwable t2) { /* fall through */ }
            }
            return false;
        } catch (Throwable t) { return false; }
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