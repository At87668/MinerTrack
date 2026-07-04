package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.command.MinerTrackCommandCore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Fabric command executor. Delegates to MinerTrackCommandCore.
 * Uses FabricReflection for Minecraft type access (not on compile classpath).
 */
public class FabricCommandExecutor {
    private final FabricAdapter adapter;
    private final FabricLanguageBridge langBridge;
    private final ViolationManagerBridge vlBridge;
    private final FabricUpdateManager updateManager;
    private final DetectionBridge detectionBridge;

    public FabricCommandExecutor(FabricAdapter adapter,
                                  FabricLanguageBridge langBridge,
                                  ViolationManagerBridge vlBridge,
                                  FabricUpdateManager updateManager,
                                  DetectionBridge detectionBridge) {
        this.adapter = adapter;
        this.langBridge = langBridge;
        this.vlBridge = vlBridge;
        this.updateManager = updateManager;
        this.detectionBridge = detectionBridge;
    }

    private MinerTrackCommandCore buildCore(Object source) {
        CommandBridge cmdBridge = new FabricCommandBridge(source, vlBridge.getVerbosePlayers());
        return new MinerTrackCommandCore(
            langBridge,
            vlBridge,
            cmdBridge,
            new PlayerLookupImpl(),
            new KickBridgeImpl(),
            new ConfigReloadBridgeImpl(),
            new UpdateCheckBridgeImpl(),
            new LogViewerBridgeImpl()
        );
    }

    public boolean onCommand(Object source, String[] args) {
        return buildCore(source).onCommand(args);
    }

    public List<String> onTabComplete(Object source, String[] args) {
        return buildCore(source).onTabComplete(args);
    }

    // ── Shared server helpers ────────────────────────────────────────────

    /** Resolve MinecraftServer.getServer() reflectively. */
    private static Object server() {
        return FabricReflection.callStatic("net.minecraft.server.MinecraftServer",
            "getServer", new Class<?>[0], new Object[0]);
    }

