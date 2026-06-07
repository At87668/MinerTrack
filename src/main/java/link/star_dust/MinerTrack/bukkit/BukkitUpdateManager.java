package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.LanguageBridge;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Bukkit-side update checker for MinerTrack v2.
 *
 * <p>Mirrors the behaviour of v1's {@code legacy/managers/UpdateManager.java}:
 * <ul>
 *   <li>On construction (if {@code check_update} is enabled) performs a single
 *       GET against the Modrinth API to cache the latest version number +
 *       download URL. The cached state is consumed by the PlayerJoin listener
 *       in {@link BukkitPlatform} and by the {@code /mtrack update} command.
 *   <li>Performs version comparison honouring the {@code check_update_channel}
 *       config setting (stable / beta / alpha), matching v1's semantics.
 *   <li>Renders update notifications using the active {@link LanguageBridge}
 *       (so they participate in the same i18n pipeline as every other v2
 *       message), falling back to the same hard-coded English defaults v1
 *       used when the language file is missing the key.
 * </ul>
 *
 * <p>Unlike v1, this class is intentionally kept inside the {@code bukkit/}
 * shim because the Bungee chat components used for the hover/click message
 * are server-platform specific; the {@code common/} layer only sees the
 * boolean predicates {@code isHasNewerVersion()} /
 * {@code shouldNotifyOnJoin()}.
 */
public class BukkitUpdateManager {
    /** Modrinth project slug. Keep in sync with v1. */
    private static final String API_URL = "https://api.modrinth.com/v2/project/minertrack/version";
    private static final String DOWNLOAD_URL_PREFIX = "https://modrinth.com/plugin/minertrack/version/";

    private final BukkitAdapter adapter;
    private final String currentVersion;
    private String latestVersion;
    private String downloadUrl;

    private final String channel;

    /**
     * @param adapter       the platform adapter (for version + logging)
     * @param detectionBridge the active detection bridge, used to read
     *                        {@code check_update} / {@code check_update_channel}
     *                        from the merged config. Passing the bridge
     *                        explicitly (instead of relying on
     *                        {@link BukkitDetectionBridge#getActive()})
     *                        keeps construction order in {@link BukkitPlatform}
     *                        explicit and lets us swap it out in tests.
     */
    public BukkitUpdateManager(BukkitAdapter adapter, DetectionBridge detectionBridge) {
        this.adapter = adapter;
        this.currentVersion = adapter.getVersion();

        boolean enabled = true;
        String channel = "stable";
        try {
            if (detectionBridge != null) {
                enabled = detectionBridge.getConfigBoolean("check_update", true);
                Object ch = detectionBridge.getConfig("check_update_channel");
                if (ch != null) channel = ch.toString();
            }
        } catch (Exception e) {
            adapter.info("Could not read check_update setting, defaulting to enabled/stable: " + e.getMessage());
        }

        // Channel is intentionally only applied to the "is this an upgrade"
        // check (matching v1's behaviour); the raw cached latestVersion
        // is still populated so the /mtrack update command can show what
        // the upstream currently is regardless of the user's filter.
        this.channel = channel.toLowerCase();

        if (enabled) {
            fetchLatestVersionFromModrinth();
        } else {
            this.latestVersion = null;
            this.downloadUrl = null;
        }
    }

    /**
     * @return {@code true} when {@code check_update} is enabled AND a newer
     *         version exists on Modrinth that the current channel allows
     *         upgrading to.
     */
    public boolean isHasNewerVersion() {
        return latestVersion != null && shouldConsiderAsUpdate(latestVersion, currentVersion);
    }

    /** @return whether the PlayerJoin listener should announce updates. */
    public boolean shouldNotifyOnJoin() {
        return isHasNewerVersion();
    }

    /**
     * Console-driven or command-driven update check. Sends a localised
     * message to {@code sender} (or to the console if {@code sender} is
     * {@code null}) describing the result.
     */
    public void checkForUpdates(CommandSender sender, LanguageBridge lang) {
        // Re-fetch on demand so a manual /mtrack update always shows the
        // latest upstream version, not whatever we cached at startup.
        fetchLatestVersionFromModrinth();

        if (latestVersion == null) {
            sendMessage(sender, lang, "update.check-failed", "&cFailed to check for updates.");
            return;
        }

        if (shouldConsiderAsUpdate(latestVersion, currentVersion)) {
            sendUpdateMessage(sender, lang, latestVersion);
        } else {
            sendMessage(sender, lang, "update.using-latest", "&2You are using the latest version.");
        }
    }

