package link.star_dust.MinerTrack.fabric;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Reflection-based Fabric event-listener registration.
 *
 * <p>Fabric API exposes its event bus through interfaces that
 * take {@code net.minecraft.*} typed parameters in their
 * callback signatures. Because the Minecraft server jar is
 * NOT on the project's compile classpath (the project compiles
 * against the Bukkit API), we can't write Fabric listener
 * lambdas with {@code MinecraftServer} / {@code World} /
 * {@code ServerPlayerEntity} typed parameters directly.
 *
 * <p>Workaround: build a dynamic proxy that implements the
 * Fabric event-listener interface at runtime, and register
 * the proxy via reflection. The proxy's {@code invoke}
 * handler delegates to a platform-agnostic
 * {@link Consumer Consumer&lt;Object[]&gt;} that receives
 * the raw event arguments and runs the detection code via
 * {@link FabricReflection}.
 *
 * <p>This indirection is the same pattern Mojang's own
 * {@code com.mojang.brigadier} uses for cross-loader
 * compatibility, and is the standard way to bridge a
 * Bukkit-style classpath to a Fabric-style event bus without
 * using Loom.
 */
final class FabricEventBus {
    private FabricEventBus() {}
    // Debug flag — controlled by FabricAdapter.isDebugEnabled()
    // via setDebug(). Defaults to false to avoid log spam in
    // production builds.
    private static boolean DEBUG = false;

    /** Enable or disable debug logging for event registration. */
    static void setDebug(boolean enabled) { DEBUG = enabled; }

