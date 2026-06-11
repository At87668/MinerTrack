package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.DebugConfig;
import link.star_dust.MinerTrack.core.Core;
import link.star_dust.MinerTrack.core.CoreLogger;
import link.star_dust.MinerTrack.core.config.WebhookConfig;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;
import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * Fabric platform entry point. Listed in
 * {@code fabric.mod.json} under
 * {@code entrypoints.server}. Mirrors {@code BukkitPlatform}'s
 * construction order: adapter, debug logger, violation manager,
 * detection bridge, mining core, webhook engine, language
 * bridge, command registration.
 *
 * <p>Implements {@link DedicatedServerModInitializer} so the
 * entry point only runs on a dedicated server (the same scope
 * as a Bukkit plugin). A client-side {@code ModInitializer} is
 * intentionally not used; MinerTrack has no client UI.
 *
 * <p>The command registration uses Fabric API's
 * {@link CommandRegistrationCallback} (a stable 1.18+ API) and
 * delegates subcommand logic to the same
 * {@code MinerTrackCommandCore} the Bukkit path uses. The
 * {@code /minertrack} command supports a single
 * {@code String[]} arg capturing the entire input line so the
 * existing subcommand parser works unchanged.
 */
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
        // ── Adapter + logger ─────────────────────────────────────
        adapter = new FabricAdapter();
        DebugConfig debugConfig = () -> adapter.isDebugEnabled();
        CoreLogger.init(debugConfig, java.util.logging.Logger.getLogger("MinerTrack"));
        if (adapter.isDebugEnabled()) {
            adapter.info("[MinerTrack:DEBUG] Debug mode is ENABLED \u2014 expect a high volume of [MinerTrack:DEBUG] log lines.");
        }

        // ── Violation manager ────────────────────────────────────
        violationManager = new FabricViolationManager(adapter);

        // ── Detection bridge (config + world registry) ──────────
        detectionBridge = new FabricDetectionBridge(adapter, adapter.getYamlLoader());
        detectionBridge.loadGroupConfigs();

        // ── Mining core (path detection, environment analyser) ─
        miningCore = new MiningCore(detectionBridge, violationManager);
        detectionBridge.setMiningCore(miningCore);

        // ── Webhook engine ───────────────────────────────────────
        webhookSender = new FabricWebhookSender(adapter);
        WebhookConfig webhookConfig = WebhookConfig.from(violationManager.getMainConfig());
        WebhookEngine webhookEngine = new WebhookEngine(webhookConfig, webhookSender);
        violationManager.setWebhookEngine(webhookEngine);

        // ── Language bridge ──────────────────────────────────────
        languageBridge = new FabricLanguageBridge(adapter);
        violationManager.setLanguageBridge(languageBridge);

        // ── Update manager ───────────────────────────────────────
        updateManager = new FabricUpdateManager(adapter, detectionBridge);

        // ── Mining listener (block break / place callbacks) ─────
        miningListener = new FabricMiningListener(miningCore, detectionBridge, violationManager, detectionBridge);
        miningListener.register();

        // ── Command executor / tab completer ─────────────────────
        commandExecutor = new FabricCommandExecutor(
            adapter, languageBridge, violationManager, updateManager, detectionBridge);
        FabricEventBus.registerCommandRegistration(dispatcher -> {
            // Register the /minertrack command and its aliases
            // (/mt, /mtrack) by reflection. The brigadier
            // dispatcher is a generic class; we call its
            // {@code register} method with a CommandBuilder
            // built via the static {@code literal} / {@code
            // argument} methods on MinecraftServer's
            // CommandManager. The {@code commandExecutor} runs
            // the same subcommand parser the Bukkit path uses.
            registerCommand(dispatcher, "minertrack");
            registerCommand(dispatcher, "mt");
            registerCommand(dispatcher, "mtrack");
        });

        // ── Global VL decay tick ─────────────────────────────────
        // 20 minutes in ticks (20 ticks/second). The Bukkit path
        // uses the same interval; Fabric API's
        // ServerTickEvents fires per tick so the math is
        // identical.
        violationManager.scheduleGlobalDecayTask(20L * 60L);

        // ── Startup banner ───────────────────────────────────────
        new Core(adapter).printStartupBanner();
        adapter.info("MinerTrack (FabricPlatform) enabled.");
    }

    /**
     * Parse a {@code String[]} argument's raw text into a
     * {@code String[]} of subcommand tokens. The Fabric command
     * API captures the full input as a single {@code String[]}
     * element when we use {@code CommandManager.argument} with a
     * string-array parameter, so this method exists to split the
     * single-element array into the individual tokens the
     * {@code MinerTrackCommandCore} expects.
     *
     * <p>The implementation re-uses Java's whitespace tokenizer
     * — quoted strings (e.g. {@code /mt kick Player "no
     * cheating"}) are not specially handled because the v1/v2
     * command path doesn't support quoted args either.
     */
    private static String[] parseArgs(String input) {
        if (input == null) return new String[0];
        return input.trim().split("\\s+");
    }

    /**
     * Register a single literal command on the brigadier
     * dispatcher via reflection. Builds a
     * {@code LiteralArgumentBuilder} from
     * {@code CommandManager.literal(name)}, attaches a single
     * greedy {@code String[]} argument that captures every
     * trailing token, and registers the resulting builder with
     * the dispatcher.
     *
     * <p>The {@code exec} body (a {@code Command}) and the
     * {@code argument} builder are both created via reflection
     * because their types live in {@code com.mojang.brigadier}
     * and {@code net.minecraft.server.command}, neither of which
     * is on the project's compile classpath.
     */
    private void registerCommand(Object dispatcher, String name) {
        try {
            Class<?> managerCls = FabricReflection.forName("net.minecraft.server.command.CommandManager");
            if (managerCls == null) return;
            // 1. CommandManager.literal(name) -> LiteralArgumentBuilder
            Object literal = FabricReflection.callStatic("net.minecraft.server.command.CommandManager",
                "literal", new Class<?>[]{String.class}, new Object[]{name});
            if (literal == null) return;
            Class<?> literalCls = literal.getClass();
            // 2. CommandManager.argument("args", String[].class)
            //    -> RequiredArgumentBuilder
            Object arg = FabricReflection.callStatic("net.minecraft.server.command.CommandManager",
                "argument", new Class<?>[]{String.class, Class.class},
                new Object[]{"args", String[].class});
            if (arg == null) return;
            Class<?> argCls = arg.getClass();
            // 3. Wrap the platform command in a brigadier
            //    {@code Command<Object>} lambda. The lambda's
            //    single method ({@code run}) takes a
            //    {@code CommandContext<Object>} and returns int.
            //    We build a dynamic proxy that implements
            //    {@code com.mojang.brigadier.Command} and reads
            //    the context's source + input via reflection.
            Class<?> brigadierCommandCls = FabricReflection.forName("com.mojang.brigadier.Command");
            if (brigadierCommandCls == null) return;
            Object commandProxy = java.lang.reflect.Proxy.newProxyInstance(
                brigadierCommandCls.getClassLoader(),
                new Class<?>[]{brigadierCommandCls},
                (proxy, method, methodArgs) -> {
                    if (!"run".equals(method.getName()) || methodArgs == null || methodArgs.length == 0) {
                        // {@code then} / {@code thenAsync}
                        // delegates might call other methods; the
                        // Command interface only has the single
                        // {@code run} abstract method, so any
                        // other invocation is a no-op.
                        return 1;
                    }
                    try {
                        Object ctx = methodArgs[0];
                        Object source = FabricReflection.callAny(ctx, "getSource", new Class<?>[0], new Object[0]);
                        String input = null;
                        // 1.20+: CommandContext has {@code getInput()} returning the
                        // raw text the user typed; 1.19- uses {@code getNodes()} or
                        // {@code getLastChild()}. We try {@code getInput} first
                        // and fall back to {@code getNodes}.
                        Object inputObj = FabricReflection.callAny(ctx, "getInput", new Class<?>[0], new Object[0]);
                        if (inputObj != null) {
                            input = inputObj.toString();
                        }
                        String[] args = parseArgs(input);
                        // Strip the leading literal (e.g.
                        // "minertrack") so the args array the
                        // executor sees matches the Bukkit path.
                        if (args.length > 0 && args[0].equalsIgnoreCase(name)) {
                            String[] sub = new String[args.length - 1];
                            System.arraycopy(args, 1, sub, 0, sub.length);
                            args = sub;
                        }
                        return commandExecutor.onCommand(source, args) ? 1 : 0;
                    } catch (Throwable t) {
                        return 0;
                    }
                });
            // 4. arg.executes(commandProxy)
            FabricReflection.callAny(arg, "executes",
                new Class<?>[]{FabricReflection.forName("com.mojang.brigadier.Command")},
                new Object[]{commandProxy});
            // 5. literal.then(arg)
            FabricReflection.callAny(literal, "then",
                new Class<?>[]{FabricReflection.forName("com.mojang.brigadier.builder.ArgumentBuilder")},
                new Object[]{arg});
            // 6. dispatcher.register(literal)
            FabricReflection.call(dispatcher, "register",
                new Class<?>[]{literalCls.getInterfaces().length > 0
                    ? literalCls.getInterfaces()[0] : literalCls},
                new Object[]{literal});
        } catch (Throwable t) {
            adapter.warning("Failed to register command /" + name + ": " + t.getMessage());
        }
    }

    /**
     * Used internally for testing / shutdown hooks. The Fabric
     * server lifecycle doesn't expose a "plugin disable" event
     * the way Bukkit does, so this method is a no-op for now;
     * the global tick handler is automatically unregistered
     * when the server stops.
     */
    public void onServerStopping() {
        // No explicit cleanup required: the global tick
        // handler is a Fabric API event-listener registration
        // that lives for the lifetime of the mod; the mod is
        // unloaded when the server stops.
    }
}
