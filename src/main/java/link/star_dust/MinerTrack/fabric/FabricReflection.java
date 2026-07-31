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

package link.star_dust.MinerTrack.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight reflection helpers for accessing Minecraft internals.
 *
 * <p>Uses {@link FabricReflectionConstants} to redirect bare mojang method
 * and field names to their runtime form (intermediary on 1.18â€?.21.x,
 * named/mojang on MC&nbsp;26+ and in dev).
 */
final class FabricReflection {

    private static volatile Object cachedServer;
    static boolean DEBUG_REFLECTION = false;

    private FabricReflection() {}

    static void setDebugReflection(boolean on) { DEBUG_REFLECTION = on; }

    /** Cache the dedicated-server instance obtained during lifecycle init. */
    static void setCachedServer(Object server) { cachedServer = server; }
    /** Return the cached server instance. */
    static Object getServer() { return cachedServer; }

    private static void log(String msg) { System.out.println("[MinerTrack:Reflection] " + msg); }

    // ==================================================================
    // Reflection caches â€?avoid repeated getDeclaredMethod/Field scans
    // ==================================================================

    /** Sentinel for "not found" results (ConcurrentHashMap forbids null values). */
    private static final Object NOT_FOUND = new Object();

    /** Cache: className â†?Class<?> (or NOT_FOUND). */
    private static final ConcurrentHashMap<String, Object> classCache = new ConcurrentHashMap<>(64);

    /** Cache: (Class, methodName, paramTypes) â†?Method (or NOT_FOUND). */
    private static final ConcurrentHashMap<MethodKey, Object> methodCache = new ConcurrentHashMap<>(128);

    /** Cache: (className, methodName, paramTypes) â†?Method (or NOT_FOUND). */
    private static final ConcurrentHashMap<StaticMethodKey, Object> staticMethodCache = new ConcurrentHashMap<>(64);

    /** Cache: (Class, fieldName) â†?Field (or NOT_FOUND). */
    private static final ConcurrentHashMap<FieldKey, Object> fieldCache = new ConcurrentHashMap<>(64);

    // -- Key types -----------------------------------------------------

    private static final class MethodKey {
        final Class<?> cls;
        final String name;
        final Class<?>[] paramTypes;
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