    /**
     * Register a {@code ServerLifecycleEvents#SERVER_STARTED}
     * listener. The {@code handler} receives the live
     * {@code MinecraftServer} instance.
     */
    static void registerServerStarted(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
            "SERVER_STARTED",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarted",
            fromConsumer(handler));
    }

    /**
     * Register a {@code ServerLifecycleEvents#SERVER_STOPPING}
     * listener. The {@code handler} receives the live
     * {@code MinecraftServer} instance that is about to stop.
     *
     * <p>This event fires before the server begins its shutdown
     * sequence, allowing mods to perform cleanup (e.g. flushing
     * buffers, cancelling async tasks, closing connections).
     */
    static void registerServerStopping(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
            "SERVER_STOPPING",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStopping",
            fromConsumer(handler));
    }

    /**
     * Register a {@code ServerWorldEvents#LOAD} listener. The
     * {@code handler} receives {@code (server, world)} as a
     * two-element array.
     */
    static void registerServerWorldLoad(java.util.function.Consumer<Object[]> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents",
            "LOAD",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Load",
            fromArrayConsumer(handler));
    }

    /**
     * Register a {@code ServerWorldEvents#UNLOAD} listener.
     */
    static void registerServerWorldUnload(java.util.function.Consumer<Object[]> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents",
            "UNLOAD",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Unload",
            fromArrayConsumer(handler));
    }

    /**
     * Register a {@code ServerTickEvents#END_SERVER_TICK}
     * listener. The {@code handler} receives the live
     * {@code MinecraftServer} instance.
     */
    static void registerEndServerTick(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents",
            "END_SERVER_TICK",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents$EndServerTick",
            fromConsumer(handler));
    }

    /**
     * Register a {@code PlayerBlockBreakEvents#AFTER} listener.
     * The {@code handler} receives
     * {@code (world, player, pos, state, blockEntity)} as a
     * five-element array.
     */
    static void registerBlockBreakAfter(java.util.function.Consumer<Object[]> handler) {
        register("net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents",
            "AFTER",
            "net.fabricmc.fabric.api.event.player.BlockBreakCallback",
            fromArrayConsumer(handler));
    }

    /**
     * Register a {@code UseBlockCallback} listener. The
     * {@code handler} receives
     * {@code (player, world, hand, hitResult)} as a four-element
     * array, and returns a {@code boolean} (true → consume the
     * event, false → pass it through).
     */
    static void registerUseBlock(UseBlockHandler handler) {
        Class<?> actionResultCls = FabricReflection.forName("net.minecraft.util.ActionResult");
        register("net.fabricmc.fabric.api.event.player.UseBlockCallback",
            "EVENT",
            "net.fabricmc.fabric.api.event.player.UseBlockCallback",
            args -> {
                boolean consumed = handler.handle(args[0], args[1], args[2], args[3]);
                try {
                    Object pass = actionResultCls.getField("PASS").get(null);
                    Object success = actionResultCls.getField("SUCCESS").get(null);
                    return consumed ? success : pass;
                } catch (Throwable t) {
                    return null;
                }
            });
    }

    /**
     * Register a {@code CommandRegistrationCallback} listener.
     * The {@code handler} receives a {@code CommandDispatcher}
     * (Fabric's brigadier dispatcher).
     *
     * <p>Supports both the v1 API (1.18.x) and the v2 API
     * (1.19+). The class name changed between versions; we
     * try v2 first (the common path for modern servers), then
     * fall back to v1 for 1.18.x servers.
     */
    static void registerCommandRegistration(Consumer<Object> handler) {
        // Try v2 first (1.19+)
        try {
            register("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
                "EVENT",
                "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
                fromConsumer(handler));
            return;
        } catch (Throwable ignored) {}
        // Fallback: v1 (1.18.x)
        try {
            register("net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback",
                "EVENT",
                "net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback",
                fromConsumer(handler));
        } catch (Throwable ignored) {}
    }

    // ── Internals ───────────────────────────────────────────────────

    /**
     * Internal: register a single Fabric API event listener by
     * reflection. The {@code listenerInterfaceName} is the FQN
     * of the Fabric event-listener interface (e.g.
     * {@code net.fabricmc.fabric.api.event.player.BlockBreakCallback}).
     * The listener-interface FQN must be supplied explicitly by
     * the caller because the generic parameter {@code T} of
     * {@code Event<T>} is erased at runtime and can't be
     * recovered from the event's class hierarchy alone — the
     * listener type T is NOT a superinterface of the
     * {@code Event} class.
     *
     * <p>We build a dynamic proxy that implements the
     * listener interface and whose {@code invoke} handler
     * delegates to {@code handler}. The proxy is then
     * registered with the event via {@code event.register(proxy)}.
     */
    private static void register(String eventClassName, String fieldName,
                                  String listenerInterfaceName, CallbackAdapter handler) {
        try {
            Class<?> eventCls = FabricReflection.forName(eventClassName);
            if (eventCls == null) {
                if (DEBUG) System.out.println("[MinerTrack:DEBUG] Event class not found: " + eventClassName);
                return;
            }
            // The Fabric event holder is a static field whose
            // value is an {@code Event<Listener>} instance. We
            // call {@code EVENT.register(listener)} where
            // {@code listener} is a proxy implementing the
            // event's functional interface.
            java.lang.reflect.Field eventField = null;
            try {
                eventField = eventCls.getField(fieldName);
            } catch (Throwable t) {
                if (DEBUG) System.out.println("[MinerTrack:DEBUG] Event field not found: " + fieldName + " on " + eventClassName);
                throw t;
            }
            Object event = eventField.get(null);
            // Resolve {@code Event#register(Object)} on the
            // Fabric API's public Event interface rather than
            // the implementation class. Calling the method
            // on the implementation via reflection may hit
            // JVM access checks (IllegalAccessException) when
            // the implementation class is in an impl package.
            java.lang.reflect.Method registerMethod;
            Class<?> eventIface = FabricReflection.forName("net.fabricmc.fabric.api.event.Event");
            if (eventIface != null) {
                try {
                    registerMethod = eventIface.getMethod("register", Object.class);
                } catch (NoSuchMethodException e) {
                    // Fallback to the concrete class method
                    registerMethod = event.getClass().getMethod("register", Object.class);
                }
            } else {
                registerMethod = event.getClass().getMethod("register", Object.class);
            }
            // The listener interface is supplied by the caller
            // — we can't reliably discover it from the Event
            // class alone because {@code Event<T>} erases the
            // {@code T} type parameter at runtime. Looking at
            // {@code event.getClass().getInterfaces()} returns
            // the Event class's own interfaces (e.g. the
            // {@code Event} marker interface and
            // {@code InvokerFactory}-related interfaces), NOT
            // the listener type T. The previous
            // {@code findListenerInterface} approach silently
            // built a proxy against the wrong interface and
            // the dispatcher called it as a no-op.
            Class<?> listenerInterface = FabricReflection.forName(listenerInterfaceName);
            if (listenerInterface == null) {
                if (DEBUG) System.out.println("[MinerTrack:DEBUG] Listener interface not found: " + listenerInterfaceName);
                return;
            }
            // Identify the listener interface's single
            // abstract method. The proxy is invoked for ANY
            // method on the interface (including
            // {@code equals}, {@code hashCode}, {@code
            // toString} from {@link Object}), but only the
            // abstract method is the actual event callback.
            // The original handler treated every invocation
            // as an event fire, which meant that the
            // Event<T> infrastructure's set-membership
            // bookkeeping (e.g. {@code listener.equals(...)} on
            // each register() call) would end up invoking our
            // detection handler with bogus arguments. Filtering
            // by abstract method ensures we only call the
            // handler for the actual event, never for Object
            // methods.
            final java.lang.reflect.Method abstractMethod = findAbstractMethod(listenerInterface);
            if (abstractMethod == null) {
                if (DEBUG) System.out.println("[MinerTrack:DEBUG] No single abstract method on listener interface: " + listenerInterfaceName);
                // No abstract method on the listener interface
                // (shouldn't happen for a Fabric event, which
                // is always a functional interface). Skip the
                // registration rather than building a proxy
                // that does the wrong thing.
                return;
            }
            final int expectedParamCount = abstractMethod.getParameterCount();
            InvocationHandler proxyHandler = (proxy, method, args) -> {
                // Only forward to the event handler when the
                // method being invoked is the listener
                // interface's single abstract method (the
                // event callback). For any other method —
                // Object's {@code equals}, {@code hashCode},
                // {@code toString}, the Event<T> infrastructure's
                // internal book-keeping methods, etc. — return
                // a sensible default so the proxy behaves
                // correctly as an Object (equal to itself,
                // identity hash, default toString).
                if (!method.equals(abstractMethod)) {
                    if ("equals".equals(method.getName()) && method.getParameterCount() == 1
                            && method.getParameterTypes()[0] == Object.class) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(method.getName()) && method.getParameterCount() == 0) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(method.getName()) && method.getParameterCount() == 0) {
                        return "FabricEventBus$Proxy@" + System.identityHashCode(proxy);
                    }
                    // For any other non-abstract method, just
                    // return a default. The proxy won't
                    // actually be invoked through any other
                    // method, but the JVM may call
                    // {@link Object}'s methods during
                    // classloader / GC walks.
                    return null;
                }
                if (args == null) args = new Object[0];
                // Validate the arg count. The dispatcher always
                // passes the exact number of args the abstract
                // method declares; if the count differs, the
                // proxy is being invoked through a path we
                // don't recognise, and we shouldn't feed bogus
                // args to the handler.
                if (args.length != expectedParamCount) {
                    return null;
                }
                return handler.handle(args);
            };
            if (DEBUG) System.out.println("[MinerTrack:DEBUG] Creating proxy for listener " + listenerInterfaceName + " (event=" + eventClassName + "." + fieldName + ")");
            Object proxy = Proxy.newProxyInstance(
                eventCls.getClassLoader(),
                new Class<?>[]{listenerInterface},
                proxyHandler);
            if (DEBUG) System.out.println("[MinerTrack:DEBUG] Invoking register on event " + eventClassName + "." + fieldName);
            registerMethod.invoke(event, proxy);
            if (DEBUG) System.out.println("[MinerTrack:DEBUG] Registered listener for " + eventClassName + "." + fieldName + " using " + listenerInterfaceName);
        } catch (Throwable t) {
            if (DEBUG) {
                System.out.println("[MinerTrack:DEBUG] Exception during Fabric event registration: " + t.getMessage());
                t.printStackTrace();
            }
            // Silently swallow — registering a Fabric API
            // listener is best-effort. The mod continues to
            // function with reduced functionality (e.g. no
            // block-break events) if registration fails for
            // some unforeseen reason (e.g. the Fabric API
            // version on the server is older than expected).
        }
    }

    /**
     * Find the single abstract method declared on
     * {@code iface} (a functional-interface convention).
     * Returns {@code null} when the interface has zero or
     * multiple abstract methods (i.e. it's not a
     * functional interface, in which case we can't reliably
     * forward to it).
     */
    private static Method findAbstractMethod(Class<?> iface) {
        if (iface == null) return null;
        Method found = null;
        int count = 0;
        for (Method m : iface.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                found = m;
                count++;
                if (count > 1) return null;
            }
        }
        // Functional interfaces can also have default
        // methods alongside the abstract one; the
        // abstract method count is the relevant filter.
        return found;
    }

    /** Adapter that produces the proxy's return value from the
     *  listener's raw argument list. */
    private interface CallbackAdapter {
        /**
         * Handle the raw Fabric event argument array. Return the
         * value the proxy should yield — {@code null} for "no
         * opinion" (PASS-style for void events) or an
         * {@code ActionResult} for {@code UseBlockCallback}.
         */
        Object handle(Object[] args);
    }

    /** Adapter that wraps a {@code Consumer<Object>}. */
    private static CallbackAdapter fromConsumer(java.util.function.Consumer<Object> c) {
        return args -> { c.accept(args[0]); return null; };
    }

    /** Adapter that wraps a {@code Consumer<Object[]>}. */
    private static CallbackAdapter fromArrayConsumer(java.util.function.Consumer<Object[]> c) {
        return args -> { c.accept(args); return null; };
    }

    /** Handler for {@link #registerUseBlock(UseBlockHandler)}. */
    interface UseBlockHandler {
        boolean handle(Object player, Object world, Object hand, Object hitResult);
    }
}