    /**
     * Build a hover/clickable chat component for the PlayerJoin notification.
     * Returns {@code null} when there is nothing to advertise.
     */
    public BaseComponent[] getUpdateMessageComponent(LanguageBridge lang) {
        if (latestVersion == null || !shouldConsiderAsUpdate(latestVersion, currentVersion)) {
            return null;
        }

        String messageKey;
        String defaultMessage;
        if (latestVersion.contains("-beta")) {
            messageKey = "update.beta-available";
            defaultMessage = "&eNew beta version %latest_version% now available!";
        } else if (latestVersion.contains("-alpha")) {
            messageKey = "update.alpha-available";
            defaultMessage = "&cNew alpha version %latest_version% now available!";
        } else {
            messageKey = "update.stable-available";
            defaultMessage = "&aNew stable version %latest_version% now available!";
        }

        // Render the message body (no prefix) using the active language
        // bridge. getMessage returns the raw YAML value (uncoloured,
        // without the prefix); we apply colours ourselves and substitute
        // the version placeholder, falling back to a hard-coded English
        // string when the language file removed the key entirely.
        String raw = lang.getMessage(messageKey);
        String body;
        if (raw == null || raw.isEmpty()) {
            body = defaultMessage.replace("%latest_version%", latestVersion);
        } else {
            body = raw.replace("%latest_version%", latestVersion);
        }
        String prefix = lang.getPrefix();
        String message = (prefix == null || prefix.isEmpty() ? "" : prefix + " ")
            + lang.applyColors(body);

        TextComponent component = new TextComponent(message);
        if (downloadUrl != null) {
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, downloadUrl));
            component.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[] {
                    new TextComponent(lang.applyColors("&f" + latestVersion + ": " + downloadUrl))
                }
            ));
        }
        return new BaseComponent[] { component };
    }

    // ─── Modrinth fetch ────────────────────────────────────────────────────

    private void fetchLatestVersionFromModrinth() {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "MinerTrack Update Checker");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            JSONArray versions = new JSONArray(response.toString());
            if (versions.length() > 0) {
                JSONObject latest = versions.getJSONObject(0);
                this.latestVersion = latest.getString("version_number");
                this.downloadUrl = DOWNLOAD_URL_PREFIX + this.latestVersion;
            } else {
                adapter.info("No versions found on Modrinth.");
                this.latestVersion = null;
                this.downloadUrl = null;
            }
        } catch (IOException | org.json.JSONException e) {
            adapter.info("Failed to check for updates from Modrinth: " + e.getMessage());
            this.latestVersion = null;
            this.downloadUrl = null;
        }
    }

    // ─── Version comparison (mirrors v1 UpdateManager) ────────────────────

    private boolean shouldConsiderAsUpdate(String latestVersion, String currentVersion) {
        if (latestVersion == null || currentVersion == null) return false;

        Version latest = parseVersion(latestVersion);
        Version current = parseVersion(currentVersion);

        int mainCompare = compareVersionNumbers(latest.mainParts, current.mainParts);
        if (mainCompare < 0) return false;
        if (mainCompare > 0) return isVersionAllowedByChannel(latest, channel);

        if (latest.preReleaseTag == null && current.preReleaseTag == null) return false;
        if (latest.preReleaseTag == null && current.preReleaseTag != null) {
            return isVersionAllowedByChannel(latest, channel);
        }
        if (latest.preReleaseTag != null) {
            if (latest.isNewerPreReleaseThan(current)) {
                return isVersionAllowedByChannel(latest, channel);
            }
        }
        return false;
    }

    private boolean isVersionAllowedByChannel(Version version, String channel) {
        if (version.preReleaseTag == null) return true;
        String tag = version.preReleaseTag.toLowerCase();
        if ("alpha".equals(channel)) return true;
        if ("beta".equals(channel)) return tag.startsWith("beta");
        return false; // stable or unknown
    }

    private static class Version {
        final int[] mainParts;
        final String preReleaseTag;

        Version(int[] mainParts, String preReleaseTag) {
            this.mainParts = mainParts;
            this.preReleaseTag = preReleaseTag;
        }

        boolean isNewerPreReleaseThan(Version other) {
            boolean thisStable = this.preReleaseTag == null;
            boolean otherStable = other.preReleaseTag == null;
            if (thisStable && !otherStable) return true;
            if (!thisStable && otherStable) return false;
            if (thisStable && otherStable) return false;
            return comparePreRelease(this.preReleaseTag, other.preReleaseTag) > 0;
        }
    }

    private Version parseVersion(String versionStr) {
        versionStr = versionStr.replaceFirst("^v", "");
        String[] parts = versionStr.split("-", 2);
        String main = parts[0];
        String pre = parts.length > 1 ? parts[1] : null;
        String[] mainSplit = main.split("\\.");
        int[] mainParts = new int[mainSplit.length];
        for (int i = 0; i < mainSplit.length; i++) {
            mainParts[i] = parsePositiveInt(mainSplit[i], 0);
        }
        return new Version(mainParts, pre);
    }

    private int compareVersionNumbers(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int va = i < a.length ? a[i] : 0;
            int vb = i < b.length ? b[i] : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int comparePreRelease(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        String aLower = a.toLowerCase();
        String bLower = b.toLowerCase();
        boolean aIsAlpha = aLower.startsWith("alpha");
        boolean aIsBeta = aLower.startsWith("beta");
        boolean bIsAlpha = bLower.startsWith("alpha");
        boolean bIsBeta = bLower.startsWith("beta");
        if (aIsBeta && bIsAlpha) return 1;
        if (aIsAlpha && bIsBeta) return -1;
        if ((aIsBeta || aIsAlpha) != (bIsBeta || bIsAlpha)) return a.compareTo(b);
        int numA = extractNumericSuffix(a);
        int numB = extractNumericSuffix(b);
        if (numA != numB) return Integer.compare(numA, numB);
        return a.compareTo(b);
    }

    private static int extractNumericSuffix(String tag) {
        String[] parts = tag.split("[^0-9]+");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty()) {
                try { return Integer.parseInt(parts[i]); }
                catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    private static int parsePositiveInt(String str, int defaultValue) {
        try {
            int val = Integer.parseInt(str.trim());
            return val >= 0 ? val : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ─── Message dispatch ─────────────────────────────────────────────────

    private void sendMessage(CommandSender sender, LanguageBridge lang, String key, String defaultMessage) {
        String raw = lang.getMessage(key);
        String text = (raw == null || raw.isEmpty()) ? defaultMessage : raw;
        String colored = lang.applyColors(text);
        if (sender != null) {
            sender.sendMessage(colored);
        } else {
            Bukkit.getConsoleSender().sendMessage(colored);
        }
    }

    private void sendUpdateMessage(CommandSender sender, LanguageBridge lang, String latestVersion) {
        String messageKey;
        String defaultMessage;
        if (latestVersion.contains("-beta")) {
            messageKey = "update.beta-available";
            defaultMessage = "&eNew beta version %latest_version% now available!";
        } else if (latestVersion.contains("-alpha")) {
            messageKey = "update.alpha-available";
            defaultMessage = "&cNew alpha version %latest_version% now available!";
        } else {
            messageKey = "update.stable-available";
            defaultMessage = "&aNew stable version %latest_version% now available!";
        }

        String raw = lang.getMessage(messageKey);
        String text = (raw == null || raw.isEmpty()) ? defaultMessage : raw;
        text = lang.applyColors(text).replace("%latest_version%", latestVersion);

        TextComponent component = new TextComponent(text);
        if (downloadUrl != null) {
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, downloadUrl));
            component.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[] {
                    new TextComponent(lang.applyColors("&f" + latestVersion + ": " + downloadUrl))
                }
            ));
        }

        if (sender instanceof Player) {
            ((Player) sender).spigot().sendMessage(component);
        } else {
            Bukkit.getConsoleSender().sendMessage(text);
        }
    }

}
