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
 * {@code CommandRegistrationCallback} (stable 1.18+) and
 * delegates subcommand logic to the same
 * {@code MinerTrackCommandCore} the Bukkit path uses. The
 * {@code /minertrack} command uses
 * {@code StringArgumentType.greedyString()} to capture the
 * entire input line as a single string, then splits it into
 * tokens so the existing subcommand parser works unchanged.
 *
 * <p>Version compatibility: 1.18–26.x. The Fabric API
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
    /** Guard against double registration (CommandRegistrationCallback + ServerStarted fallback). */
    private volatile boolean commandsRegistered = false;

    @Override
    public void onInitializeServer() {
        // ── Adapter + logger ─────────────────────────────────────
        adapter = new FabricAdapter();
        DebugConfig debugConfig = () -> adapter.isDebugEnabled();
        CoreLogger.init(debugConfig, java.util.logging.Logger.getLogger("MinerTrack"));
        FabricEventBus.setDebug(adapter.isDebugEnabled());
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
            // Debug: log dispatcher type before registering commands.
            try {
                adapter.info("[MinerTrack:DEBUG] CommandRegistrationCallback invoked. dispatcher=" + (dispatcher == null ? "null" : dispatcher.getClass().getName()));
            } catch (Throwable ignored) {
            }
            // Register the /minertrack command and its aliases
            // (/mt, /mtrack) by reflection. The brigadier
            // dispatcher is a generic class; we call its
            // {@code register} method with a CommandBuilder
            // built via the static {@code literal} / {@code
            // argument} methods on MinecraftServer's
            // CommandManager. The {@code commandExecutor} runs
            // the same subcommand parser the Bukkit path uses.
            if (commandsRegistered) return; // already registered — skip duplicate
            try {
                registerCommand(dispatcher, "minertrack");
                registerCommand(dispatcher, "mt");
                registerCommand(dispatcher, "mtrack");
                commandsRegistered = true;
            } catch (Throwable t) {
                adapter.warning("[MinerTrack:DEBUG] Exception while registering commands in callback: " + t.getMessage());
            }
        });

        // Also attempt an explicit registration on server start
        // as a fallback for environments where the Command
        // Registration callback may not fire as expected.
        FabricEventBus.registerServerStarted(server -> {
            if (commandsRegistered) return; // already registered — skip
            try {
                adapter.info("[MinerTrack:DEBUG] ServerStarted callback invoked. server=" + (server == null ? "null" : server.getClass().getName()));
                Object cmdManager = FabricReflection.callAny(server, "getCommandManager", new Class<?>[0], new Object[0]);
                if (cmdManager == null) return;
                Object dispatcher = null;
                try {
                    dispatcher = FabricReflection.callAny(cmdManager, "getDispatcher", new Class<?>[0], new Object[0]);
                } catch (Throwable ignored) {
                    // Some implementations expose the dispatcher
                    // directly as the command manager.
                }
                if (dispatcher == null) dispatcher = cmdManager;
                if (dispatcher == null) return;
                adapter.info("[MinerTrack:DEBUG] Explicit registration dispatcher=" + (dispatcher == null ? "null" : dispatcher.getClass().getName()));
                registerCommand(dispatcher, "minertrack");
                registerCommand(dispatcher, "mt");
                registerCommand(dispatcher, "mtrack");
                commandsRegistered = true;
            } catch (Throwable t) {
                adapter.warning("Explicit command registration failed: " + t.getMessage());
            }
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
    static String[] parseArgs(String input) {
        if (input == null) return new String[0];
        // Strip the leading command name token(s) — brigadier
        // passes the full input line (e.g. "/minertrack kick Foo")
        // but our tab completer expects only the sub-command args.
        // We strip up to the first token that is NOT the command
        // name itself.
        String[] raw = input.trim().split("\\s+");
        if (raw.length == 0) return new String[0];
        // Find the offset where the subcommand tokens begin.
        // If the first token looks like the command name (starts
        // with "/" or matches known names), skip it.
        int offset = 0;
        if (raw[0].startsWith("/") || "minertrack".equals(raw[0])
                || "mt".equals(raw[0]) || "mtrack".equals(raw[0])) {
            offset = 1;
        }
        if (offset >= raw.length) return new String[0];
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (int i = offset; i < raw.length; i++) {
            if (!raw[i].isEmpty()) tokens.add(raw[i]);
        }
        return tokens.toArray(new String[0]);
    }

    // ── Tab-completion helpers ─────────────────────────────────────

    /**
     * Build an empty {@code Suggestions} instance for brigadier.
     */
    private static Object buildEmptySuggestions() {
        Class<?> suggestionsCls = FabricReflection.forName(
            "com.mojang.brigadier.suggestion.Suggestions");
        if (suggestionsCls == null) return null;
        Object empty = FabricReflection.callStatic(suggestionsCls.getName(),
            "empty", new Class<?>[0], new Object[0]);
        return empty;
    }

    /**
     * Build a {@code Suggestions} instance from a list of
     * completion strings and a {@code SuggestionsBuilder}.
     *
     * <p>The brigadier {@code Suggestions} class has a static
     * factory {@code Suggestions.create(String, Collection<Suggestion>)}.
     * Each {@code Suggestion} is constructed from a
     * {@code StringRange} and the suggestion text.  Because we
     * can't reference those types directly, the whole chain is
     * reflection-based.
     */
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
            // Get the start offset from the builder so the
            // suggestion replaces only the trailing token.
            int start = 0;
            try {
                Object startObj = FabricReflection.callAny(builder,
                    "getStart", new Class<?>[0], new Object[0]);
                if (startObj instanceof Number) start = ((Number) startObj).intValue();
            } catch (Throwable ignored) {}
            Object range = FabricReflection.callStatic(rangeCls.getName(),
                "between",
                new Class<?>[]{int.class, int.class},
                new Object[]{start, input.length()});
            java.util.List<Object> suggestions = new java.util.ArrayList<>();
            for (String c : completions) {
                Object sug = suggestionCls.getDeclaredConstructor(
                    rangeCls, String.class).newInstance(range, c);
                suggestions.add(sug);
            }
            // Suggestions.create(input, collection)
            Object result = FabricReflection.callStatic(suggestionsCls.getName(),
                "create",
                new Class<?>[]{String.class, java.util.Collection.class},
                new Object[]{input, suggestions});
            return result;
        } catch (Throwable t) {
            return buildEmptySuggestions();
        }
    }

    /**
     * Register a single literal command on the brigadier
     * dispatcher via reflection. Builds a
     * {@code LiteralArgumentBuilder} from
     * {@code CommandManager.literal(name)}, attaches a single
     * greedy {@code String} argument (via
     * {@code StringArgumentType.greedyString()}) that captures
     * every trailing token as one string, and registers the
     * resulting builder with the dispatcher.
     *
     * <p>The {@code exec} body (a {@code Command}) and the
     * {@code argument} builder are both created via reflection
     * because their types live in {@code com.mojang.brigadier}
     * and {@code net.minecraft.server.command}, neither of which
     * is on the project's compile classpath.
     *
     * <p>Why greedyString: the previous implementation used
     * {@code CommandManager.argument("args", String[].class)}
     * to capture the trailing tokens as an array, but brigadier
     * only supports a fixed set of argument types (integer,
     * long, float, double, boolean, string). {@code String[]}
     * is not in that set, so the argument's argument-type
     * serializer was unset and the dispatcher rejected the
     * command at parse time with
     * "Unknown or incomplete command, mt<--[HERE]".
     * {@code greedyString} returns a single
     * {@code String} that captures every remaining token
     * verbatim, and we split on whitespace in the executor.
     */
    private void registerCommand(Object dispatcher, String name) {
        try {
            adapter.info("[MinerTrack:DEBUG] Attempting to register command /" + name + " using dispatcher: " + (dispatcher == null ? "null" : dispatcher.getClass().getName()));
            Class<?> managerCls = FabricReflection.forName("net.minecraft.server.command.CommandManager");
            if (managerCls == null) return;
            // 1. CommandManager.literal(name) -> LiteralArgumentBuilder
            Object literal = FabricReflection.callStatic("net.minecraft.server.command.CommandManager",
                "literal", new Class<?>[]{String.class}, new Object[]{name});
            if (literal == null) return;
            Class<?> literalCls = literal.getClass();
            // 2. StringArgumentType.greedyString() -> ArgumentType<String>
            Object greedy = FabricReflection.callStatic("com.mojang.brigadier.arguments.StringArgumentType",
                "greedyString", new Class<?>[0], new Object[0]);
            if (greedy == null) {
                adapter.warning("Failed to build greedyString argument type; /" + name + " will not be registered.");
                return;
            }
            // 3. CommandManager.argument("args", greedy) -> RequiredArgumentBuilder
            Object arg = FabricReflection.callStatic("net.minecraft.server.command.CommandManager",
                "argument", new Class<?>[]{String.class, FabricReflection.forName("com.mojang.brigadier.arguments.ArgumentType")},
                new Object[]{"args", greedy});
            if (arg == null) {
                adapter.warning("Failed to build argument builder for /" + name + "; command will not be registered.");
                return;
            }
            // 4. Wrap the platform command in a brigadier
            //    {@code Command<Object>} lambda. The lambda's
            //    single method ({@code run}) takes a
            //    {@code CommandContext<Object>} and returns int.
            //    We build a dynamic proxy that implements
            //    {@code com.mojang.brigadier.Command} and reads
            //    the context's source + the greedy String
            //    argument via reflection.
            Class<?> brigadierCommandCls = FabricReflection.forName("com.mojang.brigadier.Command");
            if (brigadierCommandCls == null) return;
            Object commandProxy = java.lang.reflect.Proxy.newProxyInstance(
                brigadierCommandCls.getClassLoader(),
                new Class<?>[]{brigadierCommandCls},
                (proxy, method, methodArgs) -> {
                    if (!"run".equals(method.getName()) || methodArgs == null || methodArgs.length == 0) {
                        return 1;
                    }
                    try {
                        Object ctx = methodArgs[0];
                        Object source = FabricReflection.callAny(ctx, "getSource", new Class<?>[0], new Object[0]);
                        // Read the "args" greedy String from the
                        // CommandContext. The greedy string is
                        // the entire tail of the command line
                        // (e.g. "kick PlayerA no cheating"
                        // for /minertrack kick PlayerA no
                        // cheating), so we can split it on
                        // whitespace and pass the tokens to
                        // the platform command executor.
                        //
                        // IMPORTANT: getArgument(String, Class<T>)
                        // expects the VALUE type class (String.class),
                        // NOT the ArgumentType descriptor class.
                        // The original code passed ArgumentType.class
                        // which caused a ClassCastException inside
                        // brigadier's clazz.cast(), making all
                        // subcommand arguments silently return "".
                        String greedyString = "";
                        try {
                            Object greedyVal = FabricReflection.callAny(ctx, "getArgument",
                                new Class<?>[]{String.class, String.class},
                                new Object[]{"args", greedy});
                            if (greedyVal != null) greedyString = greedyVal.toString();
                        } catch (Throwable t) {
                            // No "args" provided (e.g. /minertrack
                            // with no subcommand) — the
                            // dispatcher may call us directly
                            // on the literal, in which case the
                            // {@code args} argument is absent.
                            // That's fine: the command executor
                            // treats an empty array as "show
                            // help".
                        }
                        String[] args = parseArgs(greedyString);
                        return commandExecutor.onCommand(source, args) ? 1 : 0;
                    } catch (Throwable t) {
                        return 0;
                    }
                });
            // 5. arg.executes(commandProxy)
            FabricReflection.callAny(arg, "executes",
                new Class<?>[]{FabricReflection.forName("com.mojang.brigadier.Command")},
                new Object[]{commandProxy});
            // 5b. Tab-completion: arg.suggests(suggestionProvider)
            //     Wraps commandExecutor.onTabComplete() into a
            //     brigadier SuggestionProvider<Object> via a dynamic
            //     proxy. The proxy's {@code getSuggestions} method
            //     extracts the greedy string typed so far, splits it
            //     into tokens, calls onTabComplete, and wraps the
            //     resulting List<String> into brigadier Suggestions.
            Class<?> suggestionProviderCls = FabricReflection.forName(
                "com.mojang.brigadier.suggestion.SuggestionProvider");
            if (suggestionProviderCls != null) {
                Object suggestionProxy = java.lang.reflect.Proxy.newProxyInstance(
                    suggestionProviderCls.getClassLoader(),
                    new Class<?>[]{suggestionProviderCls},
                    (sproxy, sMethod, sArgs) -> {
                        if (!"getSuggestions".equals(sMethod.getName()) || sArgs == null || sArgs.length < 2) {
                            return buildEmptySuggestions();
                        }
                        try {
                            Object sCtx = sArgs[0];   // CommandContext<Object>
                            Object sBuilder = sArgs[1]; // SuggestionsBuilder
                            // Read the partial input typed so far
                            String input = "";
                            try {
                                Object getInput = FabricReflection.callAny(sBuilder, "getInput",
                                    new Class<?>[0], new Object[0]);
                                if (getInput != null) input = getInput.toString();
                            } catch (Throwable ignored) {}
                            // Build args array from the current input
                            // (skip the /command prefix, take only the
                            // tail after the command name)
                            String[] args = parseArgs(input);
                            // The first token is the command name;
                            // the rest is what the tab completer needs.
                            // However the greedy string starts at index
                            // 1 because brigadier sees the full input
                            // line.  Actually the input is the full
                            // command line; we need everything after
                            // the /minertrack part.
                            Object source = FabricReflection.callAny(sCtx,
                                "getSource", new Class<?>[0], new Object[0]);
                            java.util.List<String> completions =
                                commandExecutor.onTabComplete(source, args);
                            if (completions == null || completions.isEmpty()) {
                                return buildEmptySuggestions();
                            }
                            // Build Suggestions from the completions
                            return buildSuggestions(sBuilder, completions, input);
                        } catch (Throwable t) {
                            return buildEmptySuggestions();
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
            //    — the literal itself must also execute so
            //    `/minertrack` (no trailing args) works as
            //    "show help" (the {@code commandExecutor} treats
            //    an empty args[] as the help command). Without
            //    this, the dispatcher would match the literal
            //    node but find no command body and report
            //    "Unknown or incomplete command".
            FabricReflection.callAny(literal, "executes",
                new Class<?>[]{FabricReflection.forName("com.mojang.brigadier.Command")},
                new Object[]{commandProxy});
            // 8. dispatcher.register(literal)
            //    The dispatcher.register method takes a
            //    {@code CommandNode}. {@code LiteralArgumentBuilder}
            //    extends {@code CommandNode} via the
            //    {@code ArgumentBuilder} → {@code CommandBuilder}
            //    chain, so passing the builder's class as the
            //    method's parameter type works — brigadier's
            //    dispatcher.register is `CommandNode
            //    register(CommandNode)`, and the builder IS a
            //    CommandNode. We resolve the class literal via
            //    reflection because {@code CommandNode} lives in
            //    the {@code com.mojang.brigadier.tree} package,
            //    which is not on our compile classpath.
            Class<?> commandNodeCls = FabricReflection.forName("com.mojang.brigadier.tree.CommandNode");
            if (commandNodeCls == null) {
                // Fallback: use the builder's first
                // implemented interface, which is
                // ArgumentBuilder (a CommandNode subclass).
                commandNodeCls = literalCls.getInterfaces().length > 0
                        ? literalCls.getInterfaces()[0]
                        : literalCls;
            }
            FabricReflection.call(dispatcher, "register",
                new Class<?>[]{commandNodeCls},
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
