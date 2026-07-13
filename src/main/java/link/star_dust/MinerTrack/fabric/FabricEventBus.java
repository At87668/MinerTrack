package link.star_dust.MinerTrack.fabric;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Fabric event-listener registration via dynamic Proxy.
 * Minecraft types are not on compile classpath, so Fabric API event
 * callbacks (which take net.minecraft.* parameters) must be proxied.
 */
final class FabricEventBus {
    private FabricEventBus() {}

    static void registerServerStarted(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
            "SERVER_STARTED",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarted",
            fromConsumer(handler));
    }

    static void registerServerStopping(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
            "SERVER_STOPPING",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStopping",
            fromConsumer(handler));
    }

    static void registerServerWorldLoad(java.util.function.Consumer<Object[]> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents",
            "LOAD",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Load",
            fromArrayConsumer(handler));
    }

    static void registerServerWorldUnload(java.util.function.Consumer<Object[]> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents",
            "UNLOAD",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Unload",
            fromArrayConsumer(handler));
    }

    static void registerEndServerTick(Consumer<Object> handler) {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents",
            "END_SERVER_TICK",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents$EndServerTick",
            fromConsumer(handler));
    }

    static void registerBlockBreakAfter(java.util.function.Consumer<Object[]> handler) {
        // PlayerBlockBreakEvents.AFTER uses After interface:
        //   void afterBlockBreak(World, PlayerEntity, BlockPos, BlockState, BlockEntity?)
        // The inner interface compiled name is PlayerBlockBreakEvents$After.
        // Must use the exact inner class name so the dynamic proxy matches.
        register("net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents",
            "AFTER",
            "net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents$After",
            fromArrayConsumer(handler));
    }

    static void registerUseBlock(UseBlockHandler handler) {
        Class<?> actionResultCls = FabricReflection.forName("net.minecraft.world.InteractionResult");
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
     * Register a command registration callback.
     *
     * <p>Supports v2 (1.19+) with v1 (1.18.x) fallback. The handler
     * receives the raw {@code CommandDispatcher} object (typed as
     * {@code Object} to avoid compile-time dependency on Minecraft
     * classes).
     */
    static void registerCommandRegistration(Consumer<Object> handler) {
        // Try v2 first (1.19+)
        if (tryRegisterCommandV2(handler)) return;
        // Fallback: v1 (1.18.x)
        tryRegisterCommandV1(handler);
    }

    private static boolean tryRegisterCommandV2(Consumer<Object> handler) {
        try {
            Class<?> callbackCls = FabricReflection.forName(
                "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
            if (callbackCls == null) return false;

            java.lang.reflect.Field eventField = callbackCls.getField("EVENT");
            Object event = eventField.get(null);

            // CommandRegistrationCallback has a single abstract method:
            //   void register(CommandDispatcher<CommandSourceStack>, RegistryAccess, Environment)
            // We proxy it so the handler receives just the dispatcher.
            Class<?> listenerIface = callbackCls;
            java.lang.reflect.Method abstractMethod = findAbstractMethod(listenerIface);
            if (abstractMethod == null) return false;

            Class<?> eventIface = FabricReflection.forName("net.fabricmc.fabric.api.event.Event");
            java.lang.reflect.Method registerMethod = eventIface != null
                ? eventIface.getMethod("register", Object.class)
                : event.getClass().getMethod("register", Object.class);

            Object proxy = Proxy.newProxyInstance(
                callbackCls.getClassLoader(),
                new Class<?>[]{listenerIface},
                (proxyObj, method, args) -> {
                    if (!method.equals(abstractMethod)) return handleObjectMethods(proxyObj, method, args);
                    // args[0] is the CommandDispatcher
                    handler.accept(args[0]);
                    return null;
                });

            registerMethod.invoke(event, proxy);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryRegisterCommandV1(Consumer<Object> handler) {
        try {
            Class<?> callbackCls = FabricReflection.forName(
                "net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback");
            if (callbackCls == null) return false;

            java.lang.reflect.Field eventField = callbackCls.getField("EVENT");
            Object event = eventField.get(null);

            Class<?> listenerIface = callbackCls;
            java.lang.reflect.Method abstractMethod = findAbstractMethod(listenerIface);
            if (abstractMethod == null) return false;

            Class<?> eventIface = FabricReflection.forName("net.fabricmc.fabric.api.event.Event");
            java.lang.reflect.Method registerMethod = eventIface != null
                ? eventIface.getMethod("register", Object.class)
                : event.getClass().getMethod("register", Object.class);

            Object proxy = Proxy.newProxyInstance(
                callbackCls.getClassLoader(),
                new Class<?>[]{listenerIface},
                (proxyObj, method, args) -> {
                    if (!method.equals(abstractMethod)) return handleObjectMethods(proxyObj, method, args);
                    handler.accept(args[0]);
                    return null;
                });

            registerMethod.invoke(event, proxy);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Handle equals/hashCode/toString for dynamic proxies. */
    private static Object handleObjectMethods(Object proxy, Method method, Object[] args) {
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
        return null;
    }

    // ── Internals ───────────────────────────────────────────────────

    /**
     * Build a dynamic proxy implementing the listener interface, register with the event.
     * Listener interface FQN is explicit because Event&lt;T&gt; erases T at runtime.
     */
    private static void register(String eventClassName, String fieldName,
                                  String listenerInterfaceName, CallbackAdapter handler) {
        try {
            Class<?> eventCls = FabricReflection.forName(eventClassName);
            if (eventCls == null) return;
            java.lang.reflect.Field eventField = eventCls.getField(fieldName);
            Object event = eventField.get(null);

            java.lang.reflect.Method registerMethod;
            Class<?> eventIface = FabricReflection.forName("net.fabricmc.fabric.api.event.Event");
            if (eventIface != null) {
                registerMethod = eventIface.getMethod("register", Object.class);
            } else {
                registerMethod = event.getClass().getMethod("register", Object.class);
            }
            Class<?> listenerInterface = FabricReflection.forName(listenerInterfaceName);
            if (listenerInterface == null) return;

            final Method abstractMethod = findAbstractMethod(listenerInterface);
            if (abstractMethod == null) return;
            final int expectedParamCount = abstractMethod.getParameterCount();
            InvocationHandler proxyHandler = (proxy, method, args) -> {
                if (!method.equals(abstractMethod)) {
                    return handleObjectMethods(proxy, method, args);
                }
                if (args == null) args = new Object[0];
                if (args.length != expectedParamCount) return null;
                return handler.handle(args);
            };
            Object proxy = Proxy.newProxyInstance(
                eventCls.getClassLoader(),
                new Class<?>[]{listenerInterface},
                proxyHandler);
            registerMethod.invoke(event, proxy);
        } catch (Throwable t) {
            // Best-effort; mod continues with reduced functionality
        }
    }

    /** Returns the single abstract method if it's a functional interface, else null. */
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
        return found;
    }

    @FunctionalInterface
    interface CallbackAdapter {
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
