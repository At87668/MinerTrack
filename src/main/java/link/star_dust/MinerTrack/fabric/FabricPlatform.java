package link.star_dust.MinerTrack.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import link.star_dust.MinerTrack.common.DebugConfig;
import link.star_dust.MinerTrack.core.Core;
import link.star_dust.MinerTrack.core.CoreLogger;
import link.star_dust.MinerTrack.core.config.WebhookConfig;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;
import net.fabricmc.api.DedicatedServerModInitializer;

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

    @Override
    public void onInitializeServer() {
        adapter = new FabricAdapter();
        DebugConfig debugConfig = () -> adapter.isDebugEnabled();
        CoreLogger.init(debugConfig, java.util.logging.Logger.getLogger("MinerTrack"));
        if (adapter.isDebugEnabled()) {
            adapter.info("[MinerTrack:DEBUG] Debug mode enabled.");
        }

        violationManager = new FabricViolationManager(adapter);
        detectionBridge = new FabricDetectionBridge(adapter, adapter.getYamlLoader());
        detectionBridge.loadGroupConfigs();
        miningCore = new MiningCore(detectionBridge, violationManager);
        detectionBridge.setMiningCore(miningCore);

        webhookSender = new FabricWebhookSender(adapter);
        WebhookConfig webhookConfig = WebhookConfig.from(violationManager.getMainConfig());
        WebhookEngine webhookEngine = new WebhookEngine(webhookConfig, webhookSender);
        violationManager.setWebhookEngine(webhookEngine);

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
        new Core(adapter).printStartupBanner();
        adapter.info("MinerTrack (Fabric) enabled.");
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

        arg.executes(ctx -> {
            String greedy = StringArgumentType.getString(ctx, "args");
            return commandExecutor.onCommand(ctx.getSource(), parseArgs(greedy)) ? 1 : 0;
        });
        arg.suggests((ctx, builder) -> {
            List<String> completions = commandExecutor.onTabComplete(ctx.getSource(), parseArgs(builder.getInput()));
            if (completions != null) completions.forEach(builder::suggest);
            return builder.buildFuture();
        });

        literal.then(arg);
        literal.executes(ctx -> commandExecutor.onCommand(ctx.getSource(), new String[0]) ? 1 : 0);
        dispatcher.register(literal);
    }

    public void onServerStopping(Object server) {
        adapter.info("MinerTrack (Fabric) disabled.");
    }
}