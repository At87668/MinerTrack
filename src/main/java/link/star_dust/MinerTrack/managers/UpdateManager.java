/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/managers/UpdateManager.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack.managers;

import link.star_dust.MinerTrack.MinerTrack;
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

public class UpdateManager {
    private final MinerTrack plugin;
    private String latestVersion;
    private final String currentVersion;
    private String downloadUrl;

    public UpdateManager(MinerTrack plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();

        if (plugin.getConfigManager().updateCheck()) {
            fetchLatestVersionFromModrinth();
        } else {
            this.latestVersion = null;
        }
    }

    public void checkForUpdates(CommandSender sender) {
        fetchLatestVersionFromModrinth();

        if (latestVersion == null) {
            String errorMessage = plugin.getLanguageManager().getPrefixedMessageWithDefault(
                "update.check-failed",
                "&cFailed to check for updates."
            );
            sendMessage(sender, errorMessage);
            return;
        }

        if (shouldConsiderAsUpdate(latestVersion, currentVersion)) {
            sendUpdateMessage(sender, latestVersion);
        } else {
            String upToDateMessage = plugin.getLanguageManager().getPrefixedMessageWithDefault(
                "update.using-latest",
                "&2You are using the latest version."
            );
            sendMessage(sender, upToDateMessage);
        }
    }

    private void fetchLatestVersionFromModrinth() {
        try {
            URL url = new URL("https://api.modrinth.com/v2/project/minertrack/version");
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
                this.downloadUrl = "https://modrinth.com/plugin/minertrack/version/" + this.latestVersion;
            } else {
                plugin.getLogger().warning("No versions found on Modrinth.");
                this.latestVersion = null;
                this.downloadUrl = null;
            }

        } catch (IOException | org.json.JSONException e) {
            plugin.getLogger().warning("Failed to check for updates from Modrinth: " + e.getMessage());
            this.latestVersion = null;
            this.downloadUrl = null;
        }
    }

    private boolean shouldConsiderAsUpdate(String latestVersion, String currentVersion) {
        if (latestVersion == null || currentVersion == null) {
            return false;
        }

        String channel = plugin.getConfigManager().updateCheckChannel().toLowerCase();
        Version latest = parseVersion(latestVersion);
        Version current = parseVersion(currentVersion);

        int mainCompare = compareVersionNumbers(latest.mainParts, current.mainParts);
        if (mainCompare < 0) {
            return false;
        }
        if (mainCompare > 0) {
            return isVersionAllowedByChannel(latest, channel);
        }

        if (latest.preReleaseTag == null && current.preReleaseTag == null) {
            return false;
        }

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
        if (version.preReleaseTag == null) {
            return true;
        }

        String tag = version.preReleaseTag.toLowerCase();
        if ("alpha".equals(channel)) {
            return true;
        } else if ("beta".equals(channel)) {
            return tag.startsWith("beta");
        } else { // stable or unknown
            return false;
        }
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
            if (va != vb) {
                return Integer.compare(va, vb);
            }
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
        if ((aIsBeta || aIsAlpha) != (bIsBeta || bIsAlpha)) {
            return a.compareTo(b); // fallback
        }

        int numA = extractNumericSuffix(a);
        int numB = extractNumericSuffix(b);
        if (numA != numB) {
            return Integer.compare(numA, numB);
        }
        return a.compareTo(b); // fallback
    }

    private static int extractNumericSuffix(String tag) {
        String[] parts = tag.split("[^0-9]+");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty()) {
                try {
                    return Integer.parseInt(parts[i]);
                } catch (NumberFormatException ignored) {}
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

    public boolean isHasNewerVersion() {
        return latestVersion != null && shouldConsiderAsUpdate(latestVersion, currentVersion);
    }

    public BaseComponent[] getUpdateMessageComponent() {
        if (latestVersion == null || !shouldConsiderAsUpdate(latestVersion, currentVersion)) {
            return null;
        }

        String messageKey;
        if (latestVersion.contains("-beta")) {
            messageKey = "update.beta-available";
        } else if (latestVersion.contains("-alpha")) {
            messageKey = "update.alpha-available";
        } else {
            messageKey = "update.stable-available";
        }

        String defaultMessage;
        if (latestVersion.contains("-beta")) {
            defaultMessage = "&eNew beta version %latest_version% now available!";
        } else if (latestVersion.contains("-alpha")) {
            defaultMessage = "&cNew alpha version %latest_version% now available!";
        } else {
            defaultMessage = "&aNew stable version %latest_version% now available!";
        }

        String baseMessage = plugin.getLanguageManager().getPrefixedMessageWithDefault(messageKey, defaultMessage)
                .replace("%latest_version%", latestVersion);

        TextComponent component = new TextComponent(plugin.getLanguageManager().applyColors(baseMessage));
        if (downloadUrl != null) {
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, downloadUrl));
            component.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[] {
                    new TextComponent(plugin.getLanguageManager().applyColors("&f" + latestVersion + ": " + downloadUrl))
                }
            ));
        }

        return new BaseComponent[]{component};
    }

    private void sendMessage(CommandSender sender, String message) {
        String coloredMessage = plugin.getLanguageManager().applyColors(message);
        if (sender != null) {
            sender.sendMessage(coloredMessage);
        } else {
            Bukkit.getConsoleSender().sendMessage(coloredMessage);
        }
    }
    
    private void sendUpdateMessage(CommandSender sender, String latestVersion) {
        String messageKey;
        if (latestVersion.contains("-beta")) {
            messageKey = "update.beta-available";
        } else if (latestVersion.contains("-alpha")) {
            messageKey = "update.alpha-available";
        } else {
            messageKey = "update.stable-available";
        }

        String defaultMessage;
        if (latestVersion.contains("-beta")) {
            defaultMessage = "&eNew beta version %latest_version% now available!";
        } else if (latestVersion.contains("-alpha")) {
            defaultMessage = "&cNew alpha version %latest_version% now available!";
        } else {
            defaultMessage = "&aNew stable version %latest_version% now available!";
        }

        String baseMessage = plugin.getLanguageManager().getPrefixedMessageWithDefault(messageKey, defaultMessage)
                .replace("%latest_version%", latestVersion);

        TextComponent component = new TextComponent(plugin.getLanguageManager().applyColors(baseMessage));
        if (downloadUrl != null) {
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, downloadUrl));
            component.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new TextComponent[] {
                    new TextComponent(plugin.getLanguageManager().applyColors("&f" + latestVersion + ": " + downloadUrl))
                }
            ));
        }

        if (sender instanceof Player) {
            ((Player) sender).spigot().sendMessage(component);
        } else {
            Bukkit.getConsoleSender().sendMessage(plugin.getLanguageManager().applyColors(baseMessage));
        }
    }
}