package link.star_dust.MinerTrack.core;

import link.star_dust.MinerTrack.common.PluginAdapter;

import java.util.Arrays;
import java.util.List;

/**
 * Core module: platform-independent core entry.
*/
public class Core {
    private static final List<String> STARTUP_BANNER = Arrays.asList(
        "&8----[&9&lMiner&c&lTrack &av%version% &8]-----------",
        "&9&lMiner&c&lTrack &4&oAnti-XRay &aEnabled!",
        "",
        "&7Authors: Author87668",
        "&7Original Author: Author87668",
        "&7Contributors: Author87668, Zhang12334, Thomas, xiaoyueyoqwq",
        "",
        "&a&oThanks for your use!",
        "&8-----------------------------------------"
    );

    private final PluginAdapter adapter;

    public Core(PluginAdapter adapter) {
        this.adapter = adapter;
        adapter.info("Core initialized for version " + adapter.getVersion());
    }

    // Example core method that could host platform-independent logic
    public void start() {
        adapter.info("Core start called");
    }

    public void printStartupBanner() {
        String version = adapter.getVersion();
        for (String line : STARTUP_BANNER) {
            if (line == null) continue;
            String colored = adapter.applyColors(line);
            String rendered = colored.replace("%version%", version);
            adapter.sendConsoleMessage(rendered);
        }
    }
}
