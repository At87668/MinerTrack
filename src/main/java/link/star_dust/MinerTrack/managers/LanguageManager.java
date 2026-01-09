/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/managers/LanguageManager.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack.managers;

import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import link.star_dust.MinerTrack.MinerTrack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.List;
import java.util.stream.Collectors;

public class LanguageManager {
    private final MinerTrack plugin;
    private YamlConfiguration languageConfig;
    private final File languageFile;
    private static LanguageManager instance;

    public LanguageManager(MinerTrack plugin) {
        this.plugin = plugin;
        this.languageFile = new File(plugin.getDataFolder(), "language.yml");
        loadLanguageFile();
    }

    // Reload the language file
    public void reloadLanguageFile() {
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);
    }

    public void loadLanguageFile() {
        // Save default language file if it doesn't exist
        if (!languageFile.exists()) {
            plugin.saveResource("language.yml", false);
        }

        // Extract any translation files from the Translations/ resource folder
        extractTranslationFiles();

        // Load the language file
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);

        // Load defaults from the resource file using InputStreamReader
        try (InputStream defaultStream = plugin.getResource("language.yml")) {
            if (defaultStream != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
                languageConfig.setDefaults(defaultConfig);
                languageConfig.options().copyDefaults(true);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not load default language configuration: " + e.getMessage());
        }

        // Save only the custom values back to file
        saveCustomLanguageFile();
    }
    
    public static LanguageManager getInstance(MinerTrack plugin) {
		if (instance == null) {
            instance = new LanguageManager(plugin);
        }
        return instance;
    }

    public String getKickMessage(String playerName) {
        return applyColors(getMessage("kick-format").replace("%player%", playerName));
    }

    public String getPrefix() {
        return applyColors(getMessage("prefix"));
    }

    public List<String> getHelpMessages() {
        List<String> helpMessages = languageConfig.getStringList("help");
        return helpMessages.stream().map(this::applyColors).collect(Collectors.toList());
    }

    public String getPrefixedMessage(String key) {
        return getPrefix() + " " + applyColors(getMessage(key));
    }

    public String getMessage(String path) {
        return languageConfig.getString(path);
    }

    public String getColoredMessage(String path) {
        return applyColors(languageConfig.getString(path));
    }

    public String applyColors(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    public String getLogFormat() {
        return getMessage("log-format");
    }
    
    public boolean isKickBroadcastEnabled() {
        return languageConfig.getBoolean("kick-broadcast", true);
    }
    
    public String logNotFound(String log_name) {
        return applyColors(getMessage("log-not-found").replace("{log_file}", log_name));
    }

    public int getLogViewerLinesPerPage() {
        return languageConfig.getInt("log-viewer-lines-per-page", 10);
    }

    // Save only custom values
    private void saveCustomLanguageFile() {
        try {
            languageConfig.save(languageFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save custom language configuration to " + languageFile.getName() + ": " + e.getMessage());
        }
    }

    // Extract all files under Translations/ from the plugin JAR (or classes dir) into the data folder
    private void extractTranslationFiles() {
        try {
            URL codeSourceLocation = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            File codeSourceFile = new File(codeSourceLocation.toURI());

            if (codeSourceFile.isFile()) {
                // Running from a JAR - use plugin.saveResource to extract bundled resources
                try (JarFile jar = new JarFile(codeSourceFile)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith("Translations/") && !entry.isDirectory()) {
                            try {
                                // Always overwrite bundled Translations files so they match the JAR
                                plugin.saveResource(name, true);
                            } catch (IllegalArgumentException | IllegalStateException ex) {
                                // saveResource may throw if resource not found or IO error; log and continue
                                plugin.getLogger().warning("Could not save resource '" + name + "': " + ex.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (URISyntaxException | IOException e) {
            plugin.getLogger().severe("Could not extract translation files: " + e.getMessage());
        }
    }

    public String getPrefixedMessageWithDefault(String key, String defaultMessage) {
        String message = getMessage(key);

        if (message == null || message.isEmpty()) {
            message = defaultMessage;
        }

        return applyColors(getPrefix() + message);
    }
}