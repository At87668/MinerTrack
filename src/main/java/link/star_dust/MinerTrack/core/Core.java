package link.star_dust.MinerTrack.core;

import link.star_dust.MinerTrack.common.PluginAdapter;

/**
 * Core module: platform-independent core entry.
 */
public class Core {
    private final PluginAdapter adapter;

    public Core(PluginAdapter adapter) {
        this.adapter = adapter;
        adapter.info("Core initialized for version " + adapter.getVersion());
    }

    // Example core method that could host platform-independent logic
    public void start() {
        adapter.info("Core start called");
    }
}
