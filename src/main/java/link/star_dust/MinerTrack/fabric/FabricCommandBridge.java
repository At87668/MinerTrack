package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;

import java.util.Set;
import java.util.UUID;

/**
 * Fabric implementation of {@link CommandBridge}.
 *
 * <p>Wraps a Fabric {@code ServerCommandSource} (held as
 * {@code Object} to avoid pulling {@code net.minecraft.*} onto the
 * compile classpath) so the platform-neutral command core can
 * dispatch commands, query permissions, and route chat without
 * depending on the Fabric API directly.
 *
 * <p>All {@code net.minecraft.*} access goes through
 * {@link FabricReflection}.
 */
public class FabricCommandBridge implements CommandBridge {
    private final Object source; // net.minecraft.server.command.ServerCommandSource
    private final Set<UUID> verbosePlayers;
    private volatile boolean verboseConsole = false;

    public FabricCommandBridge(Object source, Set<UUID> verbosePlayers) {
        this.source = source;
        this.verbosePlayers = verbosePlayers;
    }

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
            Class<?> textCls = FabricReflection.forName("net.minecraft.text.Text");
            Object text = FabricReflection.callStatic("net.minecraft.text.Text",
                "literal", new Class<?>[]{String.class}, new Object[]{message});
            FabricReflection.callAny(s, "sendMessage", new Class<?>[]{textCls}, new Object[]{text});
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
            Class<?> textCls = FabricReflection.forName("net.minecraft.text.Text");
            Object text = FabricReflection.callStatic("net.minecraft.text.Text",
                "literal", new Class<?>[]{String.class}, new Object[]{message});
            FabricReflection.call(player, "sendMessage",
                new Class<?>[]{textCls, boolean.class}, new Object[]{text, false});
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
            Class<?> textCls = FabricReflection.forName("net.minecraft.text.Text");
            Object text = FabricReflection.callStatic("net.minecraft.text.Text",
                "literal", new Class<?>[]{String.class}, new Object[]{message});
            FabricReflection.call(server, "sendMessage", new Class<?>[]{textCls}, new Object[]{text});
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

    @Override
    public boolean hasPermission(String node) {
        Object s = source();
        if (s == null) return false;
        try {
            Object r = FabricReflection.callAny(s, "isExecutedByPlayer", new Class<?>[0], new Object[0]);
            if (r instanceof Boolean && (Boolean) r) {
                Object lvl = FabricReflection.callAny(s, "hasPermissionLevel", new Class<?>[]{int.class}, new Object[]{2});
                return lvl instanceof Boolean && (Boolean) lvl;
            }
            // Console always has permission to run admin commands.
            return true;
        } catch (Throwable t) {
            return false;
        }
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
            Object config = FabricReflection.callAny(player, "getPlayerConfigEntry", new Class<?>[0], new Object[0]);
            if (config == null) return false;
            Object r = FabricReflection.call(pm, "isOperator", new Class<?>[]{config.getClass()}, new Object[]{config});
            return r instanceof Boolean && (Boolean) r;
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
