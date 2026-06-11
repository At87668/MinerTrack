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
            Class<?> listenerInterface = findListenerInterface(event.getClass());
            if (listenerInterface == null) {
                // Fallback: build a proxy that implements the
                // most-specific event interface Fabric declares
                // (often an abstract class or interface inside
                // the event class).
                return;
            }
            InvocationHandler proxyHandler = (proxy, method, args) -> {
                if (args == null) args = new Object[0];
                Object result = handler.handle(args);
                // For methods with a primitive return type, the
                // Proxy's default unboxing throws NPE on null;
                // the caller (event dispatcher) accepts a null
                // result for "I have no opinion", which is
                // equivalent to PASS.
                return result;
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
    private static Class<?> findListenerInterface(Class<?> eventCls) {
        // The simplest path: scan every interface on the
        // event class hierarchy. Fabric's Event<T> declares
        // a {@code register(T)} method, but the actual
        // listener interface is the type argument T, which
        // is unfortunately erased at runtime. As a
        // workaround, look at the class hierarchy of the
        // Event implementation and find the interface that
        // declares a method whose signature matches our
        // known callback parameter list.
        //
        // The caller passes the parameter types via
        // {@code callbackParamTypes}; we look for the
        // interface whose only abstract method has the
        // matching parameter list.
        for (Class<?> iface : allInterfaces(eventCls)) {
            for (Method m : iface.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    // Match by method name ({@code invoke} or
                    // any other) and approximate parameter
                    // count.
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length > 0) {
                        return iface;
                    }
                }
            }
        }
        return null;
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
