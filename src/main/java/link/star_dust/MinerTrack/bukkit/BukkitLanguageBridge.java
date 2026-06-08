package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.core.config.LanguageMerger;
import org.bukkit.ChatColor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Bukkit implementation of LanguageBridge.
 *
 * <p>Loading / completion / persistence of {@code language.yml} is delegated
 * to {@link LanguageMerger} so the completion strategy for the language file
 * is kept completely separate from the whitelist-based recursive merge used
 * for {@code config.yml} (see
 * {@link link.star_dust.MinerTrack.core.config.ConfigMerger}).
 */
public class BukkitLanguageBridge implements LanguageBridge {
    private static final String LANGUAGE_RESOURCE = "language.yml";

    private final BukkitAdapter adapter;
    private final File languageFile;
    private CommonYaml langConfig;

    public BukkitLanguageBridge(BukkitAdapter adapter) {
        this.adapter = adapter;
        this.languageFile = new File(adapter.getDataFolder(), LANGUAGE_RESOURCE);
        loadLanguageFile();
    }

    private void loadLanguageFile() {
        langConfig = LanguageMerger.loadAndMerge(languageFile, LANGUAGE_RESOURCE, adapter, adapter.getYamlLoader());
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
        Object v = langConfig.get(path);
        return v == null ? null : v.toString();
    }

    @Override
    public String getColoredMessage(String path) {
        return applyColors(langConfig.getString(path, ""));
    }

    @SuppressWarnings("deprecation")
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