    private static final class StaticMethodKey {
        final String className;
        final String methodName;
        final Class<?>[] paramTypes;
        StaticMethodKey(String className, String methodName, Class<?>[] paramTypes) {
            this.className = className; this.methodName = methodName; this.paramTypes = paramTypes;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof StaticMethodKey)) return false;
            StaticMethodKey k = (StaticMethodKey) o;
            return className.equals(k.className) && methodName.equals(k.methodName)
                && Arrays.equals(paramTypes, k.paramTypes);
        }
        @Override public int hashCode() {
            return className.hashCode() * 31 + methodName.hashCode() + Arrays.hashCode(paramTypes);
        }
    }

    private static final class FieldKey {
        final Class<?> cls;
        final String name;
        FieldKey(Class<?> cls, String name) { this.cls = cls; this.name = name; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof FieldKey)) return false;
            FieldKey k = (FieldKey) o;
            return cls == k.cls && name.equals(k.name);
        }
        @Override public int hashCode() { return cls.hashCode() * 31 + name.hashCode(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T unwrap(Object cached) {
        return (cached == NOT_FOUND) ? null : (T) cached;
    }

    // -- reusable empty arrays -------------------------------------------

    static final Class<?>[] NO_PARAMS = new Class<?>[0];
    static final Object[]   NO_ARGS  = new Object[0];

    /**
     * Find and invoke ANY instance method on {@code target} whose parameter
     * types match {@code paramTypes}.  Completely ignores method names â€?
     * works purely by parameter-type signature across declared + public
     * inherited methods.  Throws if no matching method is found or
     * invocation fails.
     *
     * <p>This bypasses the global {@link FabricReflectionConstants#redirectMethod}
     * table entirely and is safe to use across all MC versions and target
     * classes.  Essential for multi-version fallback chains where the same
     * mojang name resolves to different intermediary names on different
     * target classes.
     */
    static void invokeBySigOrThrow(Object target, Class<?>[] paramTypes, Object[] args) {
        if (target == null) throw new IllegalArgumentException("target is null");
        Method m = scanMethod(target.getClass(), paramTypes, null);
        if (m == null)
            throw new RuntimeException(new NoSuchMethodException(
                target.getClass().getName() + ".(*sig " + paramTypes.length + " params)"));
        try {
            m.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Invoke by parameter signature AND return type.  Unlike
     * {@link #call}/{@link #findMethodImpl}, this never uses METHOD_REDIRECT
     * and never falls back to the first param-only match â€?critical when
     * multiple methods share the same parameter types (e.g. several
     * {@code (GameProfile)} methods, or several {@code (I)Z} methods).
     *
     * @param returnType required return type, or {@code null} to ignore it
     * @return the invoke result, or {@code null} if no match / invoke failed
     */
    static Object callBySig(Object target, Class<?>[] paramTypes, Object[] args,
<<<<<<< HEAD
                                   Class<?> returnType) {
=======
                            Class<?> returnType) {
>>>>>>> parent of 6548f47 (refactor: make FabricReflection methods public for cross-package access)
        if (target == null) return null;
        Method m = scanMethod(target.getClass(), paramTypes, returnType);
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    // ==================================================================
    // Class loading
    // ==================================================================

    /**
     * Load a Minecraft class by its mojang/named name, falling back to
     * intermediary class name on production servers.  Results are cached.
     */
    static Class<?> forName(String namedClassName) {
        if (namedClassName == null) return null;
        Object cached = classCache.get(namedClassName);
        if (cached != null) return unwrap(cached);

        Class<?> cls = tryLoad(namedClassName);
        if (cls != null) { classCache.put(namedClassName, cls); return cls; }
        String inter = FabricReflectionConstants.toIntermediaryClass(namedClassName);
        if (inter != null && !inter.equals(namedClassName)) {
            cls = tryLoad(inter);
            if (cls != null) { classCache.put(namedClassName, cls); return cls; }
        }
        if (DEBUG_REFLECTION) {
            // Suppress noise for Fabric API classes that are conditionally
            // available (v2 vs v1, optional modules, etc.)
            if (!namedClassName.startsWith("net.fabricmc.fabric.api."))
                log("CLS-MISS " + namedClassName);
        }
        classCache.put(namedClassName, NOT_FOUND);
        return null;
    }

    private static Class<?> tryLoad(String name) {
        if (name == null) return null;
        try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; }
    }

    // ==================================================================
    // Text/Component creation (unified â€?used by all callers)
    // ==================================================================

    /** Cached result of {@link #resolveTextComponentClass()}. */
    private static volatile Class<?> cachedTextClass;
    private static volatile boolean cachedTextClassResolved;

    /**
     * Resolve the Minecraft text component class at runtime.
     * Returns {@code Component} (MC 26.1+) or {@code class_2561} (1.18-1.21 intermediary).
     * Uses {@link #forName} so intermediary fallback works on production servers.
     */
    static Class<?> resolveTextComponentClass() {
        if (cachedTextClassResolved) return cachedTextClass;
        cachedTextClass = forName(FabricReflectionConstants.CLS_COMPONENT);
        cachedTextClassResolved = true;
        return cachedTextClass;
    }

    /**
     * Create a Minecraft text component from a plain string.
     *
     * <p>Priority (all go through {@link #forName}/{@link #callStatic} so
     * intermediary name resolution works on 1.18â€?.21.x production servers):
     * <ol>
     *   <li>{@code Component.literal(String)} â€?1.19.3+ / MC 26.1+</li>
     *   <li>{@code new TextComponent(String)} â€?1.18â€?.19.2</li>
     * </ol>
     *
     * @return the text component object, or {@code null} if all approaches fail
     */
    static Object createText(String message) {
        if (message == null) return null;
        // 1) Component.literal(String) â€?1.19.3+ / MC 26.1+
        //    Tried first to avoid a spurious CLS-MISS for TextComponent on
        //    MC 26.1+ where that class was removed.
        Object r = callStatic(FabricReflectionConstants.CLS_COMPONENT,
            FabricReflectionConstants.M_COMPONENT_LITERAL,
            new Class<?>[]{String.class}, new Object[]{message});
        if (r != null) return r;

        // 2) new TextComponent(String) â€?1.18â€?.19.2 (legacy)
        //    Intermediary: net/minecraft/class_2585
        Class<?> tcCls = forName("net.minecraft.network.chat.TextComponent");
        if (tcCls != null) {
            try {
                Constructor<?> ctor = tcCls.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                return ctor.newInstance(message);
            } catch (Throwable t) { /* fall through */ }
        }
        return null;
    }

    // ==================================================================
    // Method invocation
    // ==================================================================

    /**
     * Call a static method.  Looked-up Methods are cached by (className, methodName, paramTypes).
     *
     * @param className  mojang/named class name (resolved via {@link #forName})
     * @param methodName runtime method name (bare mojang name â€?will be redirected)
     */
    static Object callStatic(String className, String methodName,
<<<<<<< HEAD
                                    Class<?>[] paramTypes, Object[] args) {
=======
                             Class<?>[] paramTypes, Object[] args) {
>>>>>>> parent of 6548f47 (refactor: make FabricReflection methods public for cross-package access)
        StaticMethodKey key = new StaticMethodKey(className, methodName, paramTypes);
        Object cached = staticMethodCache.get(key);
        if (cached != null) {
            Method m = unwrap(cached);
            if (m == null) return null;
            try { return m.invoke(null, args); }
            catch (IllegalAccessException | InvocationTargetException e) { return null; }
        }

        Class<?> cls = forName(className);
        if (cls == null) { staticMethodCache.put(key, NOT_FOUND); return null; }
        String r = FabricReflectionConstants.redirectMethod(methodName);

        // 1. Exact match with resolved name
        Method found = null;
        try {
            Method m = cls.getDeclaredMethod(r, paramTypes);
            m.setAccessible(true);
            Object result = m.invoke(null, args);
            staticMethodCache.put(key, m);
            return result;
        } catch (NoSuchMethodException e) {
            try {
                Method m = cls.getMethod(r, paramTypes);
                Object result = m.invoke(null, args);
                staticMethodCache.put(key, m);
                return result;
            } catch (Throwable t2) { /* fall through */ }
        } catch (IllegalAccessException | InvocationTargetException e) {
            staticMethodCache.put(key, NOT_FOUND);
            return null;
        }

        // 2. If resolved name â‰?original, try original name
        if (!r.equals(methodName)) {
            try {
                found = cls.getDeclaredMethod(methodName, paramTypes);
                found.setAccessible(true);
                Object result = found.invoke(null, args);
                staticMethodCache.put(key, found);
                return result;
            } catch (NoSuchMethodException ignored) {
            } catch (IllegalAccessException | InvocationTargetException e) {
                staticMethodCache.put(key, NOT_FOUND);
                return null;
            }
            try {
                found = cls.getMethod(methodName, paramTypes);
                found.setAccessible(true);
                Object result = found.invoke(null, args);
                staticMethodCache.put(key, found);
                return result;
            } catch (NoSuchMethodException ignored) {
            } catch (IllegalAccessException | InvocationTargetException e) {
                staticMethodCache.put(key, NOT_FOUND);
                return null;
            }
        }

        // 3. Parameter-type scan â€?match any static method with compatible params
        for (Method candidate : cls.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) continue;
            Class<?>[] pts = candidate.getParameterTypes();
            if (pts.length != paramTypes.length) continue;
            boolean match = true;
            for (int i = 0; i < pts.length; i++) {
                if (!pts[i].isAssignableFrom(paramTypes[i])) { match = false; break; }
            }
            if (match) {
                try {
                    candidate.setAccessible(true);
                    Object result = candidate.invoke(null, args);
                    staticMethodCache.put(key, candidate);
                    return result;
                } catch (Throwable ignored) {}
            }
        }

        if (DEBUG_REFLECTION)
            log("STATIC-MISS " + className + "." + methodName + " (resolved=" + r + ")");
        staticMethodCache.put(key, NOT_FOUND);
        return null;
    }

    /**
     * Call an instance method.
     *
     * @param target     the object to invoke on
     * @param methodName bare mojang method name â€?redirected automatically
     */
    static Object call(Object target, String methodName,
<<<<<<< HEAD
                              Class<?>[] paramTypes, Object[] args) {
=======
                       Class<?>[] paramTypes, Object[] args) {
>>>>>>> parent of 6548f47 (refactor: make FabricReflection methods public for cross-package access)
        if (target == null) return null;
        Method m = findMethodImpl(target.getClass(), methodName, paramTypes);
        if (m == null) return null;
        try { return m.invoke(target, args); }
        catch (IllegalAccessException | InvocationTargetException e) { return null; }
    }

    /** Alias for {@link #call}. */
    static Object callAny(Object target, String methodName,
<<<<<<< HEAD
                                 Class<?>[] paramTypes, Object[] args) {
=======
                          Class<?>[] paramTypes, Object[] args) {
>>>>>>> parent of 6548f47 (refactor: make FabricReflection methods public for cross-package access)
        return call(target, methodName, paramTypes, args);
    }

    /**
     * Try {@code mc26Method} first, then {@code legacyMethod}.
     * Both are bare mojang names and will be redirected.
     */
    static Object callMigrated(Object target, String mc26Method, String legacyMethod,
<<<<<<< HEAD
                                      Class<?>[] paramTypes, Object[] args) {
=======
                               Class<?>[] paramTypes, Object[] args) {
>>>>>>> parent of 6548f47 (refactor: make FabricReflection methods public for cross-package access)
        if (target == null) return null;
        Object r = call(target, mc26Method, paramTypes, args);
        if (r != null) return r;
        return call(target, legacyMethod, paramTypes, args);
    }

    // ==================================================================
    // Public method lookup (used externally)
    // ==================================================================

    static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        return findMethodImpl(cls, name, paramTypes);
    }

    static Method findMethodWithMigration(Class<?> cls, String methodName,
                                          Class<?>[] paramTypes) {
        return findMethodImpl(cls, methodName, paramTypes);
    }

    // ==================================================================
    // Field access
    // ==================================================================

    @SuppressWarnings("unchecked")
    static <T> T getField(Object target, String fieldName) {
        try {
            Class<?> cls = (target instanceof Class) ? (Class<?>) target : target.getClass();
            Field f = findField(cls, fieldName);
            if (f == null) return null;
            f.setAccessible(true);
            Object owner = (target instanceof Class) ? null : target;
            return (T) f.get(owner);
        } catch (IllegalAccessException e) { return null; }
    }

    // ==================================================================
    // Version-aware helpers
    // ==================================================================

<<<<<<< HEAD
    /** Get player UUID â€?tries getUUID then getUuid. */
=======
    /** Get player UUID â€” tries getUUID then getUuid. */
>>>>>>> parent of 6548f47 (refactor: make FabricReflection methods public for cross-package access)
    static Object callUuid(Object target) {
        if (target == null) return null;
        Object r = call(target, "getUUID", NO_PARAMS, NO_ARGS);
        if (r != null) return r;
        return call(target, "getUuid", NO_PARAMS, NO_ARGS);
    }

<<<<<<< HEAD
    /** Get world dimension ResourceKey â€?dimension() then getRegistryKey(). */
=======
    /** Get world dimension ResourceKey â€” dimension() then getRegistryKey(). */
>>>>>>> parent of 6548f47 (refactor: make FabricReflection methods public for cross-package access)
    static Object callDimension(Object world) {
        if (world == null) return null;
        Object r = call(world, "dimension", NO_PARAMS, NO_ARGS);
        if (r != null) return r;
        return call(world, "getRegistryKey", NO_PARAMS, NO_ARGS);
    }

    /** Extract identifier/location from a ResourceKey. */
    static Object callResourceKeyValue(Object key) {
        if (key == null) return null;
        for (String name : new String[]{"location", "identifier", "getValue"}) {
            Object r = call(key, name, NO_PARAMS, NO_ARGS);
            if (r != null) return r;
        }
        return null;
    }

    /** Read a human-readable string from a Component / Identifier / String. */
    static String readString(Object source) {
        if (source == null) return null;
        if (source instanceof String) return (String) source;
        Object s = call(source, "getString", NO_PARAMS, NO_ARGS);
        if (s instanceof String) return (String) s;
        String str = source.toString();
        if (str.startsWith("literal{") && str.endsWith("}"))
            return str.substring("literal{".length(), str.length() - 1);
        if (str.startsWith("literal(") && str.endsWith(")"))
            return str.substring("literal(".length(), str.length() - 1);
        return str;
    }

    /**
     * Resolve a Block to its canonical minecraft:path id.
     * <p>Uses {@code Block.toString()} as the primary reliable path across
     * ALL MC versions; registry-based lookups are fallbacks.</p>
     */
    static String getBlockId(Object block) {
        if (block == null) return null;
        // 1. Block.toString() â†?"Block{minecraft:diorite}"
        //    Works on every MC version (1.18â€?.26+), no reflection needed.
        String s = block.toString();
        int brace = s.indexOf('{');
        int close  = s.indexOf('}');
        if (brace >= 0 && close > brace) {
            return s.substring(brace + 1, close);
        }
        // 2. block.builtInRegistryHolder().getKey() â€?direct getMethod
        //    bypasses redirectMethod issues with getKey having different
        //    intermediary names for different signatures.
        Object holder = call(block, "builtInRegistryHolder", NO_PARAMS, NO_ARGS);
        if (holder != null) {
            try {
                Object key = holder.getClass().getMethod("getKey").invoke(holder);
                if (key != null) {
                    Object loc = callResourceKeyValue(key);
                    if (loc != null) return readString(loc);
                }
            } catch (Throwable ignored) {}
        }
        // 3. BuiltInRegistries.BLOCK (MC 1.19.3+)
        String id = resolveBlockViaRegistry(FabricReflectionConstants.CLS_BUILT_IN_REGISTRIES,
                FabricReflectionConstants.F_BUILTIN_BLOCK, block);
        if (id != null) return id;
        // 4. Registry.BLOCK (MC 1.18.2)
        id = resolveBlockViaRegistry(FabricReflectionConstants.CLS_REGISTRY,
                FabricReflectionConstants.F_REGISTRY_BLOCK, block);
        if (id != null) return id;
        // 5. Registries.BLOCK
        return resolveBlockViaRegistriesKey(block);
    }

    private static String resolveBlockViaRegistry(String regClsName, String fieldName, Object block) {
        Class<?> regCls = forName(regClsName);
        if (regCls == null) return null;
        Object registry = getField(regCls, fieldName);
        if (registry == null) return null;
        Object id = call(registry, "getKey", new Class<?>[]{Object.class}, new Object[]{block});
        if (id != null) return readString(id);
        Object optKey = call(registry, "getResourceKey", new Class<?>[]{Object.class}, new Object[]{block});
        if (optKey instanceof java.util.Optional) {
            java.util.Optional<?> opt = (java.util.Optional<?>) optKey;
            if (opt.isPresent()) {
                Object loc = callResourceKeyValue(opt.get());
                if (loc != null) return readString(loc);
            }
        }
        return null;
    }

    private static String resolveBlockViaRegistriesKey(Object block) {
        Class<?> regsCls = forName(FabricReflectionConstants.CLS_REGISTRIES);
        if (regsCls == null) return null;
        for (Method m : regsCls.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getReturnType() != void.class) {
                try {
                    Object id = m.invoke(null, block);
                    if (id != null) {
                        String s = readString(id);
                        if (s != null && s.contains(":")) return s;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** Create a new instance via reflection. */
    static Object newInstance(String className, Class<?>[] paramTypes, Object[] args) {
        Class<?> cls = forName(className);
        if (cls == null) return null;
        try {
            Constructor<?> c = cls.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (NoSuchMethodException | IllegalAccessException
                | InstantiationException | InvocationTargetException e) {
            return null;
        }
    }

    // ==================================================================
    // Internal lookup â€?with bare-name redirect
    // ==================================================================

    /**
     * Find a method by bare mojang name. Resolves via
     * {@link FabricReflectionConstants#redirectMethod}, then falls back to
     * a parameter-type scan on the class itself before walking to the
     * superclass.  The scan is essential on production servers where the
     * same mojang name maps to different intermediary names on different
     * classes (e.g. Entity.getX vs BlockPos.getX).
     */
    static Method findMethodImpl(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null || name == null) return null;
        MethodKey key = new MethodKey(cls, name, paramTypes);
        Object cached = methodCache.get(key);
        if (cached != null) return unwrap(cached);

        String resolved = FabricReflectionConstants.redirectMethod(name);

        // 1. Try resolved name
        Method m = tryMethod(cls, resolved, paramTypes);
        if (m != null) { methodCache.put(key, m); return m; }

        // 2. If resolved â‰?original, try original
        if (!resolved.equals(name)) {
            m = tryMethod(cls, name, paramTypes);
            if (m != null) { methodCache.put(key, m); return m; }
        }

        // 3. Parameter-type scan â€?try EVERY declared method with matching
        //    paramTypes.  Critical for classes like BlockPos / Vec3i whose
        //    getX/getY/getZ have different intermediary names than Entity's.
        //    Prefer methods whose name equals the resolved/original name when
        //    multiple signatures collide (e.g. several (GameProfile) methods).
        m = scanMethodNamed(cls, resolved, paramTypes);
        if (m == null && !resolved.equals(name))
            m = scanMethodNamed(cls, name, paramTypes);
        if (m == null)
            m = scanMethod(cls, paramTypes, null);
        if (m != null) { methodCache.put(key, m); return m; }

        // 4. Walk up to superclass
        Class<?> sup = cls.getSuperclass();
        if (sup != null && sup != Object.class) {
            m = findMethodImpl(sup, name, paramTypes);
            if (m != null) { methodCache.put(key, m); return m; }
        }

        if (DEBUG_REFLECTION)
            log("M-MISS " + cls.getName() + "." + name + " (resolved=" + resolved + ")");
        methodCache.put(key, NOT_FOUND);
        return null;
    }

    private static Method tryMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        try {
            Method m = cls.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {}
        try {
            Method m = cls.getMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {}
        return null;
    }

    /**
     * Scan methods whose name equals {@code name} and params match.
     * Used before blind param-only scan so overloaded same-sig methods
     * (isOp vs isWhiteListed) are not confused when the name is known.
     */
    private static Method scanMethodNamed(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (name == null) return null;
        for (Method candidate : cls.getDeclaredMethods()) {
            if (!candidate.getName().equals(name)) continue;
            if (paramsMatch(candidate, paramTypes) && returnOk(candidate, null)) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        for (Method candidate : cls.getMethods()) {
            if (candidate.getDeclaringClass() == Object.class) continue;
            if (!candidate.getName().equals(name)) continue;
            if (paramsMatch(candidate, paramTypes) && returnOk(candidate, null)) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return null;
    }

    /**
     * Scan all declared AND inherited public methods on {@code cls} for
     * one whose parameter types match {@code paramTypes} and optionally
     * whose return type is assignable to {@code returnType}.
     */
<<<<<<< HEAD
    static Method scanMethod(Class<?> cls, Class<?>[] paramTypes, Class<?> returnType) {
        // 1. Declared (most specific â€?avoids duplicates from getMethods)
=======
    private static Method scanMethod(Class<?> cls, Class<?>[] paramTypes, Class<?> returnType) {
        // 1. Declared (most specific â€” avoids duplicates from getMethods)
>>>>>>> parent of 6548f47 (refactor: make FabricReflection methods public for cross-package access)
        for (Method candidate : cls.getDeclaredMethods()) {
            if (paramsMatch(candidate, paramTypes) && returnOk(candidate, returnType)) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        // 2. Inherited public methods (includes superclass + interfaces)
        for (Method candidate : cls.getMethods()) {
            if (candidate.getDeclaringClass() == Object.class) continue;
            if (paramsMatch(candidate, paramTypes) && returnOk(candidate, returnType)) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return null;
    }

    private static boolean paramsMatch(Method m, Class<?>[] paramTypes) {
        Class<?>[] pts = m.getParameterTypes();
        if (pts.length != paramTypes.length) return false;
        for (int i = 0; i < pts.length; i++) {
            if (!pts[i].isAssignableFrom(paramTypes[i])) return false;
        }
        return true;
    }

    private static boolean returnOk(Method m, Class<?> returnType) {
        if (returnType == null) return true;
        Class<?> rt = m.getReturnType();
        if (returnType == void.class) return rt == void.class;
        if (rt == void.class) return false;
        // Allow primitive int for Integer.class etc.
        if (returnType.isPrimitive()) return rt == returnType;
        if (rt.isPrimitive()) {
            if (returnType == Integer.class) return rt == int.class;
            if (returnType == Boolean.class) return rt == boolean.class;
            if (returnType == Long.class) return rt == long.class;
            if (returnType == Double.class) return rt == double.class;
            if (returnType == Float.class) return rt == float.class;
            return false;
        }
        return returnType.isAssignableFrom(rt);
    }

    /** Find a field by bare mojang name, redirected to runtime name.  Results are cached. */
    private static Field findField(Class<?> cls, String name) {
        if (cls == null || name == null) return null;
        FieldKey key = new FieldKey(cls, name);
        Object cached = fieldCache.get(key);
        if (cached != null) return unwrap(cached);

        String resolved = FabricReflectionConstants.redirectField(name);
        Field f = tryField(cls, resolved);
        if (f != null) { fieldCache.put(key, f); return f; }

        if (!resolved.equals(name)) {
            f = tryField(cls, name);
            if (f != null) { fieldCache.put(key, f); return f; }
        }
        Class<?> sup = cls.getSuperclass();
        if (sup != null && sup != Object.class) {
            f = findField(sup, name);
            if (f != null) { fieldCache.put(key, f); return f; }
        }

        fieldCache.put(key, NOT_FOUND);
        return null;
    }

    private static Field tryField(Class<?> cls, String name) {
        try { return cls.getDeclaredField(name); }
        catch (NoSuchFieldException ignored) {}
        try { return cls.getField(name); }
        catch (NoSuchFieldException ignored) {}
        return null;
    }
}
