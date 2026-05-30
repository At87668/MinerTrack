package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.LanguageBridge;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Bukkit implementation of LanguageBridge.
 * Loads language.yml from the plugin data folder with defaults from JAR resources.
 */
public class BukkitLanguageBridge implements LanguageBridge {
    private final BukkitAdapter adapter;
    private YamlConfiguration langConfig;
    private final File languageFile;

    public BukkitLanguageBridge(BukkitAdapter adapter) {
        this.adapter = adapter;
        this.languageFile = new File(adapter.getDataFolder(), "language.yml");
        loadLanguageFile();
    }

    private void loadLanguageFile() {
        if (!languageFile.exists()) {
            adapter.saveResource("language.yml", false);
        }

        // Load defaults from JAR first, then overlay user's file on top.
        // This ensures all keys (including help header) exist even if the
        // user's file is missing them.
        YamlConfiguration defaultsConfig = new YamlConfiguration();
        try (InputStream defaultStream = adapter.getResource("language.yml")) {
            if (defaultStream != null) {
                defaultsConfig.load(new InputStreamReader(defaultStream));
            }
        } catch (Exception ignored) {}

        langConfig = new YamlConfiguration();
        // Start with all defaults
        for (String key : defaultsConfig.getKeys(true)) {
            langConfig.set(key, defaultsConfig.get(key));
        }
        // Overlay user's values (preserves their customizations)
        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(languageFile);
        for (String key : userConfig.getKeys(true)) {
            langConfig.set(key, userConfig.get(key));
        }

        // Save merged config back to disk — this is the key step that matches v1.
        // After first load the user's file will contain all keys (defaults + customizations),
        // so subsequent loads always have a complete file even if they deleted entries.
        try {
            langConfig.save(languageFile);
        } catch (Exception ignored) {}
    }

    public void reloadLanguage() {
        loadLanguageFile();
    }

    @Override
    public String getPrefix() {
        return applyColors(langConfig.getString("prefix", "&8[&9&MinerTrack&8]&r "));
    }

    @Override
    public String getPrefixedMessage(String key) {
        return getPrefix() + " " + applyColors(langConfig.getString(key, key));
    }

    @Override
    public String getMessage(String path) {
        return langConfig.getString(path);
    }

    @Override
    public String getColoredMessage(String path) {
        return applyColors(langConfig.getString(path, ""));
    }

    @Override
    public String applyColors(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public List<String> getHelpMessages() {
        List<String> raw = langConfig.getStringList("help");
        List<String> colored = new ArrayList<>();
        for (String line : raw) colored.add(applyColors(line));
        return colored;
    }

    @Override
    public String getLogFormat() {
        return langConfig.getString("log-format",
            "%year%-%month%-%day% %hour%:%minute%:%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%");
    }

    @Override
    public boolean isKickBroadcastEnabled() {
        return langConfig.getBoolean("kick-broadcast", true);
    }

    @Override
    public String getKickFormat() {
        return applyColors(langConfig.getString("kick-format", "&cYou have been kicked for &e%reason%"));
    }
}