    /** Resolve ServerPlayerEntity from UUID. */
    private static Object playerByUuid(UUID uuid) {
        Object server = server();
        if (server == null) return null;
        Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
        if (pm == null) return null;
        return FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{uuid});
    }

    /** Create a Text/Component from a plain string (MC version-aware). */
    private static Object literalText(String message) {
        // 1. MC 26.1+: Component.literal(String)
        try {
            Class<?> compCls = Class.forName("net.minecraft.network.chat.Component");
            java.lang.reflect.Method literal = compCls.getMethod("literal", String.class);
            return literal.invoke(null, message);
        } catch (Throwable t) { /* fall through */ }

        // 2. MC 1.19.3+: Text.literal(String)
        Class<?> textCls = FabricReflection.forName("net.minecraft.text.Text");
        if (textCls != null) {
            Object text = FabricReflection.callStatic("net.minecraft.text.Text",
                "literal", new Class<?>[]{String.class}, new Object[]{message});
            if (text != null) return text;
        }

        // 3. MC 1.18-1.19.2: new LiteralText(String)
        Class<?> ltCls = FabricReflection.forName("net.minecraft.text.LiteralText");
        if (ltCls == null) return null;
        try {
            return ltCls.getDeclaredConstructor(String.class).newInstance(message);
        } catch (Throwable t) {
            return null;
        }
    }

    // ─── PlayerLookup ────────────────────────────────────────────────────

    private class PlayerLookupImpl implements MinerTrackCommandCore.PlayerLookup {
        @Override
        public UUID getPlayerUUID(String name) {
            try {
                Object server = server();
                if (server == null) return null;
                Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
                if (pm == null) return null;
                Object player = FabricReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name});
                if (player == null) return null;
                Object uuid = FabricReflection.callAny(player, "getUuid", new Class<?>[0], new Object[0]);
                return uuid instanceof UUID ? (UUID) uuid : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public String getPlayerName(UUID uuid) {
            try {
                Object player = playerByUuid(uuid);
                if (player == null) return uuid.toString();
                Object name = FabricReflection.callAny(player, "getName", new Class<?>[0], new Object[0]);
                return name == null ? uuid.toString() : name.toString();
            } catch (Throwable t) {
                return uuid.toString();
            }
        }

        @Override
        public boolean isOnline(UUID uuid) {
            return playerByUuid(uuid) != null;
        }

        @Override
        public List<String> getOnlinePlayerNames() {
            List<String> names = new ArrayList<>();
            try {
                Object server = server();
                if (server == null) return names;
                Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
                if (pm == null) return names;
                Object list = FabricReflection.call(pm, "getPlayerList", new Class<?>[0], new Object[0]);
                if (list == null) return names;
                if (list instanceof java.util.Collection) {
                    for (Object p : (java.util.Collection<?>) list) {
                        Object name = FabricReflection.callAny(p, "getName", new Class<?>[0], new Object[0]);
                        if (name != null) names.add(name.toString());
                    }
                }
            } catch (Throwable t) {
                // Server not up yet.
            }
            return names;
        }
    }

    // ─── KickBridge ──────────────────────────────────────────────────────

    private class KickBridgeImpl implements MinerTrackCommandCore.KickBridge {
        @Override
        public void kickPlayer(UUID playerId, String reason) {
            try {
                Object player = playerByUuid(playerId);
                if (player == null) return;
                Object network = FabricReflection.callAny(player, "networkHandler", new Class<?>[0], new Object[0]);
                if (network == null) return;
                Object text = literalText(reason == null ? "Kicked by MinerTrack" : reason);
                if (text == null) return;
                Class<?> textCls = resolveTextComponentClass();
                FabricReflection.callAny(network, "disconnect", new Class<?>[]{textCls}, new Object[]{text});
            } catch (Throwable t) {
                adapter.warning("Failed to kick player " + playerId + ": " + t.getMessage());
            }
        }

        @Override
        public boolean isKickStrikeLightning() {
            try {
                return detectionBridge.getConfigBoolean("kick_strike_lightning", true);
            } catch (Throwable t) {
                return true;
            }
        }

        @Override
        public void strikeLightningEffect(UUID playerId) {
            try {
                Object player = playerByUuid(playerId);
                if (player == null) return;
                Object world = FabricReflection.callAny(player, "getWorld", new Class<?>[0], new Object[0]);
                if (world == null) return;
                Object registryKey = FabricReflection.callAny(world, "getRegistryKey", new Class<?>[0], new Object[0]);
                if (registryKey == null) return;
                Object server = server();
                if (server == null) return;
                Object serverWorld = FabricReflection.call(server, "getWorld",
                    new Class<?>[]{registryKey.getClass()}, new Object[]{registryKey});
                if (serverWorld == null) return;
                // Cosmetic-only lightning strike; created via reflection
                Object lightning = FabricReflection.newInstance("net.minecraft.entity.LightningEntity",
                    new Class<?>[]{
                        FabricReflection.forName("net.minecraft.entity.EntityType"),
                        FabricReflection.forName("net.minecraft.server.world.ServerWorld")
                    },
                    new Object[]{
                        FabricReflection.forName("net.minecraft.entity.EntityType")
                            .getField("LIGHTNING_BOLT").get(null),
                        serverWorld
                    });
                if (lightning == null) return;
                Object x = FabricReflection.callAny(player, "getX", new Class<?>[0], new Object[0]);
                Object y = FabricReflection.callAny(player, "getY", new Class<?>[0], new Object[0]);
                Object z = FabricReflection.callAny(player, "getZ", new Class<?>[0], new Object[0]);
                FabricReflection.callAny(lightning, "refreshPositionAfterTeleport",
                    new Class<?>[]{double.class, double.class, double.class},
                    new Object[]{x, y, z});
            } catch (Throwable t) {
                // Cosmetic only — silent fallback.
            }
        }

        @Override
        public void broadcastMessage(String message) {
            try {
                Object server = server();
                if (server == null) return;
                Object pm = FabricReflection.call(server, "getPlayerManager", new Class<?>[0], new Object[0]);
                if (pm == null) return;
                Object text = literalText(message);
                if (text == null) return;
                Class<?> textCls = resolveTextComponentClass();
                FabricReflection.call(pm, "broadcast", new Class<?>[]{textCls, boolean.class}, new Object[]{text, false});
            } catch (Throwable t) {
                // No players online.
            }
        }
    }

    // ─── ConfigReloadBridge ──────────────────────────────────────────────

    private class ConfigReloadBridgeImpl implements MinerTrackCommandCore.ConfigReloadBridge {
        @Override
        public void reloadConfig() {
            adapter.reloadConfig();
            if (detectionBridge != null) detectionBridge.clearConfigCache();
            if (detectionBridge != null) detectionBridge.loadGroupConfigs();
            try {
                FabricViolationManager vm = FabricViolationManager.getActive();
                if (vm != null) {
                    vm.reloadConfig();
                    link.star_dust.MinerTrack.core.config.WebhookConfig freshConfig =
                        link.star_dust.MinerTrack.core.config.WebhookConfig.from(vm.getMainConfig());
                    vm.setWebhookEngine(new link.star_dust.MinerTrack.core.violation.WebhookEngine(
                        freshConfig, new FabricWebhookSender(adapter)));
                }
            } catch (Throwable t) {
                adapter.info("Failed to refresh webhook engine on reload: " + t.getMessage());
            }
        }

        @Override
        public void reloadLanguage() {
            langBridge.reloadLanguage();
        }
    }

    // ─── UpdateCheckBridge ───────────────────────────────────────────────

    private class UpdateCheckBridgeImpl implements MinerTrackCommandCore.UpdateCheckBridge {
        @Override
        public void checkForUpdates(CommandBridge sender) {
            updateManager.checkForUpdates(sender);
        }
    }

    // ─── LogViewerBridge ─────────────────────────────────────────────────

    private class LogViewerBridgeImpl implements MinerTrackCommandCore.LogViewerBridge {
        @Override
        public List<String> getLogFileNames(int maxFiles) {
            File logDir = new File(adapter.getDataFolder(), "logs");
            if (!logDir.exists()) return new ArrayList<>();
            File[] files = logDir.listFiles((d, n) -> n.toLowerCase().endsWith(".log"));
            if (files == null) return new ArrayList<>();
            Arrays.sort(files, Comparator.comparing(File::getName).reversed());
            List<String> names = new ArrayList<>();
            for (int i = 0; i < files.length && i < maxFiles; i++) {
                names.add(files[i].getName());
            }
            return names;
        }

        @Override
        public byte[] readLogFile(String fileName) {
            File logDir = new File(adapter.getDataFolder(), "logs");
            File target = new File(logDir, fileName);
            if (!target.exists() || !target.isFile()) return new byte[0];
            try {
                return Files.readAllBytes(target.toPath());
            } catch (IOException e) {
                return new byte[0];
            }
        }

        @Override
        public int getLogViewerLinesPerPage() {
            return 10;
        }

        @Override
        public String getLogFormat() {
            return vlBridge.getLogFormat();
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
                return Class.forName("net.minecraft.text.Text");
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
    }
}
