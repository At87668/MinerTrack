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

import com.google.gson.JsonObject;
import dev.faststats.Metrics;
import dev.faststats.SimpleContext;
import dev.faststats.SimpleMetrics;
import dev.faststats.config.SimpleConfig;
import dev.faststats.internal.Logger;
import dev.faststats.internal.PlatformLoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Platform-agnostic FastStats (faststats.dev) bridge for the mod platforms.
 *
 * <p>faststats.dev agreed to MinerTrack collecting mod-platform data, replacing the
 * (temporarily removed) bStats mod-platform telemetry. The official FastStats SDK
 * has no Forge module, and its Fabric/NeoForge modules require the platform APIs
 * on the compile classpath (they ship as jar-in-jar mods) — both incompatible with
 * MinerTrack's "compile against Bukkit only, reflect the rest" constraint. This
 * class therefore drives the SDK's platform-agnostic {@code core} / {@code config}
 * modules directly: it extends {@link SimpleContext}, schedules submissions on its
 * own daemon thread, and reports the standard fields from a {@link Data} provider
 * that each mod platform implements via its existing reflection helpers.
 *
 * <p>Note: {@link SimpleContext}, {@link SimpleMetrics} and {@link SimpleConfig} are
 * marked {@code @ApiStatus.Internal} by FastStats — the official platform modules
 * build on them the same way, so this is an accepted, version-pinned dependency
 * (pinned to the {@code 0.29.4} artifact in {@code build.gradle}).
 *
 * <p>The submission endpoint is {@code https://metrics.faststats.dev/v1/collect}
 * (JDK {@code java.net.http}, gzip, {@code Authorization: Bearer <token>}), the
 * initial delay is 30 s and the period is 30 min. All telemetry failure is
 * non-fatal: construction is wrapped by the callers so a transport error never
 * aborts server startup.
 */
public final class FastStatsCompat extends SimpleContext {

    /**
     * The FastStats project token (32 lowercase alphanumeric characters, see
     * {@code dev.faststats.Token.PATTERN}). <b>Placeholder — replace this with the
     * real MinerTrack faststats.dev project token.</b>
     */
    public static final String FASTSTATS_TOKEN = "b8167a220f6afef9638071218a12e115";

    /** Live telemetry supplier implemented by each mod platform. */
    public interface Data {
        /** Current online player count; negative values are coerced to 0. */
        int playerAmount();
        /** 1 = online mode, 0 = offline mode, -1 = unknown (field omitted). */
        int onlineMode();
        /** Server software label, e.g. "NeoForge". */
        String serverSoftware();
        /** Minecraft version, e.g. "1.21.1"; may be null/empty (field omitted). */
        String serverVersion();
        /** Lowercase platform tag appended to the plugin version, e.g. "neoforge". */
        String platformTag();
    }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "minertrack-faststats");
        t.setDaemon(true);
        return t;
    });
    private final Set<Future<?>> tasks = new CopyOnWriteArraySet<>();
    private final PluginAdapter adapter;
    private final Data data;

    private FastStatsCompat(Factory factory, PluginAdapter adapter, Data data,
                            String platform, String token, PlatformLoggerFactory loggerFactory) {
        super(factory, loggerFactory, SimpleConfig.read(configPath(adapter), loggerFactory), platform, token);
        this.adapter = adapter;
        this.data = data;
        initializeServices(factory);
    }

    /** Convenience factory wiring the default metrics service. */
    public static FastStatsCompat create(PluginAdapter adapter, Data data, String platform, String token) {
        return new Factory(adapter, data, platform, token)
            .metrics(Metrics.Factory::create)
            .create();
    }

    @Override
    protected boolean preSubmissionStart() {
        // First run: prints the opt-out notice and defers submission until the next
        // restart (FastStats compliance — users must be able to opt out first).
        return ((SimpleConfig) getConfig()).preSubmissionStart(this);
    }

    @Override
    public String getProjectName() {
        return "minertrack";
    }

    @Override
    protected Metrics.Factory metricsFactory() {
        return new SimpleMetrics.Factory(this) {
            @Override
            public Metrics create() {
                return new ModMetricsImpl(this, data, adapter);
            }
        };
    }

    @Override
    protected void scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        tasks.add(executor.scheduleAtFixedRate(task, initialDelay, period, unit));
    }

    @Override
    public void shutdown() {
        tasks.forEach(future -> future.cancel(false));
        super.shutdown();
        executor.shutdownNow();
    }

    private static PlatformLoggerFactory loggerFactory(PluginAdapter adapter) {
        return new PlatformLoggerFactory((level, throwable, message) -> {
            if (level == Logger.LogLevel.ERROR || level == Logger.LogLevel.WARN) {
                String suffix = throwable != null && throwable.getMessage() != null
                    ? " — " + throwable.getMessage() : "";
                adapter.warning("[FastStats] " + message + suffix);
            } else {
                adapter.info("[FastStats] " + message);
            }
        });
    }

    private static Path configPath(PluginAdapter adapter) {
        return new File(adapter.getDataFolder(), "faststats" + File.separator + "config.properties").toPath();
    }

    /** Standard mod-platform metrics payload (plus FastStats' own internal data). */
    private static final class ModMetricsImpl extends SimpleMetrics {
        private final Data data;
        private final PluginAdapter adapter;

        ModMetricsImpl(Factory factory, Data data, PluginAdapter adapter) {
            super(factory);
            this.data = data;
            this.adapter = adapter;
        }

        @Override
        protected void appendDefaultData(JsonObject metrics) {
            try {
                metrics.addProperty("server_type", data.serverSoftware());
            } catch (Throwable ignored) {}
            try {
                String v = data.serverVersion();
                if (v != null && !v.isEmpty()) metrics.addProperty("platform_version", v);
            } catch (Throwable ignored) {}
            try {
                int om = data.onlineMode();
                if (om >= 0) metrics.addProperty("online_mode", om == 1);
            } catch (Throwable ignored) {}
            try {
                int pc = data.playerAmount();
                if (pc >= 0) metrics.addProperty("player_count", pc);
            } catch (Throwable ignored) {}
            try {
                String pv = adapter.getVersion();
                String tag = data.platformTag();
                if (pv != null && !pv.isEmpty()) {
                    if (tag != null && !tag.isEmpty() && !pv.endsWith("+" + tag)) pv = pv + "+" + tag;
                    metrics.addProperty("plugin_version", pv);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Builder for {@link FastStatsCompat}. */
    public static final class Factory extends SimpleContext.Factory<FastStatsCompat, Factory> {
        private final PluginAdapter adapter;
        private final Data data;
        private final String platform;
        private final String token;

        public Factory(PluginAdapter adapter, Data data, String platform, String token) {
            this.adapter = adapter;
            this.data = data;
            this.platform = platform;
            this.token = token;
        }

        @Override
        public FastStatsCompat create() {
            return new FastStatsCompat(this, adapter, data, platform, token, loggerFactory(adapter));
        }
    }
}
