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

package link.star_dust.MinerTrack.forge;

import link.star_dust.MinerTrack.fabric.FabricReflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight reflection helpers for accessing Forge internals.
 *
 * <p>Delegates to {@link FabricReflection} for Minecraft-internal access
 * (class loading, method/field resolution, text component creation, etc.).
 * Adds Forge-specific helpers for mod container, event bus, and config path
 * access that differ from Fabric's APIs.
 *
 * <p>Forge uses SRG/MCP names at runtime. Minecraft class/method access
 * through FabricReflection handles mapping resolution transparently.
 */
final class ForgeReflection {

    private static volatile Object cachedServer;
    static boolean DEBUG_REFLECTION = false;

    private ForgeReflection() {}

    static void setDebugReflection(boolean on) { DEBUG_REFLECTION = on; FabricReflection.setDebugReflection(on); }

    static void setCachedServer(Object server) { cachedServer = server; FabricReflection.setCachedServer(server); }
    static Object getServer() { return FabricReflection.getServer(); }

    /** Clear the debug cache. Delegates to the adapter. */
    public static void clearDebugCache() {
        // This is a no-op here; callers use ForgeAdapter.clearDebugCache()
    }

    private static void log(String msg) { System.out.println("[MinerTrack:ForgeReflection] " + msg); }

    // Sentinel & caches
    private static final Object NOT_FOUND = new Object();

