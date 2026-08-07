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

package link.star_dust.MinerTrack.common;

import org.bstats.MetricsBase;
import org.bstats.charts.CustomChart;
import org.bstats.json.JsonObjectBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

/**
 * Platform-agnostic bStats compatibility bridge for the non-Bukkit platforms.
 *
 * <p>The bStats Bukkit library can only be started from a {@code JavaPlugin}, so
 * on Fabric / Forge / NeoForge servers MinerTrack could never report telemetry.
 * This class reuses the bundled, relocated {@link MetricsBase} directly — it has
 * no Bukkit dependency — and submits with {@code platform = "bukkit"} so that the
 * Fabric / (Neo)Forge installs are counted in the <b>same bStats Bukkit
 * statistics area</b> as the Bukkit builds (service id {@value #BSTATS_SERVICE_ID}).
 *
 * <p>Each platform supplies a {@link Data} provider for the live telemetry fields
 * (player count, online mode, server software, MC version). The suppliers are
 * invoked on bStats' own scheduler thread, so implementations must be defensive
 * and never throw.
 *
 * <p>The opt-out / server-UUID state is kept in a self-contained
 * {@code <dataFolder>/bStats/config.properties} file (it is <b>not</b> the shared
 * {@code plugins/bStats/config.yml} the Bukkit server writes). Telemetry failure
 * is non-fatal: construction is wrapped so a transport error never aborts startup.
 */
public final class BStatsCompat {

    /** The MinerTrack bStats service id — the Bukkit project (v1, id 23790 on bStats.org). */
    public static final int BSTATS_SERVICE_ID = 23790;

    /** Live telemetry supplier implemented by each platform. */
    public interface Data {
        /** Current online player count; negative values are coerced to 0. */
        int playerAmount();
        /** 1 = online mode, 0 = offline mode, -1 = unknown (field omitted). */
        int onlineMode();
        /** Server software label, e.g. "Fabric", "Forge", "NeoForge". */
        String serverSoftware();
        /** Minecraft version, e.g. "1.20.1"; may be null/empty (field omitted). */
        String serverVersion();
    }

    private final MetricsBase metricsBase;
    private volatile boolean running = true;

    /**
     * @param adapter platform adapter (logging + data folder + plugin version)
     * @param data    live telemetry provider
     */
    public BStatsCompat(PluginAdapter adapter, Data data) {
        MetricsBase base = null;
        try {
            Properties cfg = loadOrCreateConfig(adapter.getDataFolder());
            boolean enabled = parseBool(cfg, "enabled", true);
            String serverUuid = cfg.getProperty("serverUuid", "");
            if (serverUuid.isEmpty()) serverUuid = UUID.randomUUID().toString();
            boolean logErrors = parseBool(cfg, "logFailedRequests", false);
            boolean logSentData = parseBool(cfg, "logSentData", false);
            boolean logResponseStatusText = parseBool(cfg, "logResponseStatusText", false);

            if (!enabled) {
                adapter.info("[bStats] Metrics are disabled (see " + configFile(adapter.getDataFolder()) + ").");
            }

            String software = data.serverSoftware();
            String version = data.serverVersion();

            base = new MetricsBase(
                "bukkit", // route into the bStats BUKKIT statistics area
                serverUuid,
                BSTATS_SERVICE_ID,
                enabled,
                builder -> appendPlatformData(builder, data, software, version),
                builder -> builder.appendField("pluginVersion", adapter.getVersion()),
                null, // collect + submit on bStats' own scheduler thread
                () -> running,
                (msg, err) -> adapter.warning("[bStats] " + msg + (err == null ? "" : ": " + err.getMessage())),
                adapter::info,
                logErrors,
                logSentData,
                logResponseStatusText
            );
        } catch (Throwable t) {
            // Telemetry must never take the server down.
            adapter.warning("[bStats] Failed to initialise metrics (non-fatal): " + t.getMessage());
        }
        this.metricsBase = base;
    }

    /** Add a custom chart to the next submissions (no-op if construction failed). */
    public void addCustomChart(CustomChart chart) {
        if (metricsBase != null) metricsBase.addCustomChart(chart);
    }

    /** Stop future submissions on server shutdown. */
    public void shutdown() {
        running = false;
    }

    /**
     * Append the fields the bStats Bukkit backend understands, mapping
     * platform values onto the Bukkit-shaped keys:
     * {@code playerAmount}, {@code onlineMode}, {@code bukkitName}
     * (server software) and {@code bukkitVersion} (formatted like the
     * standard {@code "git-Paper-377 (MC: 1.20.1)"} string so the backend
     * can extract the Minecraft version). Every read is guarded so a single
     * failing supplier never aborts the whole submission.
     */
    private static void appendPlatformData(JsonObjectBuilder builder, Data data,
                                           String software, String version) {
        try {
            int players = data.playerAmount();
            if (players < 0) players = 0;
            builder.appendField("playerAmount", players);
        } catch (Throwable ignored) {}
        try {
            int online = data.onlineMode();
            if (online >= 0) builder.appendField("onlineMode", online == 1 ? 1 : 0);
        } catch (Throwable ignored) {}
        if (software != null && !software.isEmpty()) builder.appendField("bukkitName", software);
        if (version != null && !version.isEmpty() && software != null && !software.isEmpty()) {
            builder.appendField("bukkitVersion", software + " (MC: " + version + ")");
        } else if (version != null && !version.isEmpty()) {
            builder.appendField("bukkitVersion", version);
        }
        builder.appendField("javaVersion", System.getProperty("java.version"));
        builder.appendField("osName", System.getProperty("os.name"));
        builder.appendField("osArch", System.getProperty("os.arch"));
        builder.appendField("osVersion", System.getProperty("os.version"));
        builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    /**
     * Load (or create with defaults) the self-contained bStats config.
     * Follows the same keys as the shared Bukkit {@code bStats/config.yml}
     * so operators recognise them, but stored as a simple properties file.
     */
    private static Properties loadOrCreateConfig(File dataFolder) {
        File file = configFile(dataFolder);
        Properties props = new Properties();
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
                // fall through to defaults
            }
        }
        boolean created = !file.exists();
        if (!props.containsKey("enabled")) props.setProperty("enabled", "true");
        if (!props.containsKey("serverUuid")) props.setProperty("serverUuid", UUID.randomUUID().toString());
        if (!props.containsKey("logFailedRequests")) props.setProperty("logFailedRequests", "false");
        if (!props.containsKey("logSentData")) props.setProperty("logSentData", "false");
        if (!props.containsKey("logResponseStatusText")) props.setProperty("logResponseStatusText", "false");
        if (created) {
            File dir = file.getParentFile();
            try {
                if ((dir == null || dir.exists() || dir.mkdirs()) && (file.createNewFile() || file.exists())) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        props.store(out,
                            "bStats (https://bStats.org) collects anonymous usage statistics for MinerTrack.\n"
                            + "It is recommended to keep bStats enabled; set enabled=false to opt out.\n"
                            + "This file is local to MinerTrack on this (non-Bukkit) platform.");
                    }
                }
            } catch (IOException ignored) {
                // non-fatal: continue with in-memory values
            }
        }
        return props;
    }

    private static File configFile(File dataFolder) {
        return new File(dataFolder, "bStats" + File.separator + "config.properties");
    }

    private static boolean parseBool(Properties props, String key, boolean def) {
        String v = props.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }
}
