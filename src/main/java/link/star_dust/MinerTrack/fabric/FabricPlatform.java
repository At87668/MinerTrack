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

package link.star_dust.MinerTrack.fabric;

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
import link.star_dust.MinerTrack.core.config.LanguageMerger;
import link.star_dust.MinerTrack.core.config.WebhookConfig;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

public class FabricPlatform implements DedicatedServerModInitializer {
    private FabricAdapter adapter;
    private FabricDetectionBridge detectionBridge;
    private FabricViolationManager violationManager;
    private MiningCore miningCore;
    private FabricLanguageBridge languageBridge;
    private FabricUpdateManager updateManager;
    private FabricWebhookSender webhookSender;
    private FabricCommandExecutor commandExecutor;
    private FabricMiningListener miningListener;
    private FastStatsCompat fastStats;

    @Override
    public void onInitializeServer() {
        adapter = new FabricAdapter();

        // Load config BEFORE initialising the debug logger so that
        // the `debug: true` key in config.yml is already on disk
        // when CoreLogger checks it. FabricAdapter.isDebugEnabled()
        // reads the file directly; if the file doesn't exist yet
        // the default (false) is used and debug lines are silently
        // dropped for the entire server session.
        violationManager = new FabricViolationManager(adapter);
        detectionBridge = new FabricDetectionBridge(adapter, adapter.getYamlLoader());
        detectionBridge.loadGroupConfigs();
        adapter.clearDebugCache(); // force re-read config.yml

        DebugConfig debugConfig = () -> adapter.isDebugEnabled();
        CoreLogger.init(debugConfig, java.util.logging.Logger.getLogger("MinerTrack"));
        if (adapter.isDebugEnabled()) {
            adapter.info("[MinerTrack:DEBUG] Debug mode enabled.");
            FabricReflection.setDebugReflection(true);
        }

        miningCore = new MiningCore(detectionBridge, violationManager);
        detectionBridge.setMiningCore(miningCore);

        webhookSender = new FabricWebhookSender(adapter);
        WebhookConfig webhookConfig = WebhookConfig.from(violationManager.getMainConfig());
        WebhookEngine webhookEngine = new WebhookEngine(webhookConfig, webhookSender);
        violationManager.setWebhookEngine(webhookEngine);

        // Extract the bundled Translations/*.yml language files (de/fr/ru/zh_cn)
        // into the config folder on first run. Mirrors the legacy v1.x
        // behaviour; existing files are never overwritten.
        LanguageMerger.extractTranslations(adapter);
        languageBridge = new FabricLanguageBridge(adapter);
        violationManager.setLanguageBridge(languageBridge);

        updateManager = new FabricUpdateManager(adapter, detectionBridge);
        miningListener = new FabricMiningListener(miningCore, detectionBridge, violationManager, detectionBridge);
        miningListener.register();
        commandExecutor = new FabricCommandExecutor(adapter, languageBridge, violationManager, updateManager, detectionBridge);

        FabricEventBus.registerCommandRegistration(dispatcherObj -> {
            CommandDispatcher<?> dispatcher = (CommandDispatcher<?>) dispatcherObj;
            registerCommand(dispatcher, "minertrack");
            registerCommand(dispatcher, "mt");
            registerCommand(dispatcher, "mtrack");
        });

        FabricEventBus.registerServerStopping(this::onServerStopping);

        violationManager.scheduleGlobalDecayTask(20L * 60L * 20L);

        // FastStats (faststats.dev) mod-platform telemetry — replaces the removed
        // bStats bridge. Non-fatal.
        try {
            fastStats = FastStatsCompat.create(adapter, new FabricFastStatsData(), "fabric", FastStatsCompat.FASTSTATS_TOKEN);
            fastStats.ready();
        } catch (Throwable t) {
            adapter.warning("Failed to initialise FastStats metrics: " + t.getMessage());
        }

        new Core(adapter).printStartupBanner();
        adapter.info("MinerTrack (Fabric) enabled.");
    }

