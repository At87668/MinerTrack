package link.star_dust.MinerTrack.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Lightweight reflection helpers for accessing Minecraft internals.
 *
 * <p>Uses {@link FabricReflectionConstants} to redirect bare mojang method
 * and field names to their runtime form (intermediary on 1.18–1.21.x,
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

    // -- reusable empty arrays -------------------------------------------

    static final Class<?>[] NO_PARAMS = new Class<?>[0];
    static final Object[]   NO_ARGS  = new Object[0];

    // ==================================================================
    // Class loading
    // ==================================================================

    /**
     * Load a Minecraft class by its mojang/named name, falling back to
     * intermediary class name on production servers.
     */
    static Class<?> forName(String namedClassName) {
        Class<?> cls = tryLoad(namedClassName);
        if (cls != null) return cls;
        String inter = FabricReflectionConstants.toIntermediaryClass(namedClassName);
        if (inter != null && !inter.equals(namedClassName))
            cls = tryLoad(inter);
        if (cls == null && DEBUG_REFLECTION) {
            // Suppress noise for Fabric API classes that are conditionally
            // available (v2 vs v1, optional modules, etc.)
            if (!namedClassName.startsWith("net.fabricmc.fabric.api."))
                log("CLS-MISS " + namedClassName);
        }
        return cls;
    }

    private static Class<?> tryLoad(String name) {
        if (name == null) return null;
        try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; }
    }

    // ==================================================================
    // Text/Component creation (unified — used by all callers)
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
     * intermediary name resolution works on 1.18–1.21.x production servers):
     * <ol>
     *   <li>{@code Component.literal(String)} — 1.19.3+ / MC 26.1+</li>
     *   <li>{@code new TextComponent(String)} — 1.18–1.19.2</li>
     * </ol>
     *
     * @return the text component object, or {@code null} if all approaches fail
     */
    static Object createText(String message) {
        if (message == null) return null;
        // 1) Component.literal(String) — 1.19.3+ — via callStatic (handles intermediary)
        Object r = callStatic(FabricReflectionConstants.CLS_COMPONENT,
            FabricReflectionConstants.M_COMPONENT_LITERAL,
            new Class<?>[]{String.class}, new Object[]{message});
        if (r != null) return r;

        // 2) new TextComponent(String) — 1.18–1.19.2
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

    /**
     * Send a text component to a server player, trying every known API
     * across MC versions (1.18.2 through 1.26+).
     *
     * <p>Priority:
     * <ol>
     *   <li>{@code sendSystemMessage(Component)} — 1.19.3+</li>
     *   <li>{@code displayClientMessage(Component, boolean)} — 1.18.2 action bar</li>
     *   <li>{@code sendMessage(Component, UUID)} — 1.18.2 chat</li>
     *   <li>{@code sendMessage(Component)} — 1.18-1.19.2 fallback</li>
     * </ol>
     *
     * @param player the ServerPlayer object
     * @param text   the Component/Text object (from {@link #createText})
     */
    static void sendMessageToPlayer(Object player, Object text) {
        if (player == null || text == null) return;
        Class<?> textCls = resolveTextComponentClass();
        if (textCls == null) return;

        // 1) sendSystemMessage(Component) — 1.19.3+
        if (trySend(player, "sendSystemMessage", new Class<?>[]{textCls}, new Object[]{text})) return;

        // 2) displayClientMessage(Component, boolean) — 1.18.2 action bar / chat
        if (trySend(player, "displayClientMessage", new Class<?>[]{textCls, boolean.class}, new Object[]{text, false})) return;

        // 3) sendMessage(Component, UUID) — 1.18.2 chat
        if (trySend(player, "sendMessage", new Class<?>[]{textCls, java.util.UUID.class}, new Object[]{text, java.util.UUID.randomUUID()})) return;

        // 4) sendMessage(Component) — 1.18-1.19.2 plain
        trySend(player, "sendMessage", new Class<?>[]{textCls}, new Object[]{text});
    }

    private static boolean trySend(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            callAny(target, methodName, paramTypes, args);
            return true;
        } catch (Throwable t) { return false; }
    }

    /**
     * Send a text component to the server console / server log.
     *
     * <p>Priority:
     * <ol>
     *   <li>{@code sendSystemMessage(Component)} — 1.19.3+</li>
     *   <li>{@code sendMessage(Component, UUID)} — 1.18.2</li>
     *   <li>{@code sendMessage(Component)} — legacy</li>
     * </ol>
     */
    static void sendMessageToConsole(Object server, Object text) {
        if (server == null || text == null) return;
        Class<?> textCls = resolveTextComponentClass();
        if (textCls == null) return;

        // 1) sendSystemMessage(Component) — 1.19.3+
        if (trySend(server, "sendSystemMessage", new Class<?>[]{textCls}, new Object[]{text})) return;

        // 2) sendMessage(Component, UUID) — 1.18.2
        if (trySend(server, "sendMessage", new Class<?>[]{textCls, java.util.UUID.class}, new Object[]{text, java.util.UUID.randomUUID()})) return;

        // 3) sendMessage(Component) — legacy
        trySend(server, "sendMessage", new Class<?>[]{textCls}, new Object[]{text});
    }

    // ==================================================================
    // Method invocation
    // ==================================================================

    /**
     * Call a static method.
     *
     * @param className  mojang/named class name (resolved via {@link #forName})
     * @param methodName runtime method name (bare mojang name — will be redirected)
     */
    static Object callStatic(String className, String methodName,
                             Class<?>[] paramTypes, Object[] args) {
        Class<?> cls = forName(className);
        if (cls == null) return null;
        String r = FabricReflectionConstants.redirectMethod(methodName);

        // 1. Exact match with resolved name
        try {
            Method m = cls.getDeclaredMethod(r, paramTypes);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (NoSuchMethodException e) {
            try { return cls.getMethod(r, paramTypes).invoke(null, args); }
            catch (Throwable t2) { /* fall through */ }
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }

        // 2. If resolved name ≠ original, try original name
        if (!r.equals(methodName)) {
            try {
                Method m = cls.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(null, args);
            } catch (NoSuchMethodException ignored) {
            } catch (IllegalAccessException | InvocationTargetException e) {
                return null;
            }
            try {
                Method m = cls.getMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(null, args);
            } catch (NoSuchMethodException ignored) {
            } catch (IllegalAccessException | InvocationTargetException e) {
                return null;
            }
        }

        // 3. Parameter-type scan — match any static method with compatible params
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
                    return candidate.invoke(null, args);
                } catch (Throwable ignored) {}
            }
        }

        if (DEBUG_REFLECTION)
            log("STATIC-MISS " + className + "." + methodName + " (resolved=" + r + ")");
        return null;
    }

    /**
     * Call an instance method.
     *
     * @param target     the object to invoke on
     * @param methodName bare mojang method name — redirected automatically
     */
    static Object call(Object target, String methodName,
                       Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        Method m = findMethodImpl(target.getClass(), methodName, paramTypes);
        if (m == null) return null;
        try { return m.invoke(target, args); }
        catch (IllegalAccessException | InvocationTargetException e) { return null; }
    }

    /** Alias for {@link #call}. */
    static Object callAny(Object target, String methodName,
                          Class<?>[] paramTypes, Object[] args) {
        return call(target, methodName, paramTypes, args);
    }

    /**
     * Try {@code mc26Method} first, then {@code legacyMethod}.
     * Both are bare mojang names and will be redirected.
     */
    static Object callMigrated(Object target, String mc26Method, String legacyMethod,
                               Class<?>[] paramTypes, Object[] args) {
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

    /** Get player UUID — tries getUUID then getUuid. */
    static Object callUuid(Object target) {
        if (target == null) return null;
        Object r = call(target, "getUUID", NO_PARAMS, NO_ARGS);
        if (r != null) return r;
        return call(target, "getUuid", NO_PARAMS, NO_ARGS);
    }

    /** Get world dimension ResourceKey — dimension() then getRegistryKey(). */
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
        // 1. Block.toString() → "Block{minecraft:diorite}"
        //    Works on every MC version (1.18–1.26+), no reflection needed.
        String s = block.toString();
        int brace = s.indexOf('{');
        int close  = s.indexOf('}');
        if (brace >= 0 && close > brace) {
            return s.substring(brace + 1, close);
        }
        // 2. block.builtInRegistryHolder().getKey() — direct getMethod
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
    // Internal lookup — with bare-name redirect
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
        String resolved = FabricReflectionConstants.redirectMethod(name);

        // 1. Try resolved name
        Method m = tryMethod(cls, resolved, paramTypes);
        if (m != null) return m;

        // 2. If resolved ≠ original, try original
        if (!resolved.equals(name)) {
            m = tryMethod(cls, name, paramTypes);
            if (m != null) return m;
        }

        // 3. Parameter-type scan — try EVERY declared method with matching
        //    paramTypes.  Critical for classes like BlockPos / Vec3i whose
        //    getX/getY/getZ have different intermediary names than Entity's.
        m = scanMethod(cls, paramTypes);
        if (m != null) return m;

        // 4. Walk up to superclass
        Class<?> sup = cls.getSuperclass();
        if (sup != null && sup != Object.class)
            return findMethodImpl(sup, name, paramTypes);

        if (DEBUG_REFLECTION)
            log("M-MISS " + cls.getName() + "." + name + " (resolved=" + resolved + ")");
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
     * Scan all declared methods on {@code cls} for one whose parameter
     * types match {@code paramTypes}.  Returns the first match.
     */
    private static Method scanMethod(Class<?> cls, Class<?>[] paramTypes) {
        for (Method candidate : cls.getDeclaredMethods()) {
            Class<?>[] pts = candidate.getParameterTypes();
            if (pts.length != paramTypes.length) continue;
            boolean match = true;
            for (int i = 0; i < pts.length; i++) {
                if (!pts[i].isAssignableFrom(paramTypes[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return null;
    }

    /** Find a field by bare mojang name, redirected to runtime name. */
    private static Field findField(Class<?> cls, String name) {
        if (cls == null || name == null) return null;
        String resolved = FabricReflectionConstants.redirectField(name);
        try { return cls.getDeclaredField(resolved); }
        catch (NoSuchFieldException ignored) {}
        try { return cls.getField(resolved); }
        catch (NoSuchFieldException ignored) {}
        if (!resolved.equals(name)) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            try { return cls.getField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        Class<?> sup = cls.getSuperclass();
        if (sup != null && sup != Object.class)
            return findField(sup, name);
        return null;
    }
}
