package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.common.UpdateConfigSource;
import link.star_dust.MinerTrack.core.update.UpdateManagerCore;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Bukkit-side update notification shim for MinerTrack v2.
 *
 * <p>This class is intentionally thin: all Modrinth I/O, version
 * comparison, channel filtering, and language-driven message rendering
 * live in
 * {@link link.star_dust.MinerTrack.core.update.UpdateManagerCore}.
 * The shim is only responsible for:
 * <ul>
 *   <li>constructing the {@link UpdateConfigSource} that bridges
 *       {@code config.yml}'s {@code check_update} /
 *       {@code check_update_channel} keys into the core's
 *       platform-agnostic interface;</li>
 *   <li>turning the core's plain-text render output into a Bungee
 *       {@link BaseComponent} with hover / click events
 *       (only available on Bukkit);</li>
 *   <li>dispatching the rendered text to a {@link CommandSender}
 *       (Player → {@code spigot().sendMessage(...)}, console →
 *       {@code sendMessage(...)}) for the {@code /mtrack update}
 *       command path.</li>
 * </ul>
 *
 * <p>The public surface of this class
 * ({@link #isHasNewerVersion()}, {@link #shouldNotifyOnJoin()},
 * {@link #getUpdateMessageComponent(LanguageBridge)},
 * {@link #checkForUpdates(CommandSender, LanguageBridge)}) is
 * preserved from the previous in-place implementation so the existing
 * call sites in {@link BukkitPlatform} and
 * {@link MinerTrackCommandExecutor} keep working unchanged.
 */
public class BukkitUpdateManager {

    private final BukkitAdapter adapter;
    private final UpdateManagerCore core;

    /**
     * @param adapter         the platform adapter (used here only to read
     *                        the current plugin version and to log
     *                        update-check failures)
     * @param detectionBridge the active detection bridge, used to read
     *                        {@code check_update} / {@code check_update_channel}
     *                        from the merged config. Passing the bridge
     *                        explicitly (instead of relying on
     *                        {@link BukkitDetectionBridge#getActive()})
     *                        keeps construction order in
     *                        {@link BukkitPlatform} explicit.
     */
    public BukkitUpdateManager(BukkitAdapter adapter, DetectionBridge detectionBridge) {
        this.adapter = adapter;
        UpdateConfigSource source = new DetectionBridgeConfigSource(detectionBridge, adapter);
        this.core = new UpdateManagerCore(source, adapter.getVersion());
    }

    /**
     * @return {@code true} when {@code check_update} is enabled AND a newer
     *         version exists on Modrinth that the current channel allows
     *         upgrading to.
     */
    public boolean isHasNewerVersion() {
        return core.isHasNewerVersion();
    }

    /** @return whether the PlayerJoin listener should announce updates. */
    public boolean shouldNotifyOnJoin() {
        return core.shouldNotifyOnJoin();
    }

    /**
     * Console-driven or command-driven update check. Sends a localised
     * message to {@code sender} (or to the console if {@code sender} is
     * {@code null}) describing the result.
     */
    public void checkForUpdates(CommandSender sender, LanguageBridge lang) {
        UpdateManagerCore.CheckResult result = core.checkForUpdates();
        String rendered = core.renderResult(lang, result);
        if (rendered == null || rendered.isEmpty()) return;

        if (result == UpdateManagerCore.CheckResult.UPDATE_AVAILABLE
                && sender instanceof Player
                && core.getDownloadUrl() != null) {
            // Player + upgrade available → send a clickable component
            // (hover shows the version + URL). The render output
            // already includes the prefix and colour codes.
            TextComponent component = new TextComponent(rendered);
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, core.getDownloadUrl()));
            component.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[] {
                    new TextComponent(lang.applyColors("&f"
                        + (core.getLatestVersion() == null ? "" : core.getLatestVersion())
                        + ": " + core.getDownloadUrl()))
                }
            ));
            ((Player) sender).spigot().sendMessage(component);
            return;
        }

        // Non-upgrade messages (up-to-date, check-failed) and console
        // senders go through the plain text path.
        if (sender != null) {
            sender.sendMessage(rendered);
        } else {
            Bukkit.getConsoleSender().sendMessage(rendered);
        }
    }

    /**
     * Build a hover/clickable chat component for the PlayerJoin notification.
     * Returns {@code null} when there is nothing to advertise.
     */
    public BaseComponent[] getUpdateMessageComponent(LanguageBridge lang) {
        String rendered = core.renderJoinNotification(lang);
        if (rendered == null) return null;
        String url = core.getDownloadUrl();
        TextComponent component = new TextComponent(rendered);
        if (url != null) {
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            String latest = core.getLatestVersion() == null ? "" : core.getLatestVersion();
            component.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[] {
                    new TextComponent(lang.applyColors("&f" + latest + ": " + url))
                }
            ));
        }
        return new BaseComponent[] { component };
    }

    // ─── Internal: bridge config.yml → UpdateConfigSource ─────────────────

    /**
     * Adapts the active Bukkit {@link DetectionBridge} (which already
     * holds the merged config.yml) to the {@link UpdateConfigSource}
     * interface the core needs. Logs through the platform adapter so
     * update-check failures appear in the usual server log.
     */
    private static final class DetectionBridgeConfigSource implements UpdateConfigSource {
        private final DetectionBridge bridge;
        private final BukkitAdapter adapter;

        DetectionBridgeConfigSource(DetectionBridge bridge, BukkitAdapter adapter) {
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
