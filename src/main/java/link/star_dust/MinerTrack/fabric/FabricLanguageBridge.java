package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.CommonYaml;
import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.core.config.LanguageMerger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Fabric LanguageBridge. Loads language.yml via LanguageMerger. */
public class FabricLanguageBridge implements LanguageBridge {
    private static final String LANGUAGE_RESOURCE = "language.yml";

    private final FabricAdapter adapter;
    private final File languageFile;
    private CommonYaml langConfig;

    public FabricLanguageBridge(FabricAdapter adapter) {
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

    @Override
    public String applyColors(String message) {
        if (message == null) return "";
        return adapter.applyColors(message);
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
