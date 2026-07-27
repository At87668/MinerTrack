package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommandBridge;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.common.UpdateConfigSource;
import link.star_dust.MinerTrack.core.update.UpdateManagerCore;

import java.util.List;

/** Fabric update-check shim. Mirrors BukkitUpdateManager. */
public class FabricUpdateManager {
    private final FabricAdapter adapter;
    private final UpdateManagerCore core;

    public FabricUpdateManager(FabricAdapter adapter, DetectionBridge detectionBridge) {
        this.adapter = adapter;
        UpdateConfigSource source = new DetectionBridgeConfigSource(detectionBridge, adapter);
        this.core = new UpdateManagerCore(source, adapter.getVersion());
    }

    public boolean isHasNewerVersion() {
        return core.isHasNewerVersion();
    }

    public boolean shouldNotifyOnJoin() {
        return core.shouldNotifyOnJoin();
    }

    public void refresh() {
        core.refresh();
    }

    public void checkForUpdates(CommandBridge sender) {
        // Offload the HTTPS round-trip to a background thread.
        // On Fabric, commands execute on the server's main tick
        // thread (MinecraftServer extends BlockableEventLoop);
        // a synchronous network I/O would freeze the entire
        // server until the Modrinth API responds (or times out).
        new Thread(() -> {
            UpdateManagerCore.CheckResult result = core.checkForUpdates();
            String rendered = core.renderResult(stubLang(), result);
            if (rendered == null || rendered.isEmpty()) return;
            // Route the message dispatch back to the server's main
            // thread via MinecraftServer.execute(Runnable) so the
            // CommandSourceStack.sendSuccess pipeline works safely.
            Object server = FabricReflection.getServer();
            if (server != null) {
                try {
                    FabricReflection.call(server, "execute",
                        new Class<?>[]{Runnable.class},
                        new Object[]{(Runnable) () -> sender.sendMessage(rendered)});
                    return;
                } catch (Throwable t) { /* fall through to direct send */ }
            }
            sender.sendMessage(rendered);
        }, "MinerTrack-UpdateCheck").start();
    }

    private LanguageBridge stubLang() {
        FabricAdapter a = this.adapter;
        return new LanguageBridge() {
            @Override public String getPrefixedMessage(String key) { return "[" + key + "]"; }
            @Override public String getLogFormat() {
                return "%year%-%month%-%day% %hour%:%minute%:%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%";
            }
            @Override public String applyColors(String message) { return a.applyColors(message); }
            @Override public String getPrefix() { return a.applyColors("&8[&9&lMiner&c&lTrack&8]&r "); }
            @Override public List<String> getHelpMessages() { return java.util.Collections.emptyList(); }
            @Override public String getMessage(String path) { return null; }
            @Override public String getColoredMessage(String path) { return ""; }
            @Override public boolean isKickBroadcastEnabled() { return true; }
            @Override public String getKickFormat() { return "&cYou have been kicked for &e%reason%"; }
        };
    }

    private static final class DetectionBridgeConfigSource implements UpdateConfigSource {
        private final DetectionBridge bridge;
        private final FabricAdapter adapter;

        DetectionBridgeConfigSource(DetectionBridge bridge, FabricAdapter adapter) {
            this.bridge = bridge;
            this.adapter = adapter;
        }

        @Override
        public boolean isUpdateCheckEnabled() {
            try {
                if (bridge != null) return bridge.getConfigBoolean("check_update", true);
            } catch (Exception e) {
                adapter.info("Could not read check_update setting, defaulting to true: " + e.getMessage());
            }
            return true;
        }

        @Override
        public String getUpdateCheckChannel() {
            try {
                if (bridge != null) {
                    Object ch = bridge.getConfig("check_update_channel");
                    if (ch != null) return ch.toString();
                }
            } catch (Exception e) {
                adapter.info("Could not read check_update_channel setting, defaulting to stable: " + e.getMessage());
            }
            return "stable";
        }

        @Override
        public void log(String message) {
            adapter.info(message);
        }
    }
}
