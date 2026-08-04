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

import link.star_dust.MinerTrack.core.FastReflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-contained reflection helpers for Forge. Mirrors {@code FabricReflection}
 * structurally — delegates name resolution and redirects to
 * {@link ForgeReflectionConstants}.
 *
 * <p>Forge 1.18+ uses Mojang names at runtime. No intermediary fallback needed.
 */
final class ForgeReflection {

    private static volatile Object cachedServer;
    static boolean DEBUG_REFLECTION = false;

    private ForgeReflection() {}

    static void setDebugReflection(boolean on) { DEBUG_REFLECTION = on; }
    static void setCachedServer(Object server) { cachedServer = server; }
    static Object getServer() { return cachedServer; }

    private static void log(String msg) { System.out.println("[MinerTrack:ForgeReflection] " + msg); }

    private static final Object NOT_FOUND = new Object();
    private static final ConcurrentHashMap<String, Object> classCache = new ConcurrentHashMap<>(64);
    private static final ConcurrentHashMap<MethodKey, Object> methodCache = new ConcurrentHashMap<>(128);
    private static final ConcurrentHashMap<StaticMethodKey, Object> staticMethodCache = new ConcurrentHashMap<>(64);
    private static final ConcurrentHashMap<FieldKey, Object> fieldCache = new ConcurrentHashMap<>(64);

    private static final class MethodKey { final Class<?> cls; final String name; final Class<?>[] paramTypes; MethodKey(Class<?> cls, String n, Class<?>[] p) { this.cls = cls; this.name = n; this.paramTypes = p; } @Override public boolean equals(Object o) { if (!(o instanceof MethodKey)) return false; MethodKey k = (MethodKey) o; return cls == k.cls && name.equals(k.name) && Arrays.equals(paramTypes, k.paramTypes); } @Override public int hashCode() { return cls.hashCode() * 31 + name.hashCode() + Arrays.hashCode(paramTypes); } }
    private static final class StaticMethodKey { final String className; final String methodName; final Class<?>[] paramTypes; StaticMethodKey(String cn, String mn, Class<?>[] p) { this.className = cn; this.methodName = mn; this.paramTypes = p; } @Override public boolean equals(Object o) { if (!(o instanceof StaticMethodKey)) return false; StaticMethodKey k = (StaticMethodKey) o; return className.equals(k.className) && methodName.equals(k.methodName) && Arrays.equals(paramTypes, k.paramTypes); } @Override public int hashCode() { return className.hashCode() * 31 + methodName.hashCode() + Arrays.hashCode(paramTypes); } }
    private static final class FieldKey { final Class<?> cls; final String name; FieldKey(Class<?> cls, String n) { this.cls = cls; this.name = n; } @Override public boolean equals(Object o) { if (!(o instanceof FieldKey)) return false; FieldKey k = (FieldKey) o; return cls == k.cls && name.equals(k.name); } @Override public int hashCode() { return cls.hashCode() * 31 + name.hashCode(); } }

    @SuppressWarnings("unchecked") private static <T> T unwrap(Object c) { return (c == NOT_FOUND) ? null : (T) c; }

    static final Class<?>[] NO_PARAMS = new Class<?>[0];
    static final Object[]   NO_ARGS  = new Object[0];

    // ==================================================================
    // Class loading
    // ==================================================================

    static Class<?> forName(String className) {
        if (className == null) return null;
        Object cached = classCache.get(className);
        if (cached != null) return unwrap(cached);
        // 1. Try the mojang/named name directly (runtime name on Mojang-mapped Forge)
        Class<?> cls = tryLoad(className);
        if (cls != null) { classCache.put(className, cls); return cls; }
        // 2. Fall back to the runtime class name from the constants table
        //    (mirrors FabricReflectionConstants.toIntermediaryClass)
        String alt = ForgeReflectionConstants.toRuntimeClass(className);
        if (alt != null && !alt.equals(className)) {
            cls = tryLoad(alt);
            if (cls != null) { classCache.put(className, cls); return cls; }
        }
        if (DEBUG_REFLECTION) log("CLS-MISS " + className);
        classCache.put(className, NOT_FOUND); return null;
    }

