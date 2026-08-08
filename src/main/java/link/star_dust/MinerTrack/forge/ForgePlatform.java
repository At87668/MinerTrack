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

package link.star_dust.MinerTrack.forge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import link.star_dust.MinerTrack.common.FastStatsCompat;
import link.star_dust.MinerTrack.common.DebugConfig;
import link.star_dust.MinerTrack.core.Core;
import link.star_dust.MinerTrack.core.CoreLogger;
import link.star_dust.MinerTrack.core.config.WebhookConfig;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge platform initialiser. Registered as a mod event handler
 * for ServerStartingEvent on the Forge event bus.
 */
public class ForgePlatform {
    private ForgeAdapter adapter;
    private ForgeDetectionBridge detectionBridge;
    private ForgeViolationManager violationManager;
    private MiningCore miningCore;
    private ForgeLanguageBridge languageBridge;
    private ForgeUpdateManager updateManager;
    private ForgeWebhookSender webhookSender;
    private ForgeCommandExecutor commandExecutor;
    private ForgeMiningListener miningListener;
    private FastStatsCompat fastStats;

    public void onServerStarting(Object event) {
        // Cache the MinecraftServer from the ServerStartingEvent so that
        // ForgeReflection.getServer() returns a non-null value. Many permission
        // and player-lookup paths depend on it.
        if (event != null) {
            Object server = ForgeReflection.callAny(event, "getServer",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (server != null) ForgeReflection.setCachedServer(server);
        }

        adapter = new ForgeAdapter();

        violationManager = new ForgeViolationManager(adapter);
        detectionBridge = new ForgeDetectionBridge(adapter, adapter.getYamlLoader());
        detectionBridge.loadGroupConfigs();
        adapter.clearDebugCache();

        DebugConfig debugConfig = () -> adapter.isDebugEnabled();
        CoreLogger.init(debugConfig, java.util.logging.Logger.getLogger("MinerTrack"));
        if (adapter.isDebugEnabled()) {
            adapter.info("[MinerTrack:DEBUG] Debug mode enabled.");
            ForgeReflection.setDebugReflection(true);
        }

        miningCore = new MiningCore(detectionBridge, violationManager);
        detectionBridge.setMiningCore(miningCore);

        webhookSender = new ForgeWebhookSender(adapter);
        WebhookConfig webhookConfig = WebhookConfig.from(violationManager.getMainConfig());
        WebhookEngine webhookEngine = new WebhookEngine(webhookConfig, webhookSender);
        violationManager.setWebhookEngine(webhookEngine);

        languageBridge = new ForgeLanguageBridge(adapter);
        violationManager.setLanguageBridge(languageBridge);

        updateManager = new ForgeUpdateManager(adapter, detectionBridge);
        miningListener = new ForgeMiningListener(miningCore, detectionBridge, violationManager, detectionBridge);
        miningListener.register();

        // Register all already-loaded worlds so block lookups resolve to a real
        // Level. Without this, dimensionToWorld stays empty and getBlockType
        // returns AIR for every block, making the natural-environment scan see
        // the whole 7x7x7 volume as air (air=337/343).
        registerLoadedWorlds();

        commandExecutor = new ForgeCommandExecutor(adapter, languageBridge, violationManager, updateManager, detectionBridge);

        // Register server stopping handler
        registerServerStopping();

        violationManager.scheduleGlobalDecayTask(20L * 60L * 20L);

        // FastStats (faststats.dev) mod-platform telemetry — replaces the removed
        // bStats bridge. Non-fatal.
        try {
            fastStats = FastStatsCompat.create(adapter, new ForgeFastStatsData(), "forge", FastStatsCompat.FASTSTATS_TOKEN);
            fastStats.ready();
        } catch (Throwable t) {
            adapter.warning("Failed to initialise FastStats metrics: " + t.getMessage());
        }

        new Core(adapter).printStartupBanner();
        adapter.info("MinerTrack (Forge) enabled.");
    }

    private void registerForgeCommands() {
        // RegisterCommandsEvent fires during MinecraftServer construction, which
        // happens BEFORE ServerStartingEvent. So this listener must be registered
        // early (from the ForgeMod constructor), not from onServerStarting.
        // The commandExecutor is initialized later in onServerStarting; the
        // listener reads it lazily so it is ready by the time the event fires.
        ForgeReflection.registerEventListener(
            ForgeReflection.getMainEventBus(),
            ForgeReflection.forgeClass("net.minecraftforge.event.RegisterCommandsEvent"),
            rawEvent -> {
                try {
                    Object dispatcher = ForgeReflection.callAny(rawEvent, "getDispatcher",
                        ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                    if (dispatcher instanceof CommandDispatcher) {
                        @SuppressWarnings("unchecked")
                        CommandDispatcher<Object> d = (CommandDispatcher<Object>) dispatcher;
                        registerCommand(d, "minertrack");
                        registerCommand(d, "mt");
                        registerCommand(d, "mtrack");
                    }
                } catch (Throwable t) {
                    adapter.warning("Failed to register Forge commands: " + t.getMessage());
                }
            });
    }

    /**
     * Register the RegisterCommandsEvent listener early (from the ForgeMod
     * constructor). Must be called before the MinecraftServer is constructed,
     * because RegisterCommandsEvent fires during server construction — before
     * ServerStartingEvent.
     */
    public void registerCommandsEarly() {
        registerForgeCommands();
    }

    private void registerServerStopping() {
        ForgeReflection.registerEventListener(
            ForgeReflection.getMainEventBus(),
            ForgeReflection.forgeClass("net.minecraftforge.event.server.ServerStoppingEvent"),
            rawEvent -> onServerStopping());
    }

    /**
     * Enumerate all currently-loaded worlds and register them with the
     * detection bridge so {@code getBlockType} can resolve block lookups.
     * Called from {@code onServerStarting} after the bridge is constructed.
     */
    private void registerLoadedWorlds() {
        try {
            Object server = ForgeReflection.getServer();
            if (server == null) return;
            Object worlds = ForgeReflection.callAny(server, "getAllLevels",
                ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            if (worlds == null || !(worlds instanceof Iterable)) {
                worlds = ForgeReflection.callAny(server, "getWorlds",
                    ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
            }
            if (worlds instanceof Iterable) {
                for (Object w : (Iterable<?>) worlds) {
                    detectionBridge.registerWorld(w);
                }
            }
        } catch (Throwable t) {
            adapter.warning("Failed to enumerate startup worlds: " + t.getMessage());
        }
    }

    static String[] parseArgs(String input) {
        if (input == null || input.trim().isEmpty()) return new String[0];
        List<String> tokens = new ArrayList<>();
        for (String s : input.trim().split("\\s+")) {
            if (!s.isEmpty()) tokens.add(s);
        }
        return tokens.toArray(new String[0]);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerCommand(CommandDispatcher dispatcher, String name) {
        LiteralArgumentBuilder literal = LiteralArgumentBuilder.literal(name);

        RequiredArgumentBuilder arg = RequiredArgumentBuilder
            .argument("args", StringArgumentType.greedyString());

        arg.executes((Command) ctx -> {
            String greedy = StringArgumentType.getString(ctx, "args");
            Object source = ctx.getSource();
            if (commandExecutor == null) return 0;
            return commandExecutor.onCommand(source, parseArgs(greedy)) ? 1 : 0;
        });

        arg.suggests((SuggestionProvider) (ctx, builder) -> {
            String greedy = builder.getInput();
            int spaceIdx = greedy.indexOf(' ');
            if (spaceIdx < 0) {
                List<String> completions = commandExecutor.onTabComplete(
                    ctx.getSource(), new String[0]);
                if (completions != null) completions.forEach(builder::suggest);
                return builder.buildFuture();
            }
            String argsStr = greedy.substring(spaceIdx + 1);
            String[] fullArgs = parseArgs(argsStr);
            List<String> completions = commandExecutor.onTabComplete(
                ctx.getSource(), fullArgs);

            if (completions != null && fullArgs.length >= 1) {
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < fullArgs.length - 1; i++) {
                    if (i > 0) prefix.append(' ');
                    prefix.append(fullArgs[i]);
                }
                if (prefix.length() > 0) prefix.append(' ');
                final String preserved = prefix.toString();
                for (String c : completions) {
                    builder.suggest(preserved + c);
                }
            } else if (completions != null) {
                completions.forEach(builder::suggest);
            }
            return builder.buildFuture();
        });

        literal.then(arg);

        literal.executes((Command) ctx -> {
            Object source = ctx.getSource();
            if (commandExecutor == null) return 0;
            return commandExecutor.onCommand(source, new String[0]) ? 1 : 0;
        });

        dispatcher.register(literal);
    }

    public void onServerStopping() {
        if (fastStats != null) fastStats.shutdown();
        adapter.info("MinerTrack (Forge) disabled.");
    }

    /** FastStats telemetry provider for the Forge runtime (defensive reads). */
    private static final class ForgeFastStatsData implements FastStatsCompat.Data {
        @Override
        public int playerAmount() {
            try {
                Object server = ForgeReflection.getServer();
                if (server == null) return 0;
                Object pm = ForgeReflection.call(server, "getPlayerList", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                if (pm == null) return 0;
                Object list = ForgeReflection.call(pm, "getPlayers", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                if (list instanceof java.util.Collection) return ((java.util.Collection<?>) list).size();
            } catch (Throwable ignored) {}
            return 0;
        }

        @Override
        public int onlineMode() {
            try {
                Object server = ForgeReflection.getServer();
                if (server == null) return -1;
                Object v = ForgeReflection.call(server, "isOnlineMode", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
            } catch (Throwable ignored) {}
            return -1;
        }

        @Override
        public String serverSoftware() { return "Forge"; }

        @Override
        public String platformTag() { return "forge"; }

        @Override
        public String serverVersion() {
            try {
                // Stable FML API — avoids an SRG method-name lookup on MinecraftServer.
                Object info = ForgeReflection.callStatic("net.minecraftforge.fml.loading.FMLLoader",
                    "versionInfo", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                if (info == null) return null;
                Object v = ForgeReflection.call(info, "mcVersion", ForgeReflection.NO_PARAMS, ForgeReflection.NO_ARGS);
                return v == null ? null : v.toString();
            } catch (Throwable ignored) { return null; }
        }
    }
}
