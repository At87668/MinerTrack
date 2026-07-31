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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-contained reflection helpers for accessing Forge internals.
 *
 * <p>Forge 1.18+ uses Mojang names at runtime for both class and method names.
 * No intermediary/SRG fallback needed unlike Fabric. This module is fully
 * independent �?zero dependency on {@code link.star_dust.MinerTrack.fabric}.
 */
final class ForgeReflection {

    private static volatile Object cachedServer;
    static boolean DEBUG_REFLECTION = false;

    private ForgeReflection() {}

    static void setDebugReflection(boolean on) { DEBUG_REFLECTION = on; }
    static void setCachedServer(Object server) { cachedServer = server; }
    static Object getServer() { return cachedServer; }

    private static void log(String msg) { System.out.println("[MinerTrack:ForgeReflection] " + msg); }

    // -- sentinel & caches --
    private static final Object NOT_FOUND = new Object();
    private static final ConcurrentHashMap<String, Object> classCache = new ConcurrentHashMap<>(64);
    private static final ConcurrentHashMap<MethodKey, Object> methodCache = new ConcurrentHashMap<>(128);
    private static final ConcurrentHashMap<StaticMethodKey, Object> staticMethodCache = new ConcurrentHashMap<>(64);
    private static final ConcurrentHashMap<FieldKey, Object> fieldCache = new ConcurrentHashMap<>(64);

    private static final class MethodKey {
        final Class<?> cls; final String name; final Class<?>[] paramTypes;
        MethodKey(Class<?> cls, String name, Class<?>[] paramTypes) { this.cls = cls; this.name = name; this.paramTypes = paramTypes; }
        @Override public boolean equals(Object o) { if (!(o instanceof MethodKey)) return false; MethodKey k = (MethodKey) o; return cls == k.cls && name.equals(k.name) && Arrays.equals(paramTypes, k.paramTypes); }
        @Override public int hashCode() { return cls.hashCode() * 31 + name.hashCode() + Arrays.hashCode(paramTypes); }
    }
    private static final class StaticMethodKey {
        final String className; final String methodName; final Class<?>[] paramTypes;
        StaticMethodKey(String className, String methodName, Class<?>[] paramTypes) { this.className = className; this.methodName = methodName; this.paramTypes = paramTypes; }
        @Override public boolean equals(Object o) { if (!(o instanceof StaticMethodKey)) return false; StaticMethodKey k = (StaticMethodKey) o; return className.equals(k.className) && methodName.equals(k.methodName) && Arrays.equals(paramTypes, k.paramTypes); }
        @Override public int hashCode() { return className.hashCode() * 31 + methodName.hashCode() + Arrays.hashCode(paramTypes); }
    }
    private static final class FieldKey {
        final Class<?> cls; final String name;
        FieldKey(Class<?> cls, String name) { this.cls = cls; this.name = name; }
        @Override public boolean equals(Object o) { if (!(o instanceof FieldKey)) return false; FieldKey k = (FieldKey) o; return cls == k.cls && name.equals(k.name); }
        @Override public int hashCode() { return cls.hashCode() * 31 + name.hashCode(); }
    }

    @SuppressWarnings("unchecked") private static <T> T unwrap(Object cached) { return (cached == NOT_FOUND) ? null : (T) cached; }

    static final Class<?>[] NO_PARAMS = new Class<?>[0];
    static final Object[]   NO_ARGS  = new Object[0];

    // ==================================================================
    // Class loading (Forge: Mojang names work directly at runtime)
    // ==================================================================

    static Class<?> forName(String className) {
        if (className == null) return null;
        Object cached = classCache.get(className);
        if (cached != null) return unwrap(cached);
        try { Class<?> cls = Class.forName(className); classCache.put(className, cls); return cls; }
        catch (ClassNotFoundException e) { if (DEBUG_REFLECTION) log("CLS-MISS " + className); classCache.put(className, NOT_FOUND); return null; }
    }