    private static Class<?> tryLoad(String name) {
        if (name == null) return null;
        try { return Class.forName(name); } catch (ClassNotFoundException e) {}
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) { try { return Class.forName(name, false, ctx); } catch (Throwable t) {} }
        return null;
    }

    static Class<?> forgeClass(String name) {
        if (name == null) return null;
        // Try multiple classloaders. In Forge's mod-loading environment the
        // caller's classloader may not see Forge's own classes, so fall back to
        // the thread context classloader and the classloader that loaded the
        // Forge event bus (which is guaranteed to see Forge classes).
        Class<?> cls = tryLoad(name);
        if (cls != null) return cls;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) { cls = tryLoadWith(ctx, name); if (cls != null) return cls; }
        Object bus = getMainEventBus();
        if (bus != null) {
            ClassLoader bl = bus.getClass().getClassLoader();
            if (bl != null) { cls = tryLoadWith(bl, name); if (cls != null) return cls; }
        }
        return null;
    }

    private static Class<?> tryLoadWith(ClassLoader cl, String name) {
        try { return Class.forName(name, false, cl); } catch (Throwable t) { return null; }
    }

    static Object newInstance(String className, Class<?>[] paramTypes, Object[] args) {
        Class<?> cls = forName(className); if (cls == null) return null;
        try { Constructor<?> c = cls.getDeclaredConstructor(paramTypes); c.setAccessible(true); return c.newInstance(args); } catch (Throwable t) { return null; }
    }

    // ==================================================================
    // Method invocation
    // ==================================================================

    public static Object call(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        Method m = findMethodImpl(target.getClass(), methodName, paramTypes);
        if (m == null) return null;
        return FastReflection.invoke(m, target, args);
    }

    public static Object callAny(Object target, String methodName, Class<?>[] paramTypes, Object[] args) { return call(target, methodName, paramTypes, args); }

    public static Object callMigrated(Object target, String mc26Method, String legacyMethod, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        Object r = call(target, mc26Method, paramTypes, args); if (r != null) return r;
        return call(target, legacyMethod, paramTypes, args);
    }

    public static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) { return findMethodImpl(cls, name, paramTypes); }

    public static void invokeBySigOrThrow(Object target, Class<?>[] paramTypes, Object[] args) {
        if (target == null) throw new IllegalArgumentException("target is null");
        Method m = scanMethod(target.getClass(), paramTypes, null);
        if (m == null) throw new RuntimeException(new NoSuchMethodException(target.getClass().getName() + ".(*sig " + paramTypes.length + " params)"));
        try { m.invoke(target, args); } catch (IllegalAccessException | InvocationTargetException e) { throw new RuntimeException(e); }
    }

    public static Object callBySig(Object target, Class<?>[] paramTypes, Object[] args, Class<?> returnType) {
        if (target == null) return null;
        Method m = scanMethod(target.getClass(), paramTypes, returnType);
        if (m == null) return null;
        return FastReflection.invoke(m, target, args);
    }

    // ==================================================================
    // Static method invocation
    // ==================================================================

    public static Object callStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        StaticMethodKey key = new StaticMethodKey(className, methodName, paramTypes);
        Object cached = staticMethodCache.get(key);
        if (cached != null) { Method m = unwrap(cached); if (m == null) return null; return FastReflection.invokeStatic(m, args); }
        Class<?> cls = forName(className);
        if (cls == null) { staticMethodCache.put(key, NOT_FOUND); return null; }
        String r = ForgeReflectionConstants.redirectMethod(methodName);
        try { Method m = cls.getDeclaredMethod(r, paramTypes); m.setAccessible(true); Object res = FastReflection.invokeStatic(m, args); staticMethodCache.put(key, m); return res; }
        catch (NoSuchMethodException e) { try { Method m = cls.getMethod(r, paramTypes); m.setAccessible(true); Object res = FastReflection.invokeStatic(m, args); staticMethodCache.put(key, m); return res; } catch (Throwable t2) {} }
        catch (Throwable e) { staticMethodCache.put(key, NOT_FOUND); return null; }
        if (!r.equals(methodName)) { try { Method m = cls.getDeclaredMethod(methodName, paramTypes); m.setAccessible(true); Object res = FastReflection.invokeStatic(m, args); staticMethodCache.put(key, m); return res; } catch (Throwable t) {} try { Method m = cls.getMethod(methodName, paramTypes); m.setAccessible(true); Object res = FastReflection.invokeStatic(m, args); staticMethodCache.put(key, m); return res; } catch (Throwable t) {} }
        for (Method candidate : cls.getDeclaredMethods()) { if (!java.lang.reflect.Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterCount() != paramTypes.length) continue; boolean match = true; for (int i = 0; i < paramTypes.length; i++) { if (!candidate.getParameterTypes()[i].isAssignableFrom(paramTypes[i])) { match = false; break; } } if (match) { try { candidate.setAccessible(true); Object res = FastReflection.invokeStatic(candidate, args); staticMethodCache.put(key, candidate); return res; } catch (Throwable t) {} } }
        if (DEBUG_REFLECTION) log("STATIC-MISS " + className + "." + methodName + " (resolved=" + r + ")");
        staticMethodCache.put(key, NOT_FOUND); return null;
    }

    // ==================================================================
    // Field access
    // ==================================================================

    @SuppressWarnings("unchecked")
    public static <T> T getField(Object target, String fieldName) {
        Class<?> cls = (target instanceof Class) ? (Class<?>) target : target.getClass();
        Field f = findField(cls, fieldName);
        if (f == null) return null;
        f.setAccessible(true);
        Object owner = (target instanceof Class) ? null : target;
        return (T) FastReflection.get(f, owner);
    }

    // ==================================================================
    // Text/Component
    // ==================================================================

    private static volatile Class<?> cachedTextClass; private static volatile boolean cachedTextClassResolved;

    public static Class<?> resolveTextComponentClass() { if (cachedTextClassResolved) return cachedTextClass; cachedTextClass = forName(ForgeReflectionConstants.CLS_COMPONENT); cachedTextClassResolved = true; return cachedTextClass; }

    public static Object createText(String message) {
        if (message == null) return null;
        Object r = callStatic(ForgeReflectionConstants.CLS_COMPONENT, ForgeReflectionConstants.M_COMPONENT_LITERAL, new Class<?>[]{String.class}, new Object[]{message});
        if (r != null) return r;
        Class<?> tcCls = forName(ForgeReflectionConstants.CLS_TEXT_COMPONENT);
        if (tcCls != null) { try { Constructor<?> ctor = tcCls.getDeclaredConstructor(String.class); ctor.setAccessible(true); return ctor.newInstance(message); } catch (Throwable t) {} }
        return null;
    }

    // ==================================================================
    // Version-aware helpers
    // ==================================================================

    public static Object callUuid(Object target) { if (target == null) return null; Object r = call(target, "getUUID", NO_PARAMS, NO_ARGS); if (r != null) return r; return call(target, "getUuid", NO_PARAMS, NO_ARGS); }

    public static Object callDimension(Object world) { if (world == null) return null; Object r = call(world, "dimension", NO_PARAMS, NO_ARGS); if (r != null) return r; return call(world, "getRegistryKey", NO_PARAMS, NO_ARGS); }

    public static String readString(Object source) {
        if (source == null) return null; if (source instanceof String) return (String) source;
        Object s = call(source, "getString", NO_PARAMS, NO_ARGS); if (s instanceof String) return (String) s;
        String str = source.toString(); if (str.startsWith("literal{") && str.endsWith("}")) return str.substring("literal{".length(), str.length() - 1); if (str.startsWith("literal(") && str.endsWith(")")) return str.substring("literal(".length(), str.length() - 1); return str;
    }

    public static String getBlockId(Object block) {
        if (block == null) return null;
        String s = block.toString(); int brace = s.indexOf('{'), close = s.indexOf('}'); if (brace >= 0 && close > brace) return s.substring(brace + 1, close);
        Object holder = call(block, "builtInRegistryHolder", NO_PARAMS, NO_ARGS);
        if (holder != null) { try { Method getKey = holder.getClass().getMethod("getKey"); Object key = FastReflection.invoke(getKey, holder, NO_ARGS); if (key != null) { Object loc = call(key, "getValue", NO_PARAMS, NO_ARGS); if (loc != null) return readString(loc); } } catch (Throwable t) {} }
        Class<?> bir = forName(ForgeReflectionConstants.CLS_BUILT_IN_REGISTRIES); if (bir != null) { Object reg = getField(bir, ForgeReflectionConstants.F_BUILTIN_BLOCK); if (reg != null) { Object id = call(reg, "getKey", new Class<?>[]{Object.class}, new Object[]{block}); if (id != null) return readString(id); } }
        Class<?> regCls = forName(ForgeReflectionConstants.CLS_REGISTRY); if (regCls != null) { Object reg = getField(regCls, ForgeReflectionConstants.F_REGISTRY_BLOCK); if (reg != null) { Object id = call(reg, "getKey", new Class<?>[]{Object.class}, new Object[]{block}); if (id != null) return readString(id); } }
        return null;
    }

    // ==================================================================
    // Forge-specific
    // ==================================================================

    static java.nio.file.Path getConfigDir() { try { Class<?> fmlPaths = forgeClass("net.minecraftforge.fml.loading.FMLPaths"); if (fmlPaths == null) return java.nio.file.Path.of("config"); Field f = fmlPaths.getField("CONFIGDIR"); Object v = f.get(null); return v instanceof java.nio.file.Path ? (java.nio.file.Path) v : java.nio.file.Path.of("config"); } catch (Throwable t) { return java.nio.file.Path.of("config"); } }

    static String getModVersion(String modId) { try { Class<?> modListCls = forgeClass("net.minecraftforge.fml.ModList"); if (modListCls == null) return "unknown"; Method get = modListCls.getMethod("get"); Object ml = get.invoke(null); Method gc = modListCls.getMethod("getModContainerById", String.class); Object co = gc.invoke(ml, modId); if (co == null) return "unknown"; Method og = co.getClass().getMethod("get"); Object ac = og.invoke(co); Method gmi = ac.getClass().getMethod("getModInfo"); Object mi = gmi.invoke(ac); Method gv = mi.getClass().getMethod("getVersion"); Object v = gv.invoke(mi); return v != null ? v.toString() : "unknown"; } catch (Throwable t) { return "unknown"; } }

    static Object getMainEventBus() { try { Class<?> mcForge = forgeClass("net.minecraftforge.common.MinecraftForge"); if (mcForge == null) return null; Field eb = mcForge.getField("EVENT_BUS"); return eb.get(null); } catch (Throwable t) { return null; } }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void registerEventListener(Object eventBus, Class<?> eventClass, java.util.function.Consumer<Object> handler) {
        if (eventBus == null) return;
        // If the event class could not be resolved, there is nothing to
        // register. Callers that probe multiple candidate class names will
        // only pass a non-null class here.
        if (eventClass == null) return;
        try {
            // Forge's IEventBus.addListener(Consumer<T>) resolves the event type
            // from the Consumer's generic signature via TypeResolver. A dynamic
            // Proxy<Consumer> (or a raw Consumer) has NO generic signature, so
            // Forge fails with "Failed to resolve handler for ...".
            //
            // Fix: use the 4-arg overload
            //   addListener(EventPriority, boolean, Class<T>, Consumer<T>)
            // which takes the event type EXPLICITLY and never calls TypeResolver.
            // This is exactly what the API docs recommend when the generic type
            // cannot be resolved.
            Class<?> priorityCls = Class.forName("net.minecraftforge.eventbus.api.EventPriority");
            Object normal = priorityCls.getField("NORMAL").get(null);
            Class<?> consumerCls = java.util.function.Consumer.class;
            Method al = findAddListener(eventBus.getClass(), 4);
            if (al != null) {
                al.invoke(eventBus, normal, false, eventClass, handler);
                return;
            }
            log("4-arg addListener not found on " + eventBus.getClass().getName());
            // Fallback: 2-arg addListener(Consumer<T>) — only works if the
            // consumer's generic signature is resolvable. Use a concrete
            // Consumer<eventClass> subclass so TypeResolver can read it.
            Object typed = makeConsumer(eventClass, handler);
            Method al2 = eventBus.getClass().getMethod("addListener", consumerCls);
            al2.invoke(eventBus, typed);
        } catch (Throwable t) { log("Failed to register listener: " + t); }
    }

    /**
     * Find an {@code addListener} method on the bus class with the given number
     * of parameters. For the 4-arg overload we additionally require the 3rd
     * parameter to be a {@link Class} and the 4th to be a {@link Consumer}.
     * Scanning by name/count is more robust than {@code getMethod} against
     * generic-erasure signature variations across Forge versions.
     */
    private static Method findAddListener(Class<?> busClass, int paramCount) {
        for (Method m : busClass.getMethods()) {
            if (!"addListener".equals(m.getName())) continue;
            if (m.getParameterCount() != paramCount) continue;
            if (paramCount == 4) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts[2] != Class.class || pts[3] != java.util.function.Consumer.class) continue;
            }
            return m;
        }
        return null;
    }

    /**
     * Build a concrete {@code Consumer<eventClass>} whose generic signature is
     * present in bytecode so Forge's TypeResolver can resolve the event type.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> java.util.function.Consumer<T> makeConsumer(Class<T> eventClass, java.util.function.Consumer<Object> handler) {
        return new java.util.function.Consumer<T>() {
            @Override public void accept(T e) { handler.accept(e); }
        };
    }

    // ==================================================================
    // Internal method/field lookup (via Constants.redirect*)
    // ==================================================================

    private static Method findMethodImpl(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null || name == null) return null;
        MethodKey key = new MethodKey(cls, name, paramTypes); Object cached = methodCache.get(key); if (cached != null) return unwrap(cached);
        String resolved = ForgeReflectionConstants.redirectMethod(name);
        Method m = tryMethod(cls, resolved, paramTypes); if (m != null) { methodCache.put(key, m); return m; }
        if (!resolved.equals(name)) { m = tryMethod(cls, name, paramTypes); if (m != null) { methodCache.put(key, m); return m; } }
        m = scanMethodNamed(cls, resolved, paramTypes); if (m != null) { methodCache.put(key, m); return m; }
        if (!resolved.equals(name)) { m = scanMethodNamed(cls, name, paramTypes); if (m != null) { methodCache.put(key, m); return m; } }
        // Parameter-type scan fallback (mirrors Fabric). Only reached when the
        // name-based lookups above fail. METHOD_REDIRECT now correctly maps the
        // common names (getPlayer→m_11259_, getEntity→m_81373_), so this scan
        // is safe and helps classes whose methods have non-obvious names.
        m = scanMethod(cls, paramTypes, null); if (m != null) { methodCache.put(key, m); return m; }
        Class<?> sup = cls.getSuperclass(); if (sup != null && sup != Object.class) { m = findMethodImpl(sup, name, paramTypes); if (m != null) { methodCache.put(key, m); return m; } }
        if (DEBUG_REFLECTION) log("M-MISS " + cls.getName() + "." + name + " (resolved=" + resolved + ")");
        methodCache.put(key, NOT_FOUND); return null;
    }

    private static Method tryMethod(Class<?> cls, String name, Class<?>[] paramTypes) { try { Method m = cls.getDeclaredMethod(name, paramTypes); m.setAccessible(true); return m; } catch (NoSuchMethodException e) {} try { Method m = cls.getMethod(name, paramTypes); m.setAccessible(true); return m; } catch (NoSuchMethodException e) {} return null; }

    private static Method scanMethodNamed(Class<?> cls, String name, Class<?>[] paramTypes) { if (name == null) return null; for (Method c : cls.getDeclaredMethods()) { if (c.getName().equals(name) && ps(c, paramTypes) && ro(c, null)) { c.setAccessible(true); return c; } } for (Method c : cls.getMethods()) { if (c.getDeclaringClass() == Object.class) continue; if (c.getName().equals(name) && ps(c, paramTypes) && ro(c, null)) { c.setAccessible(true); return c; } } return null; }

    static Method scanMethod(Class<?> cls, Class<?>[] paramTypes, Class<?> returnType) { for (Method c : cls.getDeclaredMethods()) { if (ps(c, paramTypes) && ro(c, returnType)) { c.setAccessible(true); return c; } } for (Method c : cls.getMethods()) { if (c.getDeclaringClass() == Object.class) continue; if (ps(c, paramTypes) && ro(c, returnType)) { c.setAccessible(true); return c; } } return null; }

    private static boolean ps(Method m, Class<?>[] pts) { Class<?>[] a = m.getParameterTypes(); if (a.length != pts.length) return false; for (int i = 0; i < a.length; i++) { if (!a[i].isAssignableFrom(pts[i])) return false; } return true; }
    private static boolean ro(Method m, Class<?> rt) { if (rt == null) return true; Class<?> r = m.getReturnType(); if (rt == void.class) return r == void.class; if (r == void.class) return false; if (rt.isPrimitive()) return r == rt; if (r.isPrimitive()) { if (rt == Integer.class) return r == int.class; if (rt == Boolean.class) return r == boolean.class; if (rt == Long.class) return r == long.class; if (rt == Double.class) return r == double.class; if (rt == Float.class) return r == float.class; return false; } return rt.isAssignableFrom(r); }

    private static Field findField(Class<?> cls, String name) { if (cls == null || name == null) return null; FieldKey key = new FieldKey(cls, name); Object cached = fieldCache.get(key); if (cached != null) return unwrap(cached); String resolved = ForgeReflectionConstants.redirectField(name); Field f = tryField(cls, resolved); if (f != null) { fieldCache.put(key, f); return f; } if (!resolved.equals(name)) { f = tryField(cls, name); if (f != null) { fieldCache.put(key, f); return f; } } Class<?> sup = cls.getSuperclass(); if (sup != null && sup != Object.class) { f = findField(sup, name); if (f != null) { fieldCache.put(key, f); return f; } } fieldCache.put(key, NOT_FOUND); return null; }
    private static Field tryField(Class<?> cls, String name) { try { return cls.getDeclaredField(name); } catch (NoSuchFieldException e) {} try { return cls.getField(name); } catch (NoSuchFieldException e) {} return null; }
}
