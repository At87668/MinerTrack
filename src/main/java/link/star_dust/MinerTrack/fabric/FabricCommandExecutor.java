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
            new PlayerLookupImpl(source),
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

    /** Resolve MinecraftServer instance (version-aware). */
    private static Object server() {
        return FabricReflection.getServer();
    }

    /** Resolve ServerPlayerEntity from UUID using the command source's server. */
    private static Object playerByUuid(Object source, UUID uuid) {
        if (source == null) return playerByUuid(uuid);
        try {
            Object server = FabricReflection.callAny(source, "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return playerByUuid(uuid);
            // MC 26.1+: getPlayerList(); 1.18-1.21: getPlayerManager()
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return playerByUuid(uuid);
            Object player = FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{uuid});
            if (player != null) return player;
        } catch (Throwable t) {
            // Fall through to fallback
        }
        return playerByUuid(uuid);
    }

    /** Resolve ServerPlayerEntity from UUID (fallback using static server access). */
    private static Object playerByUuid(UUID uuid) {
        Object server = server();
        if (server == null) return null;
        Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
            new Class<?>[0], new Object[0]);
        if (pm == null) return null;
        return FabricReflection.call(pm, "getPlayer", new Class<?>[]{UUID.class}, new Object[]{uuid});
    }

    /** Resolve ServerPlayerEntity from name using the command source's server. */
    private static Object playerByName(Object source, String name) {
        if (source == null) return playerByName(name);
        try {
            Object server = FabricReflection.callAny(source, "getServer", new Class<?>[0], new Object[0]);
            if (server == null) return playerByName(name);
            Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                new Class<?>[0], new Object[0]);
            if (pm == null) return playerByName(name);
            Object player = FabricReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name});
            if (player != null) return player;
        } catch (Throwable t) {
            // Fall through to fallback
        }
        return playerByName(name);
    }

    /** Resolve ServerPlayerEntity from name (fallback using static server access). */
    private static Object playerByName(String name) {
        Object server = server();
        if (server == null) return null;
        Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
            new Class<?>[0], new Object[0]);
        if (pm == null) return null;
        return FabricReflection.call(pm, "getPlayerByName", new Class<?>[]{String.class}, new Object[]{name});
    }

    /** Create a Text/Component from a plain string — delegates to FabricReflection. */
    private static Object literalText(String message) {
        return FabricReflection.createText(message);
    }

    /** Resolve Text Component class — delegates to FabricReflection. */
    private static Class<?> resolveTextComponentClass() {
        return FabricReflection.resolveTextComponentClass();
    }

    // ─── PlayerLookup ────────────────────────────────────────────────────

    private class PlayerLookupImpl implements MinerTrackCommandCore.PlayerLookup {
        private final Object commandSource;

        PlayerLookupImpl(Object commandSource) {
            this.commandSource = commandSource;
        }

        @Override
        public UUID getPlayerUUID(String name) {
            try {
                // Use the command source's server for player lookup, which is
                // more reliable than the static server() accessor across MC versions.
                Object player = playerByName(commandSource, name);
                if (player == null) return null;
                Object uuid = FabricReflection.callUuid(player);
                return uuid instanceof UUID ? (UUID) uuid : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public String getPlayerName(UUID uuid) {
            try {
                Object player = playerByUuid(commandSource, uuid);
                if (player == null) return uuid.toString();
                // MC 26.1+: getName() returns Component; use readString() to unwrap.
                Object name = FabricReflection.callAny(player, "getName", new Class<?>[0], new Object[0]);
                String s = FabricReflection.readString(name);
                return s == null ? uuid.toString() : s;
            } catch (Throwable t) {
                return uuid.toString();
            }
        }

        @Override
        public boolean isOnline(UUID uuid) {
            return playerByUuid(commandSource, uuid) != null;
        }

        @Override
        public List<String> getOnlinePlayerNames() {
            List<String> names = new ArrayList<>();
            try {
                Object server = commandSource != null
                    ? FabricReflection.callAny(commandSource, "getServer", new Class<?>[0], new Object[0])
                    : server();
                if (server == null) return names;
                Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                    new Class<?>[0], new Object[0]);
                if (pm == null) return names;
                // MC 26.1+: getPlayers(); 1.18-1.21: getPlayerList()
                Object list = FabricReflection.callMigrated(pm, "getPlayers", "getPlayerList",
                    new Class<?>[0], new Object[0]);
                if (list == null) return names;
                if (list instanceof java.util.Collection) {
                    for (Object p : (java.util.Collection<?>) list) {
                        // MC 26.1+: Entity.getName() returns Component; unwrap with readString.
                        Object name = FabricReflection.callAny(p, "getName", new Class<?>[0], new Object[0]);
                        String s = FabricReflection.readString(name);
                        if (s != null) names.add(s);
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
                // MC 26.1+: connection field; 1.18-1.21: networkHandler field.
                // Both are public fields, NOT methods — use getField().
                Object network = FabricReflection.getField(player, "connection");
                if (network == null) {
                    network = FabricReflection.getField(player, "networkHandler");
                }
                if (network == null) return;
                Object text = literalText(reason == null ? "Kicked by MinerTrack" : reason);
                if (text == null) return;
                Class<?> textCls = resolveTextComponentClass();
                if (textCls == null) return;
                // MC 26.1+: disconnect(Component); 1.18-1.21: disconnect(Text)
                try {
                    FabricReflection.callAny(network, "disconnect", new Class<?>[]{textCls}, new Object[]{text});
                } catch (Throwable t1) {
                    // On older MC, "disconnect" may take DisconnectionDetails; try onDisconnect
                    FabricReflection.callAny(network, "onDisconnect", new Class<?>[]{textCls}, new Object[]{text});
                }
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
                // MC 26.1+: level(); 1.18-1.21: getWorld()/getEntityWorld()
                Object world = FabricReflection.callMigrated(player, "level", "getWorld",
                    new Class<?>[0], new Object[0]);
                if (world == null) return;
                Object registryKey = FabricReflection.callDimension(world);
                if (registryKey == null) return;
                Object server = server();
                if (server == null) return;
                // MC 26.1+: getLevel(ResourceKey); 1.18-1.21: getWorld(ResourceKey)
                Object serverWorld = FabricReflection.callMigrated(server, "getLevel", "getWorld",
                    new Class<?>[]{registryKey.getClass()}, new Object[]{registryKey});
                if (serverWorld == null) return;
                // Cosmetic-only lightning strike; created via reflection.
                // MC 1.21.1+: LightningBolt(EntityType<?>, ServerLevel)
                // MC 1.18.2:  LightningBolt(EntityType<?>, Level)
                Object lightning = null;
                try {
                    lightning = FabricReflection.newInstance("net.minecraft.world.entity.LightningBolt",
                        new Class<?>[]{
                            FabricReflection.forName("net.minecraft.world.entity.EntityType"),
                            FabricReflection.forName("net.minecraft.server.level.ServerLevel")
                        },
                        new Object[]{
                            FabricReflection.forName("net.minecraft.world.entity.EntityType")
                                .getField("LIGHTNING_BOLT").get(null),
                            serverWorld
                        });
                } catch (Throwable t) {
                    // 1.18.2 fallback: Level
                    lightning = FabricReflection.newInstance("net.minecraft.world.entity.LightningBolt",
                        new Class<?>[]{
                            FabricReflection.forName("net.minecraft.world.entity.EntityType"),
                            FabricReflection.forName("net.minecraft.world.level.Level")
                        },
                        new Object[]{
                            FabricReflection.forName("net.minecraft.world.entity.EntityType")
                                .getField("LIGHTNING_BOLT").get(null),
                            serverWorld
                        });
                }
                if (lightning == null) return;
                Object x = FabricReflection.callAny(player, "getX", new Class<?>[0], new Object[0]);
                Object y = FabricReflection.callAny(player, "getY", new Class<?>[0], new Object[0]);
                Object z = FabricReflection.callAny(player, "getZ", new Class<?>[0], new Object[0]);
                // MC 26.1+: setPos(double, double, double) — 1.18-1.21 used refreshPositionAfterTeleport
                try {
                    FabricReflection.callAny(lightning, "setPos",
                        new Class<?>[]{double.class, double.class, double.class},
                        new Object[]{x, y, z});
                } catch (Throwable t) {
                    FabricReflection.callAny(lightning, "refreshPositionAfterTeleport",
                        new Class<?>[]{double.class, double.class, double.class},
                        new Object[]{x, y, z});
                }
            } catch (Throwable t) {
                // Cosmetic only — silent fallback.
            }
        }

        @Override
        public void broadcastMessage(String message) {
            try {
                Object server = server();
                if (server == null) return;
                Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                    new Class<?>[0], new Object[0]);
                if (pm == null) return;
                // Prepend the plugin prefix so the broadcasted kick / notify
                // messages are clearly branded in chat (matches Bukkit behaviour).
                String prefix = langBridge.getPrefix();
                String fullMsg = prefix.trim() + " " + adapter.applyColors(message);
                Object text = literalText(fullMsg);
                if (text == null) return;
                Class<?> textCls = resolveTextComponentClass();

                // 1.21.1+: broadcastSystemMessage(Component, boolean)
                try {
                    FabricReflection.call(pm, "broadcastSystemMessage",
                        new Class<?>[]{textCls, boolean.class}, new Object[]{text, false});
                    return;
                } catch (Throwable t1) { /* fall through */ }

                // 1.18.2: broadcastMessage(Component, ChatType, UUID)
                // Need to resolve ChatType.CHAT reflectively
                try {
                    Class<?> chatTypeCls = FabricReflection.forName("net.minecraft.network.chat.ChatType");
                    if (chatTypeCls != null) {
                        Object chatType = FabricReflection.getField(chatTypeCls, "CHAT");
                        if (chatType == null) chatType = FabricReflection.getField(chatTypeCls, "SYSTEM");
                        if (chatType != null) {
                            FabricReflection.call(pm, "broadcastMessage",
                                new Class<?>[]{textCls, chatTypeCls, UUID.class},
                                new Object[]{text, chatType, java.util.UUID.randomUUID()});
                            return;
                        }
                    }
                } catch (Throwable t1) { /* fall through */ }

                // Last resort: broadcast(Text, boolean) on old mappings
                try {
                    FabricReflection.call(pm, "broadcast",
                        new Class<?>[]{textCls, boolean.class}, new Object[]{text, false});
                } catch (Throwable t1) { /* silent */ }
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
}
