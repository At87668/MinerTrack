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

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import link.star_dust.MinerTrack.common.DebugConfig;
import link.star_dust.MinerTrack.core.Core;
import link.star_dust.MinerTrack.core.CoreLogger;
import link.star_dust.MinerTrack.core.config.WebhookConfig;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;
import link.star_dust.MinerTrack.fabric.FabricReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge platform initialiser. Registered via NeoForge event bus for
 * ServerStartingEvent.
 */
public class NeoForgePlatform {
    private NeoForgeAdapter adapter;
    private NeoForgeDetectionBridge detectionBridge;
    private NeoForgeViolationManager violationManager;
    private MiningCore miningCore;
    private NeoForgeLanguageBridge languageBridge;
    private NeoForgeUpdateManager updateManager;
    private NeoForgeWebhookSender webhookSender;
    private NeoForgeCommandExecutor commandExecutor;
    private NeoForgeMiningListener miningListener;

    public void onServerStarting(Object event) {
        adapter = new NeoForgeAdapter();

        violationManager = new NeoForgeViolationManager(adapter);
        detectionBridge = new NeoForgeDetectionBridge(adapter, adapter.getYamlLoader());
        detectionBridge.loadGroupConfigs();
        adapter.clearDebugCache();

        DebugConfig debugConfig = () -> adapter.isDebugEnabled();
        CoreLogger.init(debugConfig, java.util.logging.Logger.getLogger("MinerTrack"));
        if (adapter.isDebugEnabled()) {
            adapter.info("[MinerTrack:DEBUG] Debug mode enabled.");
            NeoForgeReflection.setDebugReflection(true);
        }

        miningCore = new MiningCore(detectionBridge, violationManager);
        detectionBridge.setMiningCore(miningCore);

        webhookSender = new NeoForgeWebhookSender(adapter);
        WebhookConfig webhookConfig = WebhookConfig.from(violationManager.getMainConfig());
        WebhookEngine webhookEngine = new WebhookEngine(webhookConfig, webhookSender);
        violationManager.setWebhookEngine(webhookEngine);

        languageBridge = new NeoForgeLanguageBridge(adapter);
        violationManager.setLanguageBridge(languageBridge);

        updateManager = new NeoForgeUpdateManager(adapter, detectionBridge);
        miningListener = new NeoForgeMiningListener(miningCore, detectionBridge, violationManager, detectionBridge);
        miningListener.register();

        commandExecutor = new NeoForgeCommandExecutor(adapter, languageBridge, violationManager, updateManager, detectionBridge);

        registerNeoForgeCommands();
        registerServerStopping();

        violationManager.scheduleGlobalDecayTask(20L * 60L * 20L);
        new Core(adapter).printStartupBanner();
        adapter.info("MinerTrack (NeoForge) enabled.");
    }

    private void registerNeoForgeCommands() {
        NeoForgeReflection.registerEventListener(
            NeoForgeReflection.getMainEventBus(),
            NeoForgeReflection.neoClass("net.neoforged.neoforge.event.RegisterCommandsEvent"),
            rawEvent -> {
                try {
                    Object dispatcher = FabricReflection.callAny(rawEvent, "getDispatcher",
                        FabricReflection.NO_PARAMS, FabricReflection.NO_ARGS);
                    if (dispatcher instanceof CommandDispatcher) {
                        @SuppressWarnings("unchecked")
                        CommandDispatcher<Object> d = (CommandDispatcher<Object>) dispatcher;
                        registerCommand(d, "minertrack");
                        registerCommand(d, "mt");
                        registerCommand(d, "mtrack");
                    }
                } catch (Throwable t) {
                    adapter.warning("Failed to register NeoForge commands: " + t.getMessage());
                }
            });
    }

    private void registerServerStopping() {
        NeoForgeReflection.registerEventListener(
            NeoForgeReflection.getMainEventBus(),
            NeoForgeReflection.neoClass("net.neoforged.neoforge.event.server.ServerStoppingEvent"),
            rawEvent -> onServerStopping());
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
        RequiredArgumentBuilder arg = RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString());

        arg.executes((Command) ctx -> {
            String greedy = StringArgumentType.getString(ctx, "args");
            return commandExecutor.onCommand(ctx.getSource(), parseArgs(greedy)) ? 1 : 0;
        });

        arg.suggests((SuggestionProvider) (ctx, builder) -> {
            String greedy = builder.getInput();
            int spaceIdx = greedy.indexOf(' ');
            if (spaceIdx < 0) {
                List<String> completions = commandExecutor.onTabComplete(ctx.getSource(), new String[0]);
                if (completions != null) completions.forEach(builder::suggest);
                return builder.buildFuture();
            }
            String argsStr = greedy.substring(spaceIdx + 1);
            String[] fullArgs = parseArgs(argsStr);
            List<String> completions = commandExecutor.onTabComplete(ctx.getSource(), fullArgs);
            if (completions != null && fullArgs.length >= 1) {
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < fullArgs.length - 1; i++) {
                    if (i > 0) prefix.append(' ');
                    prefix.append(fullArgs[i]);
                }
                if (prefix.length() > 0) prefix.append(' ');
                final String preserved = prefix.toString();
                for (String c : completions) builder.suggest(preserved + c);
            } else if (completions != null) {
                completions.forEach(builder::suggest);
            }
            return builder.buildFuture();
        });

        literal.then(arg);
        literal.executes((Command) ctx -> commandExecutor.onCommand(ctx.getSource(), new String[0]) ? 1 : 0);
        dispatcher.register(literal);
    }

    public void onServerStopping() {
        adapter.info("MinerTrack (NeoForge) disabled.");
    }
}