    private static final class MethodKey {
        final Class<?> cls; final String name; final Class<?>[] paramTypes;
        MethodKey(Class<?> cls, String name, Class<?>[] paramTypes) {
            this.cls = cls; this.name = name; this.paramTypes = paramTypes;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof MethodKey)) return false;
            MethodKey k = (MethodKey) o;
            return cls == k.cls && name.equals(k.name) && Arrays.equals(paramTypes, k.paramTypes);
        }
        @Override public int hashCode() {
            return cls.hashCode() * 31 + name.hashCode() + Arrays.hashCode(paramTypes);
        }
    }

    private static final ConcurrentHashMap<MethodKey, Object> methodCache = new ConcurrentHashMap<>(128);

    @SuppressWarnings("unchecked")
    private static <T> T unwrap(Object cached) {
        return (cached == NOT_FOUND) ? null : (T) cached;
    }

    static final Class<?>[] NO_PARAMS = new Class<?>[0];
    static final Object[]   NO_ARGS  = new Object[0];

    // ==================================================================
    // Forge-specific class loading
    // ==================================================================

    static Class<?> forgeClass(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException e) { return null; }
    }

    // ==================================================================
    // Forge config directory
    // ==================================================================

    static java.nio.file.Path getConfigDir() {
        try {
            Class<?> fmlPaths = forgeClass("net.minecraftforge.fml.loading.FMLPaths");
            if (fmlPaths == null) return java.nio.file.Path.of("config");
            Field f = fmlPaths.getField("CONFIGDIR");
            Object configDirPath = f.get(null);
            if (configDirPath instanceof java.nio.file.Path) return (java.nio.file.Path) configDirPath;
            return java.nio.file.Path.of("config");
        } catch (Throwable t) {
            return java.nio.file.Path.of("config");
        }
    }

    // ==================================================================
    // Forge mod version
    // ==================================================================

    static String getModVersion(String modId) {
        try {
            Class<?> modListCls = forgeClass("net.minecraftforge.fml.ModList");
            if (modListCls == null) return "unknown";
            Method getMethod = modListCls.getMethod("get");
            Object modList = getMethod.invoke(null);
            Method getContainer = modListCls.getMethod("getModContainerById", String.class);
            Object container = getContainer.invoke(modList, modId);
            if (container == null) return "unknown";
            // java.util.Optional — call get()
            Method optGet = container.getClass().getMethod("get");
            Object actualContainer = optGet.invoke(container);
            Method getModInfo = actualContainer.getClass().getMethod("getModInfo");
            Object modInfo = getModInfo.invoke(actualContainer);
            Method getVersion = modInfo.getClass().getMethod("getVersion");
            Object version = getVersion.invoke(modInfo);
            return version != null ? version.toString() : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ==================================================================
    // Forge event bus
    // ==================================================================

    /**
     * Get the Forge mod event bus (for mod-specific lifecycle events).
     * Returns {@code FMLJavaModLoadingContext.get().getModEventBus()}.
     */
    static Object getModEventBus() {
        try {
            Class<?> ctxCls = forgeClass("net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext");
            if (ctxCls == null) return null;
            Method getCtx = ctxCls.getMethod("get");
            Object ctx = getCtx.invoke(null);
            Method getBus = ctxCls.getMethod("getModEventBus");
            return getBus.invoke(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Get the Forge main event bus (MinecraftForge.EVENT_BUS).
     */
    static Object getMainEventBus() {
        try {
            Class<?> mcForge = forgeClass("net.minecraftforge.common.MinecraftForge");
            if (mcForge == null) return null;
            Field ebField = mcForge.getField("EVENT_BUS");
            return ebField.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Register a generic event listener on the given Forge event bus.
     * Uses Java dynamic proxy to implement the Consumer interface.
     */
    @SuppressWarnings("rawtypes")
    static void registerEventListener(Object eventBus, Class<?> eventClass,
                                       java.util.function.Consumer<Object> handler) {
        if (eventBus == null || eventClass == null) return;
        try {
            // IEventBus.addListener(Consumer<T>)
            Class<?> consumerCls = java.util.function.Consumer.class;
            Method addListener = eventBus.getClass().getMethod("addListener",
                consumerCls);

            Object proxy = Proxy.newProxyInstance(
                consumerCls.getClassLoader(),
                new Class<?>[]{consumerCls},
                (proxyObj, method, args) -> {
                    if ("accept".equals(method.getName()) && args != null && args.length == 1) {
                        handler.accept(args[0]);
                    } else if ("equals".equals(method.getName())) {
                        return proxyObj == args[0];
                    } else if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxyObj);
                    } else if ("toString".equals(method.getName())) {
                        return "ForgeEventListener$Proxy";
                    }
                    return null;
                });

            addListener.invoke(eventBus, proxy);
        } catch (Throwable t) {
            if (DEBUG_REFLECTION) log("Failed to register listener for " + eventClass.getName()
                + ": " + t.getMessage());
        }
    }

    // ==================================================================
    // Delegate to FabricReflection for Minecraft internals
    // ==================================================================

    /** Load a Minecraft class — delegates to FabricReflection. */
    static Class<?> forName(String namedClassName) {
        return FabricReflection.forName(namedClassName);
    }

    /** Create an instance via reflection. */
    static Object newInstance(String className, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> cls = forName(className);
            if (cls == null) return null;
            Constructor<?> ctor = cls.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable t) { return null; }
    }

    /** Call an instance method on the target object. */
    static Object call(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        return FabricReflection.call(target, methodName, paramTypes, args);
    }

    static Object callAny(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        return FabricReflection.callAny(target, methodName, paramTypes, args);
    }

    static Object callMigrated(Object target, String mc26Method, String legacyMethod,
                               Class<?>[] paramTypes, Object[] args) {
        return FabricReflection.callMigrated(target, mc26Method, legacyMethod, paramTypes, args);
    }

    /** Get a field value from an object. */
    static Object getField(Object target, String fieldName) {
        return FabricReflection.getField(target, fieldName);
    }

    /** Get a static field value from a class. */
    static Object getField(Class<?> cls, String fieldName) {
        return FabricReflection.getField(cls, fieldName);
    }

    /** Find a method on a class (with parent class walking). */
    static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        return FabricReflection.findMethod(cls, name, paramTypes);
    }

    /** Create a Text/Component from a plain string. */
    static Object createText(String message) {
        return FabricReflection.createText(message);
    }

    /** Resolve the Text Component class. */
    static Class<?> resolveTextComponentClass() {
        return FabricReflection.resolveTextComponentClass();
    }

    /** Get the block ID string from a Block object. */
    static String getBlockId(Object block) {
        return FabricReflection.getBlockId(block);
    }

    /** Find a method by parameter signature only (no method name matching). */
    static Method scanMethod(Class<?> cls, Class<?>[] paramTypes, Class<?> returnType) {
        return FabricReflection.scanMethod(cls, paramTypes, returnType);
    }

    /** Call a method by parameter signature and return type. */
    static Object callBySig(Object target, Class<?>[] paramTypes, Object[] args, Class<?> returnType) {
        return FabricReflection.callBySig(target, paramTypes, args, returnType);
    }

    /** Invoke by signature or throw. */
    static void invokeBySigOrThrow(Object target, Class<?>[] paramTypes, Object[] args) {
        FabricReflection.invokeBySigOrThrow(target, paramTypes, args);
    }

    /** Read a UUID from an entity/player. */
    static Object callUuid(Object entity) {
        return FabricReflection.callUuid(entity);
    }

    /** Read a string from a Component/Text object. */
    static String readString(Object component) {
        return FabricReflection.readString(component);
    }

    /** Call getDimension / dimension() on a Level/World object. */
    static Object callDimension(Object world) {
        return FabricReflection.callDimension(world);
    }

    /** Call a static method. */
    static Object callStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        return FabricReflection.callStatic(className, methodName, paramTypes, args);
    }
}
