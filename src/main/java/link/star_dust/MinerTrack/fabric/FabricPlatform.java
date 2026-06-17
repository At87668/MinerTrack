package link.star_dust.MinerTrack.fabric;

import link.star_dust.MinerTrack.common.DebugConfig;
import link.star_dust.MinerTrack.core.Core;
import link.star_dust.MinerTrack.core.CoreLogger;
import link.star_dust.MinerTrack.core.config.WebhookConfig;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import link.star_dust.MinerTrack.core.violation.WebhookEngine;
import net.fabricmc.api.DedicatedServerModInitializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // Use AtomicBoolean for thread-safe, atomic registration guard
    // to prevent race conditions between CommandRegistrationCallback and ServerStarted.
    private final AtomicBoolean commandsRegistered = new AtomicBoolean(false);

    @Override
    public void onInitializeServer() {
        // ── Adapter + logger ─────────────────────────────────────
        adapter = new FabricAdapter();
        DebugConfig debugConfig = () -> adapter.isDebugEnabled();
        CoreLogger.init(debugConfig, java.util.logging.Logger.getLogger("MinerTrack"));
        FabricEventBus.setDebug(adapter.isDebugEnabled());
        if (adapter.isDebugEnabled()) {
            adapter.info(
                    "[MinerTrack:DEBUG] Debug mode is ENABLED — expect a high volume of [MinerTrack:DEBUG] log lines.");
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

        // Removed redundant fabricBridge field; pass detectionBridge directly.
        // ── Mining listener (block break / place callbacks) ─────
        miningListener = new FabricMiningListener(miningCore, detectionBridge, violationManager, detectionBridge);
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
            // Atomic check-and-set prevents duplicate registration across threads.
            if (!commandsRegistered.compareAndSet(false, true)) {
                return;
            }
            try {
                registerCommand(dispatcher, "minertrack");
                registerCommand(dispatcher, "mt");
                registerCommand(dispatcher, "mtrack");
            } catch (Throwable t) {
                adapter.warning(
                        "[MinerTrack:DEBUG] Exception while registering commands in callback: " + t.getMessage());
            }
        });

        // Also attempt an explicit registration on server start as fallback
        FabricEventBus.registerServerStarted(server -> {
            // Atomic check-and-set prevents duplicate registration across threads.
            if (!commandsRegistered.compareAndSet(false, true)) {
                return;
            }
            try {
                adapter.info("[MinerTrack:DEBUG] ServerStarted callback invoked. server="
                        + (server == null ? "null" : server.getClass().getName()));
                Object cmdManager = FabricReflection.callAny(server, "getCommandManager", new Class<?>[0],
                        new Object[0]);
                if (cmdManager == null) return;

                Object dispatcher = null;
                try {
                    dispatcher = FabricReflection.callAny(cmdManager, "getDispatcher", new Class<?>[0], new Object[0]);
                } catch (Throwable ignored) {
                }
                if (dispatcher == null) dispatcher = cmdManager;
                if (dispatcher == null) return;

                adapter.info("[MinerTrack:DEBUG] Explicit registration dispatcher="
                        + (dispatcher == null ? "null" : dispatcher.getClass().getName()));
                registerCommand(dispatcher, "minertrack");
                registerCommand(dispatcher, "mt");
                registerCommand(dispatcher, "mtrack");
            } catch (Throwable t) {
                adapter.warning("Explicit command registration failed: " + t.getMessage());
            }
        });

        // Register server stopping callback for proper cleanup.
        FabricEventBus.registerServerStopping(this::onServerStopping);

        // ── Global VL decay tick ─────────────────────────────────
        violationManager.scheduleGlobalDecayTask(20L * 60L * 20L);

        // ── Startup banner ───────────────────────────────────────
        new Core(adapter).printStartupBanner();
        adapter.info("MinerTrack (FabricPlatform) enabled.");
    }

    /**
     * Parse a greedy string argument into subcommand tokens.
     * In Brigadier, greedyString() only captures arguments after the command label,
     * so no prefix/command-name stripping is needed (unlike Bukkit).
     */
    static String[] parseArgs(String input) {
        if (input == null || input.trim().isEmpty()) return new String[0];
        String[] raw = input.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String s : raw) {
            if (!s.isEmpty()) tokens.add(s);
        }
        return tokens.toArray(new String[0]);
    }

    // ── Tab-completion helpers ─────────────────────────────────────

    /**
     * Build an empty Suggestions object using the constructor (not a non-existent static method).
     * Wrapped in try-catch to fail safely instead of crashing the tab-completion thread.
     */
    private static Object buildEmptySuggestions() {
        try {
            Class<?> suggestionsCls = FabricReflection.forName("com.mojang.brigadier.suggestion.Suggestions");
            Class<?> rangeCls = FabricReflection.forName("com.mojang.brigadier.context.StringRange");
            if (suggestionsCls == null || rangeCls == null) return null;

            // Create an empty range at position 0
            Object range = FabricReflection.callStatic(rangeCls.getName(), "at",
                    new Class<?>[]{int.class}, new Object[]{0});
            if (range == null) {
                range = FabricReflection.callStatic(rangeCls.getName(), "between",
                        new Class<?>[]{int.class, int.class}, new Object[]{0, 0});
            }

            // Use constructor: new Suggestions(StringRange, List<Suggestion>)
            return suggestionsCls.getConstructor(rangeCls, List.class)
                    .newInstance(range, new ArrayList<>());
        } catch (Throwable t) {
            // Fail safely instead of propagating exception to brigadier
            return null;
        }
    }

    /**
     * Wraps a Suggestions object in a CompletableFuture, as required by Brigadier's
     * SuggestionProvider.
     */
    private static Object wrapInCompletableFuture(Object value) {
        try {
            Class<?> completableFutureCls = FabricReflection.forName("java.util.concurrent.CompletableFuture");
            if (completableFutureCls == null) return value;
            return FabricReflection.callStatic(completableFutureCls.getName(),
                    "completedFuture",
                    new Class<?>[]{Object.class},
                    new Object[]{value});
        } catch (Throwable t) {
            return value;
        }
    }

    /**
     * Build Suggestions with actual completions using the constructor.
     */
    private static Object buildSuggestions(Object builder, List<String> completions, String input) {
        try {
            Class<?> suggestionCls = FabricReflection.forName("com.mojang.brigadier.suggestion.Suggestion");
            Class<?> rangeCls = FabricReflection.forName("com.mojang.brigadier.context.StringRange");
            Class<?> suggestionsCls = FabricReflection.forName("com.mojang.brigadier.suggestion.Suggestions");
            if (suggestionCls == null || rangeCls == null || suggestionsCls == null) {
                return buildEmptySuggestions();
            }

            int start = 0;
            try {
                Object startObj = FabricReflection.callAny(builder, "getStart", new Class<?>[0], new Object[0]);
                if (startObj instanceof Number) start = ((Number) startObj).intValue();
            } catch (Throwable ignored) {
            }

            Object range = FabricReflection.callStatic(rangeCls.getName(), "between",
                    new Class<?>[]{int.class, int.class},
                    new Object[]{start, input.length()});

            List<Object> suggestions = new ArrayList<>();
            for (String c : completions) {
                Object sug = suggestionCls.getDeclaredConstructor(rangeCls, String.class)
                        .newInstance(range, c);
                suggestions.add(sug);
            }

            // Use constructor instead of non-existent Suggestions.create() static method
            return suggestionsCls.getConstructor(rangeCls, List.class)
                    .newInstance(range, suggestions);
        } catch (Throwable t) {
            return buildEmptySuggestions();
        }
    }

    private void registerCommand(Object dispatcher, String name) {
        try {
            adapter.info("[MinerTrack:DEBUG] Attempting to register command /" + name + " using dispatcher: "
                    + (dispatcher == null ? "null" : dispatcher.getClass().getName()));

            Class<?> managerCls = FabricReflection.forName("net.minecraft.server.command.CommandManager");
            if (managerCls == null) return;

            // 1. CommandManager.literal(name) -> LiteralArgumentBuilder
            Object literal = FabricReflection.callStatic("net.minecraft.server.command.CommandManager",
                    "literal", new Class<?>[]{String.class}, new Object[]{name});
            if (literal == null) return;

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
                    "argument", new Class<?>[]{String.class, argumentTypeCls},
                    new Object[]{"args", greedy});
            if (arg == null) {
                adapter.warning("Failed to build argument builder for /" + name + "; command will not be registered.");
                return;
            }

            // 4. Wrap the platform command in a brigadier Command<Object> lambda via proxy.
            Class<?> brigadierCommandCls = FabricReflection.forName("com.mojang.brigadier.Command");
            if (brigadierCommandCls == null) return;

            Object commandProxy = java.lang.reflect.Proxy.newProxyInstance(
                    brigadierCommandCls.getClassLoader(),
                    new Class<?>[]{brigadierCommandCls},
                    (proxy, method, methodArgs) -> {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == methodArgs[0];
                        if ("toString".equals(method.getName())) return "MinerTrackCommandProxy";

                        if (!"run".equals(method.getName()) || methodArgs == null || methodArgs.length == 0) {
                            return 1;
                        }
                        try {
                            Object ctx = methodArgs[0];
                            Object source = FabricReflection.callAny(ctx, "getSource", new Class<?>[0], new Object[0]);
                            String greedyString = "";
                            try {
                                Object greedyVal = FabricReflection.callAny(ctx, "getArgument",
                                        new Class<?>[]{String.class, Class.class},
                                        new Object[]{"args", String.class});
                                if (greedyVal != null) greedyString = greedyVal.toString();
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
                    new Class<?>[]{brigadierCommandCls},
                    new Object[]{commandProxy});

            // 5b. Tab-completion: arg.suggests(suggestionProvider)
            Class<?> suggestionProviderCls = FabricReflection.forName(
                    "com.mojang.brigadier.suggestion.SuggestionProvider");
            if (suggestionProviderCls != null) {
                Object suggestionProxy = java.lang.reflect.Proxy.newProxyInstance(
                        suggestionProviderCls.getClassLoader(),
                        new Class<?>[]{suggestionProviderCls},
                        (sproxy, sMethod, sArgs) -> {
                            if ("hashCode".equals(sMethod.getName())) return System.identityHashCode(sproxy);
                            if ("equals".equals(sMethod.getName())) return sproxy == sArgs[0];
                            if ("toString".equals(sMethod.getName())) return "MinerTrackSuggestionProxy";

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
                                    if (getInput != null) input = getInput.toString();
                                } catch (Throwable ignored) {
                                }
                                String[] args = parseArgs(input);
                                Object source = FabricReflection.callAny(sCtx, "getSource", new Class<?>[0], new Object[0]);
                                List<String> completions = commandExecutor.onTabComplete(source, args);
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
                        new Class<?>[]{suggestionProviderCls},
                        new Object[]{suggestionProxy});
            }

            // 6. literal.then(arg)
            FabricReflection.callAny(literal, "then",
                    new Class<?>[]{FabricReflection.forName("com.mojang.brigadier.builder.ArgumentBuilder")},
                    new Object[]{arg});

            // 7. literal.executes(commandProxy)
            FabricReflection.callAny(literal, "executes",
                    new Class<?>[]{brigadierCommandCls},
                    new Object[]{commandProxy});

            // 8. dispatcher.register(literal)
            // Use base class LiteralArgumentBuilder for method lookup
            // to avoid failures when literal() returns a proxy or subclass.
            Class<?> literalBuilderCls = FabricReflection.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder");
            if (literalBuilderCls == null) literalBuilderCls = literal.getClass();

            FabricReflection.call(dispatcher, "register",
                    new Class<?>[]{literalBuilderCls},
                    new Object[]{literal});

            adapter.info("[MinerTrack:DEBUG] Successfully registered command /" + name);
        } catch (Throwable t) {
            adapter.warning("Failed to register command /" + name + ": " + t.getMessage());
            if (adapter.isDebugEnabled()) {
                adapter.info("[MinerTrack:DEBUG] Exception during registerCommand for /" + name + ": " + t.toString());
            }
        }
    }

    /**
     * Called when the server is stopping. Receives the MinecraftServer instance.
     * Registered via FabricEventBus.registerServerStopping().
     *
     * @param server the MinecraftServer instance that is shutting down
     */
    public void onServerStopping(Object server) {
        adapter.info("MinerTrack (FabricPlatform) disabling.");
    }
}