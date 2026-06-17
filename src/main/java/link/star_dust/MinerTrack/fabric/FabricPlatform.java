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
 * <p>
 * Implements {@link DedicatedServerModInitializer} so the
 * entry point only runs on a dedicated server (the same scope
 * as a Bukkit plugin). A client-side {@code ModInitializer} is
 * intentionally not used; MinerTrack has no client UI.
 *
 * <p>
 * The command registration uses Fabric API's
 * {@code CommandRegistrationCallback} (stable 1.18+) and
 * delegates subcommand logic to the same
 * {@code MinerTrackCommandCore} the Bukkit path uses. The
 * {@code /minertrack} command uses
 * {@code StringArgumentType.greedyString()} to capture the
 * entire input line as a single string, then splits it into
 * tokens so the existing subcommand parser works unchanged.
 *
 * <p>
 * Version compatibility: 1.18–26.x. The Fabric API
 * command event evolved from v1 to v2 between 1.18 and 1.19;
 * this class tries both package names. The Minecraft
 * {@code Text} API also changed from {@code LiteralText} (1.18)
 * to {@code Text.literal()} (1.19.3+); callers that create
 * messages should handle both. All version-sensitive types
 * are accessed through reflection, so no compile-time
 * dependency exists on any specific Minecraft version.
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
    private FabricDetectionBridge fabricBridge;
    /**
     * Guard against double registration (CommandRegistrationCallback +
     * ServerStarted fallback).
     */
    private volatile boolean commandsRegistered = false;

    @Override
    public void onInitializeServer() {
        // ── Adapter + logger ─────────────────────────────────────
        adapter = new FabricAdapter();
        DebugConfig debugConfig = () -> adapter.isDebugEnabled();
        CoreLogger.init(debugConfig, java.util.logging.Logger.getLogger("MinerTrack"));
        FabricEventBus.setDebug(adapter.isDebugEnabled());
        if (adapter.isDebugEnabled()) {
            adapter.info(
                    "[MinerTrack:DEBUG] Debug mode is ENABLED \u2014 expect a high volume of [MinerTrack:DEBUG] log lines.");
        }

        // ── Violation manager ────────────────────────────────────
        violationManager = new FabricViolationManager(adapter);

        // ── Detection bridge (config + world registry) ──────────
        detectionBridge = new FabricDetectionBridge(adapter, adapter.getYamlLoader());
        detectionBridge.loadGroupConfigs();

        fabricBridge = detectionBridge;

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
        miningListener = new FabricMiningListener(miningCore, detectionBridge, violationManager, fabricBridge);
        miningListener.register();

        // ── Command executor / tab completer ─────────────────────
        commandExecutor = new FabricCommandExecutor(
                adapter, languageBridge, violationManager, updateManager, detectionBridge);
        FabricEventBus.registerCommandRegistration(dispatcher -> {
            // Debug: log dispatcher type before registering commands.
            try {
                adapter.info("[MinerTrack:DEBUG] CommandRegistrationCallback invoked. dispatcher="
                        + (dispatcher == null ? "null" : dispatcher.getClass().getName()));
            } catch (Throwable ignored) {
            }
            if (commandsRegistered)
                return; // already registered — skip duplicate
            try {
                registerCommand(dispatcher, "minertrack");
                registerCommand(dispatcher, "mt");
                registerCommand(dispatcher, "mtrack");
                commandsRegistered = true;
            } catch (Throwable t) {
                adapter.warning(
                        "[MinerTrack:DEBUG] Exception while registering commands in callback: " + t.getMessage());
            }
        });

        // Also attempt an explicit registration on server start
        FabricEventBus.registerServerStarted(server -> {
            if (commandsRegistered)
                return; // already registered — skip
            try {
                adapter.info("[MinerTrack:DEBUG] ServerStarted callback invoked. server="
                        + (server == null ? "null" : server.getClass().getName()));
                Object cmdManager = FabricReflection.callAny(server, "getCommandManager", new Class<?>[0],
                        new Object[0]);
                if (cmdManager == null)
                    return;
                Object dispatcher = null;
                try {
                    dispatcher = FabricReflection.callAny(cmdManager, "getDispatcher", new Class<?>[0], new Object[0]);
                } catch (Throwable ignored) {
                }
                if (dispatcher == null)
                    dispatcher = cmdManager;
                if (dispatcher == null)
                    return;
                adapter.info("[MinerTrack:DEBUG] Explicit registration dispatcher="
                        + (dispatcher == null ? "null" : dispatcher.getClass().getName()));
                registerCommand(dispatcher, "minertrack");
                registerCommand(dispatcher, "mt");
                registerCommand(dispatcher, "mtrack");
                commandsRegistered = true;
            } catch (Throwable t) {
                adapter.warning("Explicit command registration failed: " + t.getMessage());
            }
        });

        // ── Global VL decay tick ─────────────────────────────────
        violationManager.scheduleGlobalDecayTask(20L * 60L * 20L);

        // ── Startup banner ───────────────────────────────────────
        new Core(adapter).printStartupBanner();
        adapter.info("MinerTrack (FabricPlatform) enabled.");
    }

    /**
     * Parse a {@code String[]} argument's raw text into a
     * {@code String[]} of subcommand tokens.
     */
    static String[] parseArgs(String input) {
        if (input == null)
            return new String[0];
        String[] raw = input.trim().split("\\s+");
        if (raw.length == 0)
            return new String[0];
        int offset = 0;
        if (raw[0].startsWith("/") || "minertrack".equals(raw[0])
                || "mt".equals(raw[0]) || "mtrack".equals(raw[0])) {
            offset = 1;
        }
        if (offset >= raw.length)
            return new String[0];
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (int i = offset; i < raw.length; i++) {
            if (!raw[i].isEmpty())
                tokens.add(raw[i]);
        }
        return tokens.toArray(new String[0]);
    }

    // ── Tab-completion helpers ─────────────────────────────────────

    private static Object buildEmptySuggestions() {
        Class<?> suggestionsCls = FabricReflection.forName(
                "com.mojang.brigadier.suggestion.Suggestions");
        if (suggestionsCls == null)
            return null;

        return FabricReflection.callStatic(suggestionsCls.getName(),
                "create",
                new Class<?>[] { String.class, java.util.Collection.class },
                new Object[] { "", new java.util.ArrayList<>() });
    }

    /**
     * Wraps a Suggestions object in a CompletableFuture, as required by Brigadier's
     * SuggestionProvider.
     */
    private static Object wrapInCompletableFuture(Object value) {
        try {
            Class<?> completableFutureCls = FabricReflection.forName("java.util.concurrent.CompletableFuture");
            if (completableFutureCls == null)
                return value;
            return FabricReflection.callStatic(completableFutureCls.getName(),
                    "completedFuture",
                    new Class<?>[] { Object.class },
                    new Object[] { value });
        } catch (Throwable t) {
            return value;
        }
    }

    private static Object buildSuggestions(Object builder,
            java.util.List<String> completions,
            String input) {
        try {
            Class<?> suggestionCls = FabricReflection.forName(
                    "com.mojang.brigadier.suggestion.Suggestion");
            Class<?> rangeCls = FabricReflection.forName(
                    "com.mojang.brigadier.context.StringRange");
            Class<?> suggestionsCls = FabricReflection.forName(
                    "com.mojang.brigadier.suggestion.Suggestions");
            if (suggestionCls == null || rangeCls == null
                    || suggestionsCls == null) {
                return buildEmptySuggestions();
            }
            int start = 0;
            try {
                Object startObj = FabricReflection.callAny(builder,
                        "getStart", new Class<?>[0], new Object[0]);
                if (startObj instanceof Number)
                    start = ((Number) startObj).intValue();
            } catch (Throwable ignored) {
            }
            Object range = FabricReflection.callStatic(rangeCls.getName(),
                    "between",
                    new Class<?>[] { int.class, int.class },
                    new Object[] { start, input.length() });
            java.util.List<Object> suggestions = new java.util.ArrayList<>();
            for (String c : completions) {
                Object sug = suggestionCls.getDeclaredConstructor(
                        rangeCls, String.class).newInstance(range, c);
                suggestions.add(sug);
            }
            Object result = FabricReflection.callStatic(suggestionsCls.getName(),
                    "create",
                    new Class<?>[] { String.class, java.util.Collection.class },
                    new Object[] { input, suggestions });
            return result;
        } catch (Throwable t) {
            return buildEmptySuggestions();
        }
    }

    private void registerCommand(Object dispatcher, String name) {
        try {
            adapter.info("[MinerTrack:DEBUG] Attempting to register command /" + name + " using dispatcher: "
                    + (dispatcher == null ? "null" : dispatcher.getClass().getName()));
            Class<?> managerCls = FabricReflection.forName("net.minecraft.server.command.CommandManager");
            if (managerCls == null)
                return;

            // 1. CommandManager.literal(name) -> LiteralArgumentBuilder
            Object literal = FabricReflection.callStatic("net.minecraft.server.command.CommandManager",
                    "literal", new Class<?>[] { String.class }, new Object[] { name });
            if (literal == null)
                return;
            Class<?> literalCls = literal.getClass();

            // 2. StringArgumentType.greedyString() -> ArgumentType<String>
            Object greedy = FabricReflection.callStatic("com.mojang.brigadier.arguments.StringArgumentType",
                    "greedyString", new Class<?>[0], new Object[0]);
            if (greedy == null) {
                adapter.warning("Failed to build greedyString argument type; /" + name + " will not be registered.");
                return;
            }

            // 3. CommandManager.argument("args", greedy) -> RequiredArgumentBuilder
            Class<?> argumentTypeCls = FabricReflection.forName("com.mojang.brigadier.arguments.ArgumentType");
            if (argumentTypeCls == null) {
                adapter.warning("Failed to find ArgumentType class; /" + name + " will not be registered.");
                return;
            }
            Object arg = FabricReflection.callStatic("net.minecraft.server.command.CommandManager",
                    "argument", new Class<?>[] { String.class, argumentTypeCls },
                    new Object[] { "args", greedy });
            if (arg == null) {
                adapter.warning("Failed to build argument builder for /" + name + "; command will not be registered.");
                return;
            }

            // 4. Wrap the platform command in a brigadier Command<Object> lambda.
            Class<?> brigadierCommandCls = FabricReflection.forName("com.mojang.brigadier.Command");
            if (brigadierCommandCls == null)
                return;
            Object commandProxy = java.lang.reflect.Proxy.newProxyInstance(
                    brigadierCommandCls.getClassLoader(),
                    new Class<?>[] { brigadierCommandCls },
                    (proxy, method, methodArgs) -> {
                        if ("hashCode".equals(method.getName()))
                            return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName()))
                            return proxy == methodArgs[0];
                        if ("toString".equals(method.getName()))
                            return "MinerTrackCommandProxy";

                        if (!"run".equals(method.getName()) || methodArgs == null || methodArgs.length == 0) {
                            return 1;
                        }
                        try {
                            Object ctx = methodArgs[0];
                            Object source = FabricReflection.callAny(ctx, "getSource", new Class<?>[0], new Object[0]);
                            String greedyString = "";
                            try {
                                Object greedyVal = FabricReflection.callAny(ctx, "getArgument",
                                        new Class<?>[] { String.class, Class.class },
                                        new Object[] { "args", String.class });
                                if (greedyVal != null)
                                    greedyString = greedyVal.toString();
                            } catch (Throwable t) {
                                // No "args" provided
                            }
                            String[] args = parseArgs(greedyString);
                            return commandExecutor.onCommand(source, args) ? 1 : 0;
                        } catch (Throwable t) {
                            return 0;
                        }
                    });

            // 5. arg.executes(commandProxy)
            FabricReflection.callAny(arg, "executes",
                    new Class<?>[] { brigadierCommandCls },
                    new Object[] { commandProxy });

            // 5b. Tab-completion: arg.suggests(suggestionProvider)
            Class<?> suggestionProviderCls = FabricReflection.forName(
                    "com.mojang.brigadier.suggestion.SuggestionProvider");
            if (suggestionProviderCls != null) {
                Object suggestionProxy = java.lang.reflect.Proxy.newProxyInstance(
                        suggestionProviderCls.getClassLoader(),
                        new Class<?>[] { suggestionProviderCls },
                        (sproxy, sMethod, sArgs) -> {
                            if ("hashCode".equals(sMethod.getName()))
                                return System.identityHashCode(sproxy);
                            if ("equals".equals(sMethod.getName()))
                                return sproxy == sArgs[0];
                            if ("toString".equals(sMethod.getName()))
                                return "MinerTrackSuggestionProxy";

                            if (!"getSuggestions".equals(sMethod.getName()) || sArgs == null || sArgs.length < 2) {
                                return wrapInCompletableFuture(buildEmptySuggestions());
                            }
                            try {
                                Object sCtx = sArgs[0];
                                Object sBuilder = sArgs[1];
                                String input = "";
                                try {
                                    Object getInput = FabricReflection.callAny(sBuilder, "getInput",
                                            new Class<?>[0], new Object[0]);
                                    if (getInput != null)
                                        input = getInput.toString();
                                } catch (Throwable ignored) {
                                }
                                String[] args = parseArgs(input);
                                Object source = FabricReflection.callAny(sCtx,
                                        "getSource", new Class<?>[0], new Object[0]);
                                java.util.List<String> completions = commandExecutor.onTabComplete(source, args);
                                if (completions == null || completions.isEmpty()) {
                                    return wrapInCompletableFuture(buildEmptySuggestions());
                                }
                                Object suggestions = buildSuggestions(sBuilder, completions, input);
                                return wrapInCompletableFuture(suggestions);
                            } catch (Throwable t) {
                                return wrapInCompletableFuture(buildEmptySuggestions());
                            }
                        });
                FabricReflection.callAny(arg, "suggests",
                        new Class<?>[] { suggestionProviderCls },
                        new Object[] { suggestionProxy });
            }

            // 6. literal.then(arg)
            FabricReflection.callAny(literal, "then",
                    new Class<?>[] { FabricReflection.forName("com.mojang.brigadier.builder.ArgumentBuilder") },
                    new Object[] { arg });

            // 7. literal.executes(commandProxy)
            FabricReflection.callAny(literal, "executes",
                    new Class<?>[] { brigadierCommandCls },
                    new Object[] { commandProxy });

            // 8. dispatcher.register(literal)
            FabricReflection.call(dispatcher, "register",
                    new Class<?>[] { literalCls },
                    new Object[] { literal });
            adapter.info("[MinerTrack:DEBUG] Successfully registered command /" + name);
        } catch (Throwable t) {
            adapter.warning("Failed to register command /" + name + ": " + t.getMessage());
            if (adapter.isDebugEnabled()) {
                adapter.info("[MinerTrack:DEBUG] Exception during registerCommand for /" + name + ": " + t.toString());
            }
        }
    }

    public void onServerStopping() {
        // No explicit cleanup required
    }
}