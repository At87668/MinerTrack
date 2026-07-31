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

package link.star_dust.MinerTrack.neoforge;

import link.star_dust.MinerTrack.fabric.FabricReflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight reflection helpers for accessing NeoForge internals.
 *
 * <p>Delegates to {@link FabricReflection} for Minecraft-internal access.
 * Adds NeoForge-specific helpers for mod container, event bus, and config path
 * access (using {@code net.neoforged.*} packages).
 */
final class NeoForgeReflection {

    private static volatile Object cachedServer;
    static boolean DEBUG_REFLECTION = false;

    private NeoForgeReflection() {}

    static void setDebugReflection(boolean on) { DEBUG_REFLECTION = on; FabricReflection.setDebugReflection(on); }

    static void setCachedServer(Object server) { cachedServer = server; FabricReflection.setCachedServer(server); }
    static Object getServer() { return FabricReflection.getServer(); }

    public static void clearDebugCache() {
        // No-op here; callers use NeoForgeAdapter.clearDebugCache()
    }

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

    static Class<?> neoClass(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException e) { return null; }
    }

    static java.nio.file.Path getConfigDir() {
        try {
            Class<?> fmlPaths = neoClass("net.neoforged.fml.loading.FMLPaths");
            if (fmlPaths == null) return java.nio.file.Path.of("config");
            Field f = fmlPaths.getField("CONFIGDIR");
            Object configDirPath = f.get(null);
            if (configDirPath instanceof java.nio.file.Path) return (java.nio.file.Path) configDirPath;
            return java.nio.file.Path.of("config");
        } catch (Throwable t) {
            return java.nio.file.Path.of("config");
        }
    }

    static String getModVersion(String modId) {
        try {
            Class<?> modListCls = neoClass("net.neoforged.fml.ModList");
            if (modListCls == null) return "unknown";
            Method getMethod = modListCls.getMethod("get");
            Object modList = getMethod.invoke(null);
            Method getContainer = modListCls.getMethod("getModContainerById", String.class);
            Object container = getContainer.invoke(modList, modId);
            if (container == null) return "unknown";
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

    /**
     * Get the NeoForge main event bus ({@code NeoForge.EVENT_BUS}).
     */
    static Object getMainEventBus() {
        try {
            Class<?> neoForge = neoClass("net.neoforged.neoforge.common.NeoForge");
            if (neoForge == null) return null;
            Field ebField = neoForge.getField("EVENT_BUS");
            return ebField.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("rawtypes")
    static void registerEventListener(Object eventBus, Class<?> eventClass,
                                       java.util.function.Consumer<Object> handler) {
        if (eventBus == null || eventClass == null) return;
        try {
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
                        return "NeoForgeEventListener$Proxy";
                    }
                    return null;
                });

            addListener.invoke(eventBus, proxy);
        } catch (Throwable t) {
            if (DEBUG_REFLECTION) System.out.println("[MinerTrack:NeoReflection] Failed to register listener for "
                + eventClass.getName() + ": " + t.getMessage());
        }
    }
}