    /**
     * Split a command input string into arguments, preserving the
     * platform-neutral contract expected by MinerTrackCommandCore.
     */
    static String[] parseArgs(String input) {
        if (input == null || input.trim().isEmpty()) return new String[0];
        List<String> tokens = new ArrayList<>();
        for (String s : input.trim().split("\\s+")) {
            if (!s.isEmpty()) tokens.add(s);
        }
        return tokens.toArray(new String[0]);
    }

    /**
     * Register a Brigadier command with the given dispatcher under the
     * given name (e.g. "minertrack", "mt", "mtrack").
     *
     * <p>Uses a required greedy-string argument so every token after the
     * command name is captured verbatim, then parsed into a {@code String[]}
     * for the platform-agnostic command core. A no-arg execution path is
     * also registered so {@code /minertrack} (with zero trailing args) routes
     * to the help screen.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerCommand(CommandDispatcher dispatcher, String name) {
        LiteralArgumentBuilder literal = LiteralArgumentBuilder.literal(name);

        // Greedy-string branch: captures everything after the command name
        RequiredArgumentBuilder arg = RequiredArgumentBuilder
            .argument("args", StringArgumentType.greedyString());

        arg.executes((Command) ctx -> {
            String greedy = StringArgumentType.getString(ctx, "args");
            Object source = ctx.getSource();
            return commandExecutor.onCommand(source, parseArgs(greedy)) ? 1 : 0;
        });

        arg.suggests((SuggestionProvider) (ctx, builder) -> {
            // The greedy-string argument captures ALL tokens after the
            // command name. Example: /mt check s → greedy = "check s".
            // If we suggest "steve", Brigadier replaces the *entire* greedy
            // string with "steve", losing "check". Fix: the completion
            // handler should only replace the LAST token (the one being
            // completed), leaving the earlier tokens unchanged.
            String greedy = builder.getInput();
            // Strip the leading command name and space (e.g. "/mt " → "check s")
            int spaceIdx = greedy.indexOf(' ');
            if (spaceIdx < 0) {
                // Only the command name, no args → suggest subcommands
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
                // Rebuild the prefix that must be preserved: all tokens
                // before the last one, plus a trailing space.
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

        // No-arg branch: /<name> with no arguments → show help
        literal.executes((Command) ctx -> {
            Object source = ctx.getSource();
            return commandExecutor.onCommand(source, new String[0]) ? 1 : 0;
        });

        dispatcher.register(literal);
    }

    public void onServerStopping(Object server) {
        if (fastStats != null) fastStats.shutdown();
        adapter.info("MinerTrack (Fabric) disabled.");
    }

    /** FastStats telemetry provider for the Fabric runtime (defensive reads). */
    private static final class FabricFastStatsData implements FastStatsCompat.Data {
        @Override
        public int playerAmount() {
            try {
                Object server = FabricReflection.getServer();
                if (server == null) return 0;
                Object pm = FabricReflection.callMigrated(server, "getPlayerList", "getPlayerManager",
                    FabricReflectionConstants.NO_ARGS, FabricReflectionConstants.NO_VALS);
                if (pm == null) return 0;
                // MC 26.1+: getPlayers(); 1.18-1.21: getPlayerList()
                Object list = FabricReflection.callMigrated(pm, "getPlayers", "getPlayerList",
                    FabricReflectionConstants.NO_ARGS, FabricReflectionConstants.NO_VALS);
                if (list instanceof java.util.Collection) return ((java.util.Collection<?>) list).size();
            } catch (Throwable ignored) {}
            return 0;
        }

        @Override
        public int onlineMode() {
            try {
                Object server = FabricReflection.getServer();
                if (server == null) return -1;
                Object v = FabricReflection.call(server, "isOnlineMode",
                    FabricReflectionConstants.NO_ARGS, FabricReflectionConstants.NO_VALS);
                if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
            } catch (Throwable ignored) {}
            return -1;
        }

        @Override
        public String serverSoftware() { return "Fabric"; }

        @Override
        public String platformTag() { return "fabric"; }

        @Override
        public String serverVersion() {
            try {
                // fabric-loader 0.14.x has no FabricLoader.getGameVersion(); read the
                // "minecraft" mod's declared version from its metadata instead.
                return FabricLoader.getInstance().getModContainer("minecraft")
                    .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
                    .orElse(null);
            } catch (Throwable ignored) { return null; }
        }
    }
}