    static Class<?> forgeClass(String name) {
        try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; }
    }

    static Object newInstance(String className, Class<?>[] paramTypes, Object[] args) {
        Class<?> cls = forName(className); if (cls == null) return null;
        try { Constructor<?> c = cls.getDeclaredConstructor(paramTypes); c.setAccessible(true); return c.newInstance(args); }
        catch (Throwable t) { return null; }
    }

    // ==================================================================
    // Method invocation �?instance
    // ==================================================================

    static Object call(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        Method m = findMethodImpl(target.getClass(), methodName, paramTypes);
        if (m == null) return null;
        try { return m.invoke(target, args); } catch (Throwable t) { return null; }
    }

    static Object callAny(Object target, String methodName, Class<?>[] paramTypes, Object[] args) { return call(target, methodName, paramTypes, args); }

    static Object callMigrated(Object target, String mc26Method, String legacyMethod, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        Object r = call(target, mc26Method, paramTypes, args); if (r != null) return r;
        return call(target, legacyMethod, paramTypes, args);
    }

    static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) { return findMethodImpl(cls, name, paramTypes); }

    static void invokeBySigOrThrow(Object target, Class<?>[] paramTypes, Object[] args) {
        if (target == null) throw new IllegalArgumentException("target is null");
        Method m = scanMethod(target.getClass(), paramTypes, null);
        if (m == null) throw new RuntimeException(new NoSuchMethodException(target.getClass().getName() + ".(*sig " + paramTypes.length + " params)"));
        try { m.invoke(target, args); } catch (IllegalAccessException | InvocationTargetException e) { throw new RuntimeException(e); }
    }

    static Object callBySig(Object target, Class<?>[] paramTypes, Object[] args, Class<?> returnType) {
        if (target == null) return null;
        Method m = scanMethod(target.getClass(), paramTypes, returnType);
        if (m == null) return null;
        try { return m.invoke(target, args); } catch (Throwable t) { return null; }
    }

    // ==================================================================
    // Method invocation �?static
    // ==================================================================

    static Object callStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        StaticMethodKey key = new StaticMethodKey(className, methodName, paramTypes);
        Object cached = staticMethodCache.get(key);
        if (cached != null) { Method m = unwrap(cached); if (m == null) return null; try { return m.invoke(null, args); } catch (Throwable t) { return null; } }
        Class<?> cls = forName(className);
        if (cls == null) { staticMethodCache.put(key, NOT_FOUND); return null; }
        try { Method m = cls.getDeclaredMethod(methodName, paramTypes); m.setAccessible(true); Object r = m.invoke(null, args); staticMethodCache.put(key, m); return r; }
        catch (NoSuchMethodException e) { try { Method m = cls.getMethod(methodName, paramTypes); Object r = m.invoke(null, args); staticMethodCache.put(key, m); return r; } catch (Throwable t2) {} }
        catch (Throwable e) { staticMethodCache.put(key, NOT_FOUND); return null; }
        for (Method candidate : cls.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) continue;
            if (candidate.getParameterCount() != paramTypes.length) continue;
            boolean match = true; for (int i = 0; i < paramTypes.length; i++) { if (!candidate.getParameterTypes()[i].isAssignableFrom(paramTypes[i])) { match = false; break; } }
            if (match) { try { candidate.setAccessible(true); Object r = candidate.invoke(null, args); staticMethodCache.put(key, candidate); return r; } catch (Throwable t) {} }
        }
        staticMethodCache.put(key, NOT_FOUND); return null;
    }

    // ==================================================================
    // Field access
    // ==================================================================

    @SuppressWarnings("unchecked")
    static <T> T getField(Object target, String fieldName) {
        try {
            Class<?> cls = (target instanceof Class) ? (Class<?>) target : target.getClass();
            Field f = findField(cls, fieldName); if (f == null) return null;
            f.setAccessible(true); Object owner = (target instanceof Class) ? null : target;
            return (T) f.get(owner);
        } catch (Throwable t) { return null; }
    }

    // ==================================================================
    // Text/Component
    // ==================================================================

    private static volatile Class<?> cachedTextClass; private static volatile boolean cachedTextClassResolved;

    static Class<?> resolveTextComponentClass() {
        if (cachedTextClassResolved) return cachedTextClass;
        cachedTextClass = forName("net.minecraft.network.chat.Component");
        cachedTextClassResolved = true; return cachedTextClass;
    }

    static Object createText(String message) {
        if (message == null) return null;
        Object r = callStatic("net.minecraft.network.chat.Component", "literal", new Class<?>[]{String.class}, new Object[]{message});
        if (r != null) return r;
        Class<?> tcCls = forName("net.minecraft.network.chat.TextComponent");
        if (tcCls != null) { try { Constructor<?> ctor = tcCls.getDeclaredConstructor(String.class); ctor.setAccessible(true); return ctor.newInstance(message); } catch (Throwable t) {} }
        return null;
    }

    // ==================================================================
    // Version-aware helpers
    // ==================================================================

    static Object callUuid(Object target) {
        if (target == null) return null;
        Object r = call(target, "getUUID", NO_PARAMS, NO_ARGS); if (r != null) return r;
        return call(target, "getUuid", NO_PARAMS, NO_ARGS);
    }

    static Object callDimension(Object world) {
        if (world == null) return null;
        Object r = call(world, "dimension", NO_PARAMS, NO_ARGS); if (r != null) return r;
        return call(world, "getRegistryKey", NO_PARAMS, NO_ARGS);
    }

    static String readString(Object source) {
        if (source == null) return null;
        if (source instanceof String) return (String) source;
        Object s = call(source, "getString", NO_PARAMS, NO_ARGS); if (s instanceof String) return (String) s;
        String str = source.toString();
        if (str.startsWith("literal{") && str.endsWith("}")) return str.substring("literal{".length(), str.length() - 1);
        if (str.startsWith("literal(") && str.endsWith(")")) return str.substring("literal(".length(), str.length() - 1);
        return str;
    }

    static String getBlockId(Object block) {
        if (block == null) return null;
        String s = block.toString(); int brace = s.indexOf('{'), close = s.indexOf('}');
        if (brace >= 0 && close > brace) return s.substring(brace + 1, close);
        Object holder = call(block, "builtInRegistryHolder", NO_PARAMS, NO_ARGS);
        if (holder != null) { try { Object key = holder.getClass().getMethod("getKey").invoke(holder); if (key != null) { Object loc = call(key, "getValue", NO_PARAMS, NO_ARGS); if (loc != null) return readString(loc); } } catch (Throwable t) {} }
        Class<?> bir = forName("net.minecraft.core.registries.BuiltInRegistries");
        if (bir != null) { Object reg = getField(bir, "BLOCK"); if (reg != null) { Object id = call(reg, "getKey", new Class<?>[]{Object.class}, new Object[]{block}); if (id != null) return readString(id); } }
        Class<?> regCls = forName("net.minecraft.core.Registry");
        if (regCls != null) { Object reg = getField(regCls, "BLOCK"); if (reg != null) { Object id = call(reg, "getKey", new Class<?>[]{Object.class}, new Object[]{block}); if (id != null) return readString(id); } }
        return null;
    }

    // ==================================================================
    // Forge config directory
    // ==================================================================

    static java.nio.file.Path getConfigDir() {
        try { Class<?> fmlPaths = forgeClass("net.minecraftforge.fml.loading.FMLPaths"); if (fmlPaths == null) return java.nio.file.Path.of("config"); Field f = fmlPaths.getField("CONFIGDIR"); Object v = f.get(null); return v instanceof java.nio.file.Path ? (java.nio.file.Path) v : java.nio.file.Path.of("config"); }
        catch (Throwable t) { return java.nio.file.Path.of("config"); }
    }

    static String getModVersion(String modId) {
        try {
            Class<?> modListCls = forgeClass("net.minecraftforge.fml.ModList"); if (modListCls == null) return "unknown";
            Method getMethod = modListCls.getMethod("get"); Object modList = getMethod.invoke(null);
            Method getContainer = modListCls.getMethod("getModContainerById", String.class); Object container = getContainer.invoke(modList, modId);
            if (container == null) return "unknown";
            Method optGet = container.getClass().getMethod("get"); Object c = optGet.invoke(container);
            Method getModInfo = c.getClass().getMethod("getModInfo"); Object mi = getModInfo.invoke(c);
            Method getVersion = mi.getClass().getMethod("getVersion"); Object v = getVersion.invoke(mi);
            return v != null ? v.toString() : "unknown";
        } catch (Throwable t) { return "unknown"; }
    }

    // ==================================================================
    // Forge event bus
    // ==================================================================

    static Object getMainEventBus() {
        try { Class<?> mcForge = forgeClass("net.minecraftforge.common.MinecraftForge"); if (mcForge == null) return null; Field eb = mcForge.getField("EVENT_BUS"); return eb.get(null); }
        catch (Throwable t) { return null; }
    }

    @SuppressWarnings("rawtypes")
    static void registerEventListener(Object eventBus, Class<?> eventClass, java.util.function.Consumer<Object> handler) {
        if (eventBus == null) return;
        try {
            // Try addListener(EventPriority, boolean, Class<T>, Consumer<T>) first.
            // Bare Consumer.class proxies lose generic type info, causing Forge to
            // fail with "Failed to resolve handler". Passing the eventClass explicitly
            // lets Forge match the handler to the correct event type.
            Class<?> consumerCls = java.util.function.Consumer.class;
            Object proxy = Proxy.newProxyInstance(consumerCls.getClassLoader(), new Class<?>[]{consumerCls}, (proxyObj, method, args) -> {
                if ("accept".equals(method.getName()) && args != null && args.length == 1) { handler.accept(args[0]); }
                else if ("equals".equals(method.getName())) { return proxyObj == args[0]; }
                else if ("hashCode".equals(method.getName())) { return System.identityHashCode(proxyObj); }
                else if ("toString".equals(method.getName())) { return "ForgeEventListener$Proxy"; }
                return null;
            });
            // IEventBus.addListener(EventPriority.NORMAL, false, Class<T>, Consumer<T>)
            try {
                Class<?> priorityCls = Class.forName("net.minecraftforge.eventbus.api.EventPriority");
                Object normal = priorityCls.getField("NORMAL").get(null);
                Method addListenerExplicit = eventBus.getClass().getMethod("addListener",
                    priorityCls, boolean.class, Class.class, consumerCls);
                addListenerExplicit.invoke(eventBus, normal, false, eventClass, proxy);
                return;
            } catch (Throwable t) { /* fall through to generic overload */ }
            // Fallback: IEventBus.addListener(Consumer<T>) — only works if
            // the JVM retains enough generic info (some Forge builds do).
            try {
                Method addListener = eventBus.getClass().getMethod("addListener", consumerCls);
                addListener.invoke(eventBus, proxy);
            } catch (Throwable t) {
                if (DEBUG_REFLECTION) log("Failed to register listener: " + t.getMessage());
            }
        } catch (Throwable t) { if (DEBUG_REFLECTION) log("Failed to register listener: " + t.getMessage()); }
    }

    // ==================================================================
    // Internal method/field lookup
    // ==================================================================

    private static Method findMethodImpl(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null || name == null) return null;
        MethodKey key = new MethodKey(cls, name, paramTypes);
        Object cached = methodCache.get(key); if (cached != null) return unwrap(cached);
        Method m = tryMethod(cls, name, paramTypes);
        if (m != null) { methodCache.put(key, m); return m; }
        m = scanMethodNamed(cls, name, paramTypes);
        if (m != null) { methodCache.put(key, m); return m; }
        m = scanMethod(cls, paramTypes, null);
        if (m != null) { methodCache.put(key, m); return m; }
        Class<?> sup = cls.getSuperclass();
        if (sup != null && sup != Object.class) { m = findMethodImpl(sup, name, paramTypes); if (m != null) { methodCache.put(key, m); return m; } }
        if (DEBUG_REFLECTION) log("M-MISS " + cls.getName() + "." + name);
        methodCache.put(key, NOT_FOUND); return null;
    }

    private static Method tryMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        try { Method m = cls.getDeclaredMethod(name, paramTypes); m.setAccessible(true); return m; } catch (NoSuchMethodException e) {}
        try { Method m = cls.getMethod(name, paramTypes); m.setAccessible(true); return m; } catch (NoSuchMethodException e) {}
        return null;
    }

    private static Method scanMethodNamed(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (name == null) return null;
        for (Method c : cls.getDeclaredMethods()) { if (c.getName().equals(name) && ps(c, paramTypes) && ro(c, null)) { c.setAccessible(true); return c; } }
        for (Method c : cls.getMethods()) { if (c.getDeclaringClass() == Object.class) continue; if (c.getName().equals(name) && ps(c, paramTypes) && ro(c, null)) { c.setAccessible(true); return c; } }
        return null;
    }

    static Method scanMethod(Class<?> cls, Class<?>[] paramTypes, Class<?> returnType) {
        for (Method c : cls.getDeclaredMethods()) { if (ps(c, paramTypes) && ro(c, returnType)) { c.setAccessible(true); return c; } }
        for (Method c : cls.getMethods()) { if (c.getDeclaringClass() == Object.class) continue; if (ps(c, paramTypes) && ro(c, returnType)) { c.setAccessible(true); return c; } }
        return null;
    }

    private static boolean ps(Method m, Class<?>[] pts) {
        Class<?>[] a = m.getParameterTypes(); if (a.length != pts.length) return false;
        for (int i = 0; i < a.length; i++) { if (!a[i].isAssignableFrom(pts[i])) return false; }
        return true;
    }

    private static boolean ro(Method m, Class<?> rt) {
        if (rt == null) return true;
        Class<?> r = m.getReturnType();
        if (rt == void.class) return r == void.class; if (r == void.class) return false;
        if (rt.isPrimitive()) return r == rt;
        if (r.isPrimitive()) { if (rt == Integer.class) return r == int.class; if (rt == Boolean.class) return r == boolean.class; if (rt == Long.class) return r == long.class; if (rt == Double.class) return r == double.class; if (rt == Float.class) return r == float.class; return false; }
        return rt.isAssignableFrom(r);
    }

    private static Field findField(Class<?> cls, String name) {
        if (cls == null || name == null) return null;
        FieldKey key = new FieldKey(cls, name); Object cached = fieldCache.get(key); if (cached != null) return unwrap(cached);
        Field f = tryField(cls, name); if (f != null) { fieldCache.put(key, f); return f; }
        Class<?> sup = cls.getSuperclass(); if (sup != null && sup != Object.class) { f = findField(sup, name); if (f != null) { fieldCache.put(key, f); return f; } }
        fieldCache.put(key, NOT_FOUND); return null;
    }

    private static Field tryField(Class<?> cls, String name) {
        try { return cls.getDeclaredField(name); } catch (NoSuchFieldException e) {}
        try { return cls.getField(name); } catch (NoSuchFieldException e) {}
        return null;
    }
}
