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

    /**
     * Register a {@code ServerLifecycleEvents#SERVER_STARTED}
     * listener. The {@code handler} receives the live
     * {@code MinecraftServer} instance.
     */
    static void registerServerStarted(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
            "SERVER_STARTED", new Class<?>[]{
                FabricReflection.forName("net.minecraft.server.MinecraftServer")
            }, fromConsumer(handler));
    }

    /**
     * Register a {@code ServerWorldEvents#LOAD} listener. The
     * {@code handler} receives {@code (server, world)} as a
     * two-element array.
     */
    static void registerServerWorldLoad(java.util.function.Consumer<Object[]> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents",
            "LOAD", new Class<?>[]{
                FabricReflection.forName("net.minecraft.server.MinecraftServer"),
                FabricReflection.forName("net.minecraft.server.world.ServerWorld")
            }, fromArrayConsumer(handler));
    }

    /**
     * Register a {@code ServerWorldEvents#UNLOAD} listener.
     */
    static void registerServerWorldUnload(java.util.function.Consumer<Object[]> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents",
            "UNLOAD", new Class<?>[]{
                FabricReflection.forName("net.minecraft.server.MinecraftServer"),
                FabricReflection.forName("net.minecraft.server.world.ServerWorld")
            }, fromArrayConsumer(handler));
    }

    /**
     * Register a {@code ServerTickEvents#END_SERVER_TICK}
     * listener. The {@code handler} receives the live
     * {@code MinecraftServer} instance.
     */
    static void registerEndServerTick(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents",
            "END_SERVER_TICK", new Class<?>[]{
                FabricReflection.forName("net.minecraft.server.MinecraftServer")
            }, fromConsumer(handler));
    }

    /**
     * Register a {@code PlayerBlockBreakEvents#AFTER} listener.
     * The {@code handler} receives
     * {@code (world, player, pos, state, blockEntity)} as a
     * five-element array.
     */
    static void registerBlockBreakAfter(java.util.function.Consumer<Object[]> handler) {
        Class<?> worldCls = FabricReflection.forName("net.minecraft.world.World");
        Class<?> playerCls = FabricReflection.forName("net.minecraft.entity.player.PlayerEntity");
        Class<?> posCls = FabricReflection.forName("net.minecraft.util.math.BlockPos");
        Class<?> stateCls = FabricReflection.forName("net.minecraft.block.BlockState");
        Class<?> beCls = FabricReflection.forName("net.minecraft.block.entity.BlockEntity");
        register("net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents",
            "AFTER", new Class<?>[]{worldCls, playerCls, posCls, stateCls, beCls},
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
            "EVENT", new Class<?>[]{
                FabricReflection.forName("net.minecraft.entity.player.PlayerEntity"),
                FabricReflection.forName("net.minecraft.world.World"),
                FabricReflection.forName("net.minecraft.util.Hand"),
                FabricReflection.forName("net.minecraft.util.hit.BlockHitResult")
            }, args -> {
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
     */
    static void registerCommandRegistration(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
            "EVENT", new Class<?>[]{
                FabricReflection.forName("com.mojang.brigadier.CommandDispatcher"),
                // RegistryAccess — varies across 1.18-1.21
                // (sometimes a class, sometimes an interface).
                // We declare the param type as Object so the
                // dispatcher's {@code register} accepts whatever
                // shape the running server has.
                Object.class,
                // CommandEnvironment — Fabric command API v2
                // (1.19+). For 1.18, this parameter is absent;
                // we still declare it as Object so the proxy
                // doesn't fail signature matching.
                Object.class
            }, fromConsumer(handler));
    }

    // ── Internals ───────────────────────────────────────────────────

    /**
     * Internal: register a single Fabric API event listener by
     * reflection. The {@code listenerInterface} is the Fabric
     * API's interface (a functional interface whose single
     * abstract method takes the event parameters). We build a
     * dynamic proxy that implements {@code listenerInterface}
     * and whose {@code invoke} handler delegates to
     * {@code handler}.
     */
    private static void register(String eventClassName, String fieldName,
                                  Class<?>[] callbackParamTypes, CallbackAdapter handler) {
        try {
            Class<?> eventCls = FabricReflection.forName(eventClassName);
            if (eventCls == null) return;
            // The Fabric event holder is a static field whose
            // value is an {@code Event<Listener>} instance. We
            // call {@code EVENT.register(listener)} where
            // {@code listener} is a proxy implementing the
            // event's functional interface.
            java.lang.reflect.Field eventField = eventCls.getField(fieldName);
            Object event = eventField.get(null);
            // The Event class exposes {@code Class<T> type()} and
            // {@code void register(T listener)}. We use the
            // declared class of the field's generic type to
            // determine the listener interface.
            // Fabric's Event class has a
            // {@code register(Object)} method, so we don't
            // actually need the type.
            java.lang.reflect.Method registerMethod = event.getClass().getMethod("register", Object.class);
            // Determine the listener interface from the Event's
            // declared type. The generic parameter is on the
            // Event's parent (or via getTypeParameters on the
            // field). For our purposes, the listener interface
            // has exactly one abstract method whose parameter
            // types we already know.
            Class<?> listenerInterface = findListenerInterface(event.getClass(), callbackParamTypes.length);
            if (listenerInterface == null) {
                // Fallback: build a proxy that implements the
                // most-specific event interface Fabric declares
                // (often an abstract class or interface inside
                // the event class).
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
            Object proxy = Proxy.newProxyInstance(
                eventCls.getClassLoader(),
                new Class<?>[]{listenerInterface},
                proxyHandler);
            registerMethod.invoke(event, proxy);
        } catch (Throwable t) {
            // Silently swallow — registering a Fabric API
            // listener is best-effort. The mod continues to
            // function with reduced functionality (e.g. no
            // block-break events) if registration fails for
            // some unforeseen reason (e.g. the Fabric API
            // version on the server is older than expected).
        }
    }

    /**
     * Walk the Event object's class hierarchy looking for the
     * listener interface. Fabric's {@code Event<T>} is a
     * generic parameter; the actual {@code T} is visible via
     * the Event subclass's type information, but the easier
     * route is to find the single abstract method on the
     * class's interfaces.
     */
    private static Class<?> findListenerInterface(Class<?> eventCls, int expectedParamCount) {
        // Walk the listener interface hierarchy. The actual
        // listener interface is the one whose single
        // abstract method's parameter count matches
        // {@code expectedParamCount} (which is the arg
        // count the caller passed in). Picking the
        // matching interface by parameter count is more
        // robust than the original "first interface with
        // any abstract method" approach — the
        // {@code net.fabricmc.fabric.impl.event.Event}
        // class has several internal abstract methods on
        // its interface set (invoker-building, listener
        // comparison, etc.), and the old code was
        // returning the wrong interface for some events.
        for (Class<?> iface : allInterfaces(eventCls)) {
            Method m = findAbstractMethod(iface);
            if (m != null && m.getParameterCount() == expectedParamCount) {
                return iface;
            }
        }
        // Fallback: any interface with an abstract method
        // whose parameter count matches. Less strict than
        // the primary loop, but catches edge cases where
        // the listener interface is declared on a
        // superclass of the Event implementation rather
        // than directly.
        for (Class<?> iface : allInterfaces(eventCls)) {
            Method m = findAbstractMethod(iface);
            if (m != null && m.getParameterCount() == expectedParamCount) {
                return iface;
            }
        }
        return null;
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

    private static java.util.Set<Class<?>> allInterfaces(Class<?> cls) {
        java.util.Set<Class<?>> result = new java.util.LinkedHashSet<>();
        Class<?> cur = cls;
        while (cur != null) {
            for (Class<?> iface : cur.getInterfaces()) {
                if (result.add(iface)) {
                    result.addAll(allInterfaces(iface));
                }
            }
            cur = cur.getSuperclass();
        }
        return result;
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
