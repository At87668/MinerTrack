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
    private boolean isHasNewerVersion;
    private String latestVersion;
    private String currentVersion;
    private String downloadUrl;

    public UpdateManager(MinerTrack plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();

        if (plugin.getConfigManager().updateCheck()) {
            fetchLatestVersionFromModrinth();
        } else {
            latestVersion = "0.0.0";
        }

        if (isNewerVersion(latestVersion, currentVersion)) {
            isHasNewerVersion = true;
        } else {
            isHasNewerVersion = false;
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

        if (isHasNewerVersion()) {
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
                JSONArray files = latest.getJSONArray("files");
                if (files.length() > 0) {
                    this.downloadUrl = files.getJSONObject(0).getString("url");
                } else {
                    this.downloadUrl = "https://modrinth.com/plugin/minertrack";
                }
            } else {
                plugin.getLogger().warning("No versions found on Modrinth.");
                this.latestVersion = null;
            }

        } catch (IOException | org.json.JSONException e) {
            plugin.getLogger().warning("Failed to check for updates from Modrinth: " + e.getMessage());
            this.latestVersion = null;
            this.downloadUrl = null;
        }
    }

    private boolean isNewerVersion(String latestVersion, String currentVersion) {
        if (latestVersion == null) {
            return false;
        }

        String latest = latestVersion.split("-")[0];
        String current = currentVersion.split("-")[0];

        String[] latestParts = latest.split("\\.");
        String[] currentParts = current.split("\\.");

        for (int i = 0; i < Math.max(latestParts.length, currentParts.length); i++) {
            int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            if (latestPart > currentPart) {
                return true;
            } else if (latestPart < currentPart) {
                return false;
            }
        }

        return false;
    }

    public boolean isHasNewerVersion() {
        return isHasNewerVersion;
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

    public BaseComponent[] getUpdateMessageComponent() {
        if (!isHasNewerVersion || latestVersion == null) {
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
                new TextComponent[] {
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
}