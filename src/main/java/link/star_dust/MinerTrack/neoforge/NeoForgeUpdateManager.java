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

package link.star_dust.MinerTrack.neoforge;

import link.star_dust.MinerTrack.common.CommandBridge;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.LanguageBridge;
import link.star_dust.MinerTrack.common.UpdateConfigSource;
import link.star_dust.MinerTrack.core.update.UpdateManagerCore;

import java.util.List;

/** NeoForge update-check shim. Mirrors ForgeUpdateManager. */
public class NeoForgeUpdateManager {
    private final NeoForgeAdapter adapter;
    private final UpdateManagerCore core;

    public NeoForgeUpdateManager(NeoForgeAdapter adapter, DetectionBridge detectionBridge) {
        this.adapter = adapter;
        UpdateConfigSource source = new DetectionBridgeConfigSource(detectionBridge, adapter);
        this.core = new UpdateManagerCore(source, adapter.getVersion(), "neoforge");
    }

    public boolean isHasNewerVersion() { return core.isHasNewerVersion(); }
    public boolean shouldNotifyOnJoin() { return core.shouldNotifyOnJoin(); }
    public void refresh() { core.refresh(); }

    public void checkForUpdates(CommandBridge sender) {
        new Thread(() -> {
            UpdateManagerCore.CheckResult result = core.checkForUpdates();
            String rendered = core.renderResult(stubLang(), result);
            if (rendered == null || rendered.isEmpty()) return;
            Object server = NeoForgeReflection.getServer();
            if (server != null) {
                try {
                    NeoForgeReflection.call(server, "execute",
                        new Class<?>[]{Runnable.class},
                        new Object[]{(Runnable) () -> sender.sendMessage(rendered)});
                    return;
                } catch (Throwable t) {}
            }
            sender.sendMessage(rendered);
        }, "MinerTrack-UpdateCheck").start();
    }

    private LanguageBridge stubLang() {
        NeoForgeAdapter a = this.adapter;
        return new LanguageBridge() {
            @Override public String getPrefixedMessage(String key) { return "[" + key + "]"; }
            @Override public String getLogFormat() { return "%year%-%month%-%day% %hour%:%minute%:%second% | %player% | %vl% | %world% | %pos_x% %pos_y% %pos_z%"; }
            @Override public String applyColors(String message) { return a.applyColors(message); }
            @Override public String getPrefix() { return a.applyColors("&8[&9&lMiner&c&lTrack&8]&r "); }
            @Override public List<String> getHelpMessages() { return java.util.Collections.emptyList(); }
            @Override public String getMessage(String path) { return null; }
            @Override public String getColoredMessage(String path) { return ""; }
            @Override public boolean isKickBroadcastEnabled() { return true; }
            @Override public String getKickFormat() { return "&cYou have been kicked for &e%reason%"; }
        };
    }

    private static final class DetectionBridgeConfigSource implements UpdateConfigSource {
        private final DetectionBridge bridge;
        private final NeoForgeAdapter adapter;

        DetectionBridgeConfigSource(DetectionBridge bridge, NeoForgeAdapter adapter) {
            this.bridge = bridge; this.adapter = adapter;
        }

        @Override public boolean isUpdateCheckEnabled() {
            try { if (bridge != null) return bridge.getConfigBoolean("check_update", true); }
            catch (Exception e) { adapter.info("Could not read check_update setting, defaulting to true: " + e.getMessage()); }
            return true;
        }
        @Override public String getUpdateCheckChannel() {
            try { if (bridge != null) { Object ch = bridge.getConfig("check_update_channel"); if (ch != null) return ch.toString(); } }
            catch (Exception e) { adapter.info("Could not read check_update_channel setting, defaulting to stable: " + e.getMessage()); }
            return "stable";
        }
        @Override public void log(String message) { adapter.info(message); }
    }
}
