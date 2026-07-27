/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 *
 *  Copyright (c) At87668 (Author87668) <https://github.com/At87668>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
