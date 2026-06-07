package link.star_dust.MinerTrack.core.update;

import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.common.UpdateConfigSource;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Platform-agnostic update checker.
 *
 * <p>This is the v2 successor to the v1.
 * It owns:
 * <ul>
 *   <li>the Modrinth HTTP fetch (cached at construction, refreshable on
 *       demand via {@link #refresh()});</li>
 *   <li>the version comparison + pre-release / channel filter
 *       (stable / beta / alpha);</li>
 *   <li>the language-driven message rendering (prefixed, colourised
 *       text returned to the platform layer).</li>
 * </ul>
 *
 * <p>What it deliberately does <em>not</em> do:
 * <ul>
 *   <li>build platform chat components (Bungee {@code BaseComponent} on
 *       Bukkit, the Fabric equivalent, …) — that is the platform
 *       adapter's job;</li>
 *   <li>dispatch messages to a sender — the platform layer resolves
 *       its own {@code CommandSender} / {@code Player} and asks
 *       {@link #checkForUpdates()} for a {@link CheckResult} plus
 *       rendered text via {@link #renderResult(LanguageBridge, CheckResult)};</li>
 *   <li>register event listeners (e.g. {@code PlayerJoinEvent} on
 *       Bukkit) — the platform layer calls
 *       {@link #shouldNotifyOnJoin()} from its own listener.</li>
 * </ul>
 *
 * <p>Dependencies: only {@code org.json} (a project-wide {@code
 * implementation} dep) and {@code java.net}. No Bukkit / Fabric imports,
 * so the same class works on every supported platform.
 */
public class UpdateManagerCore {

    /** Outcome of a one-shot update check. */
    public enum CheckResult {
        /** {@code check_update} was disabled in config. */
        DISABLED,
        /** Network / parse failure talking to Modrinth. */
        CHECK_FAILED,
        /** Cache has a latest version but the channel filter says
         *  the current build is already up to date. */
        UP_TO_DATE,
        /** A newer version is available that the current channel allows. */
        UPDATE_AVAILABLE
    }

    /** Modrinth project slug. Keep in sync with v1. */
    private static final String API_URL = "https://api.modrinth.com/v2/project/minertrack/version";
    private static final String DOWNLOAD_URL_PREFIX = "https://modrinth.com/plugin/minertrack/version/";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final String USER_AGENT = "MinerTrack Update Checker";

    private final UpdateConfigSource configSource;
    private final String currentVersion;
    private final String channel;

    // Volatile because refresh() can be called from a background
    // dispatcher on some platforms; reads (isHasNewerVersion etc.) come
    // from the main thread.
    private volatile String latestVersion;
    private volatile String downloadUrl;

    /**
     * @param configSource  reads {@code check_update} / {@code check_update_channel}
     *                      and surfaces log messages to the platform.
     * @param currentVersion the running plugin's version (e.g.
     *                       {@code "2.0.0.0-dev"}). Compared against the
     *                       Modrinth latest to decide whether an update
     *                       notification is warranted.
     */
    public UpdateManagerCore(UpdateConfigSource configSource, String currentVersion) {
        this.configSource = configSource;
        this.currentVersion = currentVersion;

        boolean enabled = true;
        String ch = "stable";
        try {
            if (configSource != null) {
                enabled = configSource.isUpdateCheckEnabled();
                String c = configSource.getUpdateCheckChannel();
                if (c != null && !c.isEmpty()) ch = c;
            }
        } catch (Exception e) {
            logSafely("Could not read check_update setting, defaulting to enabled/stable: " + e.getMessage());
        }
        // Channel is applied at the "is this an upgrade" check; the
        // cached latestVersion is always populated when the network
        // call succeeds, so /mtrack update can still report upstream's
        // current version regardless of the channel filter.
        this.channel = ch == null ? "stable" : ch.toLowerCase();

        if (enabled) {
            refresh();
        } else {
            this.latestVersion = null;
            this.downloadUrl = null;
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────

    /** @return the running plugin's version, as supplied to the constructor. */
    public String getCurrentVersion() {
        return currentVersion;
    }

    /**
     * @return the latest version reported by Modrinth at the time of the
     *         last {@link #refresh()}, or {@code null} if disabled / the
     *         fetch failed. <em>Not</em> filtered by the channel — the
     *         channel only affects {@link #isHasNewerVersion()}.
     */
    public String getLatestVersion() {
        return latestVersion;
    }

    /** @return the Modrinth download URL for the latest version, or {@code null}. */
    public String getDownloadUrl() {
        return downloadUrl;
    }

    /**
     * @return {@code true} when {@code check_update} is enabled AND a newer
     *         version exists on Modrinth that the current channel allows
     *         upgrading to.
     */
    public boolean isHasNewerVersion() {
        return latestVersion != null && shouldConsiderAsUpdate(latestVersion, currentVersion);
    }

    /**
     * @return whether the platform should announce an update to joining
     *         players. Currently identical to {@link #isHasNewerVersion()}
     *         but kept separate so future logic (e.g. silencing updates
     *         for a few hours after a manual check) can branch here
     *         without touching every call site.
     */
    public boolean shouldNotifyOnJoin() {
        return isHasNewerVersion();
    }

    // ─── Commands ─────────────────────────────────────────────────────────

    /**
     * Re-fetch the latest version from Modrinth. Safe to call from any
     * thread. Updates {@link #getLatestVersion()} / {@link #getDownloadUrl()}
     * atomically (via the {@code volatile} fields) and logs (does not
     * throw) on failure.
     */
    public void refresh() {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

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
                logSafely("No versions found on Modrinth.");
                this.latestVersion = null;
                this.downloadUrl = null;
            }
        } catch (IOException | org.json.JSONException e) {
            logSafely("Failed to check for updates from Modrinth: " + e.getMessage());
            this.latestVersion = null;
            this.downloadUrl = null;
        }
    }

    /**
     * Re-fetch + classify the result in a single call. Used by the
     * {@code /mtrack update} command path so a manual check always
     * reflects the latest upstream state.
     */
    public CheckResult checkForUpdates() {
        refresh();
        if (latestVersion == null) {
            // Distinguish "disabled" from "fetch failed" so the
            // platform layer can render an appropriate message.
            if (configSource != null && !configSource.isUpdateCheckEnabled()) {
                return CheckResult.DISABLED;
            }
            return CheckResult.CHECK_FAILED;
        }
        return shouldConsiderAsUpdate(latestVersion, currentVersion)
            ? CheckResult.UPDATE_AVAILABLE
            : CheckResult.UP_TO_DATE;
    }

    // ─── Message rendering (language-driven, no platform deps) ────────────

    /**
     * Render the PlayerJoin notification text. Returns {@code null} when
     * there is no upgrade to advertise.
     */
    public String renderJoinNotification(LanguageBridge lang) {
        if (latestVersion == null || !shouldConsiderAsUpdate(latestVersion, currentVersion)) {
            return null;
        }
        return renderColouredUpdateMessage(lang, latestVersion);
    }

    /**
     * Render the message body for a given {@link CheckResult}. The
     * platform layer calls this once per check and dispatches the
     * returned String to its sender (Player or console).
     *
     * @param lang      active language bridge (used to pull the
     *                  {@code update.*} messages and the plugin prefix)
     * @param result    the result of the most recent {@link #checkForUpdates()}
     * @return the fully-rendered, colourised message body; never {@code null}
     *         (returns an empty string for {@link CheckResult#DISABLED}).
     */
    public String renderResult(LanguageBridge lang, CheckResult result) {
        switch (result) {
            case DISABLED:
                return ""; // platform should typically skip the dispatch
            case CHECK_FAILED:
                return renderPlainMessage(lang, "update.check-failed",
                    "&cFailed to check for updates.");
            case UP_TO_DATE:
                return renderPlainMessage(lang, "update.using-latest",
                    "&2You are using the latest version.");
            case UPDATE_AVAILABLE:
                return renderColouredUpdateMessage(lang, latestVersion);
        }
        return "";
    }

    // ─── Internals ────────────────────────────────────────────────────────

    private String renderColouredUpdateMessage(LanguageBridge lang, String version) {
        String key;
        String defaultText;
        if (version.contains("-beta")) {
            key = "update.beta-available";
            defaultText = "&eNew beta version %latest_version% now available!";
        } else if (version.contains("-alpha")) {
            key = "update.alpha-available";
            defaultText = "&cNew alpha version %latest_version% now available!";
        } else {
            key = "update.stable-available";
            defaultText = "&aNew stable version %latest_version% now available!";
        }
        return renderPlainMessage(lang, key, defaultText);
    }

    private String renderPlainMessage(LanguageBridge lang, String key, String defaultText) {
        if (lang == null) {
            // No language bridge available (e.g. tests). Return the
            // default text with the %latest_version% placeholder
            // substituted, untouched.
            return defaultText.replace("%latest_version%", latestVersion == null ? "" : latestVersion);
        }
        String raw = lang.getMessage(key);
        String body;
        if (raw == null || raw.isEmpty()) {
            body = defaultText;
        } else {
            body = raw;
        }
        // Substitute the version placeholder before colourising so the
        // colour code sequences in the language file survive intact.
        if (latestVersion != null) {
            body = body.replace("%latest_version%", latestVersion);
        }
        String coloured = lang.applyColors(body);
        String prefix = lang.getPrefix();
        if (prefix == null || prefix.isEmpty()) return coloured;
        return prefix + " " + coloured;
    }

    private void logSafely(String msg) {
        if (configSource == null) return;
        try { configSource.log(msg); }
        catch (Throwable ignored) { /* never let logging abort the update path */ }
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
}
