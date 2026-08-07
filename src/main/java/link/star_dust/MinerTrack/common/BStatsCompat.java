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
 * no Bukkit dependency — and by default submits with {@code platform = "bukkit"}
 * so that the Fabric / (Neo)Forge installs are counted in the <b>same bStats
 * Bukkit statistics area</b> as the Bukkit builds (service id {@value #BSTATS_SERVICE_ID}).
 *
 * <p>The service id and the platform are fixed: service id {@value #BSTATS_SERVICE_ID}
 * submitted to the {@code "bukkit"} platform bucket (bStats routes submissions by the
 * platform in the URL path). {@code debug=true} in
 * {@code <dataFolder>/bStats/config.properties} logs the exact payload and the server
 * response for troubleshooting.
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

    /** The MinerTrack bStats service id (Bukkit platform project). */
    public static final int BSTATS_SERVICE_ID = 33200;

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
        /** Lowercase bStats platform tag appended to the reported plugin version, e.g. "fabric". */
        String platformTag();
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
            boolean debug = parseBool(cfg, "debug", false);

            // Fixed submission target: the MinerTrack Bukkit project (service id
            // BSTATS_SERVICE_ID) into the bStats "bukkit" platform bucket. bStats
            // routes submissions by the platform in the URL path
            // (https://bStats.org/api/v2/data/bukkit), so the id must be a Bukkit
            // project — that is how the Fabric/(Neo)Forge installs are counted in
            // the same stats area as the Bukkit builds.
            String platform = "bukkit";
            int serviceId = BSTATS_SERVICE_ID;

            // Log submission failures by default so "no data" is never silent.
            boolean logErrors = parseBool(cfg, "logFailedRequests", true);
            boolean logSentData = parseBool(cfg, "logSentData", debug);
            boolean logResponseStatusText = parseBool(cfg, "logResponseStatusText", debug);

            String software = safe(data.serverSoftware());
            String version = safe(data.serverVersion());
            String platformTag = safe(data.platformTag());

            if (!enabled) {
                adapter.info("[bStats] Metrics are disabled (see " + configFile(adapter.getDataFolder()) + ").");
            } else {
                adapter.info("[bStats] Metrics initialised: serviceId=" + serviceId
                    + ", platform=" + platform
                    + ", serverUUID=" + serverUuid
                    + ", endpoint=" + submitUrl(platform)
                    + ", config=" + configFile(adapter.getDataFolder()));
                if (debug) {
                    adapter.info("[bStats] Payload that will be submitted:\n"
                        + buildPayload(serverUuid, serviceId, data, software, version, platformTag, adapter));
                }
            }

            base = new MetricsBase(
                platform,
                serverUuid,
                serviceId,
                enabled,
                builder -> appendPlatformData(builder, data, software, version),
                builder -> appendServiceData(builder, adapter, platformTag),
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
        if (!software.isEmpty()) appendString(builder, "bukkitName", software);
        if (!version.isEmpty()) {
            appendString(builder, "bukkitVersion",
                software.isEmpty() ? version : software + " (MC: " + version + ")");
        }
        appendString(builder, "javaVersion", System.getProperty("java.version"));
        appendString(builder, "osName", System.getProperty("os.name"));
        appendString(builder, "osArch", System.getProperty("os.arch"));
        appendString(builder, "osVersion", System.getProperty("os.version"));
        try { builder.appendField("coreCount", Runtime.getRuntime().availableProcessors()); }
        catch (Throwable ignored) {}
    }

    /** Append a string field, skipping null/empty values (JsonObjectBuilder rejects null). */
    private static void appendString(JsonObjectBuilder builder, String key, String value) {
        if (value == null || value.isEmpty()) return;
        try { builder.appendField(key, value); } catch (Throwable ignored) {}
    }

    /** Append the service payload, guarding the plugin-version read. The reported
     *  plugin version carries the mod-platform tag (e.g. "2.1.0.0+neoforge") so the
     *  Bukkit bStats area can distinguish installs by platform. */
    private static void appendServiceData(JsonObjectBuilder builder, PluginAdapter adapter, String platformTag) {
        String pv = null;
        try { pv = adapter.getVersion(); } catch (Throwable ignored) {}
        if (pv != null && !pv.isEmpty()) {
            if (platformTag != null && !platformTag.isEmpty() && !pv.endsWith("+" + platformTag)) {
                pv = pv + "+" + platformTag;
            }
            try { builder.appendField("pluginVersion", pv); } catch (Throwable ignored) {}
        }
    }

    /** Null-coalesce for the data provider strings. */
    private static String safe(String s) { return s == null ? "" : s; }

    /** The exact bStats v2 submission URL for a platform. */
    private static String submitUrl(String platform) {
        return "https://bStats.org/api/v2/data/" + platform;
    }

    /** Build the exact JSON payload MetricsBase will submit (debug dry-run, never sent). */
    private static String buildPayload(String serverUuid, int serviceId, Data data,
                                       String software, String version, String platformTag, PluginAdapter adapter) {
        try {
            JsonObjectBuilder base = new JsonObjectBuilder();
            appendPlatformData(base, data, software, version);
            JsonObjectBuilder service = new JsonObjectBuilder();
            service.appendField("id", serviceId);
            service.appendField("customCharts", new JsonObjectBuilder.JsonObject[0]);
            appendServiceData(service, adapter, platformTag);
            base.appendField("service", service.build());
            base.appendField("serverUUID", serverUuid);
            base.appendField("metricsVersion", MetricsBase.METRICS_VERSION);
            return base.build().toString();
        } catch (Throwable t) {
            return "{" + t.getClass().getSimpleName() + ": " + t.getMessage() + "}";
        }
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
        boolean changed = false;
        if (!props.containsKey("enabled")) { props.setProperty("enabled", "true"); changed = true; }
        if (!props.containsKey("serverUuid")) { props.setProperty("serverUuid", UUID.randomUUID().toString()); changed = true; }
        if (!props.containsKey("debug")) { props.setProperty("debug", "false"); changed = true; }
        if (!props.containsKey("logFailedRequests")) { props.setProperty("logFailedRequests", "true"); changed = true; }
        if (!props.containsKey("logSentData")) { props.setProperty("logSentData", "false"); changed = true; }
        if (!props.containsKey("logResponseStatusText")) { props.setProperty("logResponseStatusText", "false"); changed = true; }
        if (changed) {
            File dir = file.getParentFile();
            try {
                if ((dir == null || dir.exists() || dir.mkdirs()) && (file.createNewFile() || file.exists())) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        props.store(out,
                            "bStats (https://bStats.org) collects anonymous usage statistics for MinerTrack.\n"
                            + "It is recommended to keep bStats enabled; set enabled=false to opt out.\n"
                            + "debug     = true logs the exact JSON payload + the server response.\n"
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
