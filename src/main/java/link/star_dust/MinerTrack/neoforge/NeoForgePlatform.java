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
import link.star_dust.MinerTrack.common.BStatsCompat;
import link.star_dust.MinerTrack.common.DebugConfig;
import link.star_dust.MinerTrack.core.Core;
import link.star_dust.MinerTrack.core.CoreLogger;
import link.star_dust.MinerTrack.core.config.WebhookConfig;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;

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
    private BStatsCompat bStatsCompat;

    public void onServerStarting(Object event) {
        // Cache the MinecraftServer from the ServerStartingEvent so that
        // NeoForgeReflection.getServer() returns a non-null value. Many
        // permission and player-lookup paths depend on it (e.g.
        // isPlayerOperator -> PlayerList.isOp, resolvePlayer, sendMessageToPlayer).
        if (event != null) {
            Object server = NeoForgeReflection.callAny(event, "getServer",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (server != null) NeoForgeReflection.setCachedServer(server);
        }

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

        // Register all already-loaded worlds so block lookups resolve to a real
        // Level. Without this, dimensionToWorld stays empty and getBlockType
        // returns AIR for every block, making the natural-environment scan see
        // the whole 7x7x7 volume as air.
        registerLoadedWorlds();

        commandExecutor = new NeoForgeCommandExecutor(adapter, languageBridge, violationManager, updateManager, detectionBridge);

        registerServerStopping();

        violationManager.scheduleGlobalDecayTask(20L * 60L * 20L);

        // bStats — NeoForge has no JavaPlugin, so the Bukkit bStats library cannot
        // start from this platform. Feed the bStats project through the
        // platform-agnostic bridge (serviceId/platform are configurable in
        // <dataFolder>/bStats/config.properties). Non-fatal.
        try {
            bStatsCompat = new BStatsCompat(adapter, new NeoForgeBStatsData());
        } catch (Throwable t) {
            adapter.warning("Failed to initialise bStats metrics: " + t.getMessage());
        }

        new Core(adapter).printStartupBanner();
        adapter.info("MinerTrack (NeoForge) enabled.");
    }

    /**
     * Register the RegisterCommandsEvent listener early (from the NeoForgeMod
     * constructor). Must be called before the MinecraftServer is constructed,
     * because RegisterCommandsEvent fires during server construction — before
     * ServerStartingEvent. Registering from onServerStarting is too late and
     * results in "/mt Unknown or incomplete command".
     */
    public void registerCommandsEarly() {
        NeoForgeReflection.registerEventListener(
            NeoForgeReflection.getMainEventBus(),
            NeoForgeReflection.neoClass("net.neoforged.neoforge.event.RegisterCommandsEvent"),
            rawEvent -> {
                try {
                    Object dispatcher = NeoForgeReflection.callAny(rawEvent, "getDispatcher",
                        NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                    if (dispatcher instanceof CommandDispatcher) {
                        @SuppressWarnings("unchecked")
                        CommandDispatcher<Object> d = (CommandDispatcher<Object>) dispatcher;
                        registerCommand(d, "minertrack");
                        registerCommand(d, "mt");
                        registerCommand(d, "mtrack");
                    }
                } catch (Throwable t) {
                    if (adapter != null) {
                        adapter.warning("Failed to register NeoForge commands: " + t.getMessage());
                    }
                }
            });
    }

    private void registerServerStopping() {
        NeoForgeReflection.registerEventListener(
            NeoForgeReflection.getMainEventBus(),
            NeoForgeReflection.neoClass("net.neoforged.neoforge.event.server.ServerStoppingEvent"),
            rawEvent -> onServerStopping());
    }

    /**
     * Enumerate all currently-loaded worlds and register them with the
     * detection bridge so {@code getBlockType} can resolve block lookups.
     * Called from {@code onServerStarting} after the bridge is constructed.
     */
    private void registerLoadedWorlds() {
        try {
            Object server = NeoForgeReflection.getServer();
            if (server == null) return;
            Object worlds = NeoForgeReflection.callAny(server, "getAllLevels",
                NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
            if (worlds == null || !(worlds instanceof Iterable)) {
                worlds = NeoForgeReflection.callAny(server, "getWorlds",
                    NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
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
        RequiredArgumentBuilder arg = RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString());

        arg.executes((Command) ctx -> {
            // commandExecutor is initialized on ServerStartingEvent; commands are
            // registered earlier (RegisterCommandsEvent) so guard against it.
            if (commandExecutor == null) return 0;
            String greedy = StringArgumentType.getString(ctx, "args");
            return commandExecutor.onCommand(ctx.getSource(), parseArgs(greedy)) ? 1 : 0;
        });

        arg.suggests((SuggestionProvider) (ctx, builder) -> {
            if (commandExecutor == null) return builder.buildFuture();
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
        literal.executes((Command) ctx -> commandExecutor == null ? 0 : (commandExecutor.onCommand(ctx.getSource(), new String[0]) ? 1 : 0));
        dispatcher.register(literal);
    }

    public void onServerStopping() {
        if (bStatsCompat != null) bStatsCompat.shutdown();
        adapter.info("MinerTrack (NeoForge) disabled.");
    }

    /** bStats telemetry provider for the NeoForge runtime (defensive reads). */
    private static final class NeoForgeBStatsData implements BStatsCompat.Data {
        @Override
        public int playerAmount() {
            try {
                Object server = NeoForgeReflection.getServer();
                if (server == null) return 0;
                Object pm = NeoForgeReflection.call(server, "getPlayerList", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                if (pm == null) return 0;
                Object list = NeoForgeReflection.call(pm, "getPlayers", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                if (list instanceof java.util.Collection) return ((java.util.Collection<?>) list).size();
            } catch (Throwable ignored) {}
            return 0;
        }

        @Override
        public int onlineMode() {
            try {
                Object server = NeoForgeReflection.getServer();
                if (server == null) return -1;
                Object v = NeoForgeReflection.call(server, "isOnlineMode", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
            } catch (Throwable ignored) {}
            return -1;
        }

        @Override
        public String serverSoftware() { return "NeoForge"; }

        @Override
        public String platformTag() { return "neoforge"; }

        @Override
        public String serverVersion() {
            try {
                // Stable FML API — avoids an SRG method-name lookup on MinecraftServer.
                Object info = NeoForgeReflection.callStatic("net.neoforged.fml.loading.FMLLoader",
                    "versionInfo", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                if (info == null) return null;
                Object v = NeoForgeReflection.call(info, "mcVersion", NeoForgeReflection.NO_PARAMS, NeoForgeReflection.NO_ARGS);
                return v == null ? null : v.toString();
            } catch (Throwable ignored) { return null; }
        }
    }
}
