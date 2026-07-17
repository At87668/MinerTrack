package link.star_dust.MinerTrack.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Reflection helpers for accessing net.minecraft.* types not on compile classpath.
 * Used only when Fabric API cannot provide the needed hook.
 *
 * <p>This class handles Minecraft's code obfuscation by using Fabric's {@link MappingResolver}
 * to map method names from the injected {@code "mojmap"} namespace to runtime (obfuscated)
 * names when necessary.</p>
 *
 * <p>In a named environment (development or modern Fabric servers with "named" mappings),
 * class and method names are already human-readable. In an obfuscated production environment,
 * this class will automatically resolve the correct obfuscated names via the Mojmap namespace
 * that was injected during pre-launch by {@link MinerTrackPreLaunch}.</p>
 *
 * <p>For Minecraft 26.x and later the server jar ships unobfuscated, so all Mojmap resolution
 * is bypassed and the original names are used directly.</p>
 */
final class FabricReflection {
    private static final Logger LOGGER = Logger.getLogger("MinerTrack/FabricReflection");
    
    // Lazy initialization to avoid static init issues if FabricLoader isn't ready
    private static MappingResolver getMappingResolver() {
        return FabricLoader.getInstance().getMappingResolver();
    }

    private static final String RUNTIME_NAMESPACE = FabricLoader.getInstance().isDevelopmentEnvironment() ? "named" : getRuntimeNamespace();

    // Lazily initialised Mojmap resolver (only for 1.18–1.21.x, used for field lookups)
    private static InternalMappingResolver mojmapResolver;

    /** Cached MinecraftServer instance — MC 26.1+ has no static getServer(). Set by SERVER_STARTED. */
    private static volatile Object cachedServer;

    /** Determine the runtime namespace based on environment */
    private static String getRuntimeNamespace() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return "named";
        }
        return "intermediary";
    }

    private FabricReflection() {}

    // ── MC 26.1.2 API migration ──────────────────────────────────────────

    /**
     * Call a method on the MinecraftServer, trying MC 26.1+ name first,
     * then the legacy 1.18-1.21 name.
     */
    static Object callServer(String mc26Method, String legacyMethod, Class<?>[] paramTypes, Object[] args) {
        Object server = getServer();
        if (server == null) return null;
        // Try MC 26.1+ method name first
        Object result = call(server, mc26Method, paramTypes, args);
        if (result != null || !mc26Method.equals(legacyMethod)) {
            // If call returned non-null or methods differ, try legacy as fallback
            try {
                if (result == null) {
                    result = call(server, legacyMethod, paramTypes, args);
                }
            } catch (Throwable t) { /* fall through */ }
            return result;
        }
        return null;
    }

    /**
     * Call a method on an object, trying the MC 26.1+ name first.
     * Handles common MC version migrations.
     */
    static Object callMigrated(Object target, String mc26Method, String legacyMethod,
                               Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        // Try MC 26.1+ method first
        try {
            Method m = findMethod(target.getClass(), mc26Method, paramTypes);
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        } catch (Throwable t) { /* fall through */ }
        // Fall back to legacy method name
        return call(target, legacyMethod, paramTypes, args);
    }

    /** Common API migrations as a Map for method name resolution. */
    private static final java.util.Map<String, String> API_MIGRATIONS = new java.util.HashMap<>();
    static {
        API_MIGRATIONS.put("getPlayerManager", "getPlayerList");
        API_MIGRATIONS.put("getCommandManager", "getCommands");
        API_MIGRATIONS.put("getWorlds", "getAllLevels");
        API_MIGRATIONS.put("getWorld", "getLevel");
        API_MIGRATIONS.put("getCommandSource", "createCommandSourceStack");
        API_MIGRATIONS.put("withSilent", "withSuppressedOutput");
        API_MIGRATIONS.put("getTicks", "getTickCount");
        API_MIGRATIONS.put("isExecutedByPlayer", "isPlayer");
        API_MIGRATIONS.put("hasPermissionLevel", "hasPermission");
        API_MIGRATIONS.put("executeWithPrefix", "performCommand");
    }

    /**
     * Try to resolve a method on a class by name, automatically trying
     * MC 26.1+ migration if the original name fails.
     */
    static Method findMethodWithMigration(Class<?> cls, String methodName, Class<?>[] paramTypes) {
        Method m = findMethod(cls, methodName, paramTypes);
        if (m != null) return m;
        String migrated = API_MIGRATIONS.get(methodName);
        if (migrated != null) {
            return findMethod(cls, migrated, paramTypes);
        }
        return null;
    }

    /** Called by FabricDetectionBridge when SERVER_STARTED fires. */
    static void setCachedServer(Object server) {
        cachedServer = server;
    }

    /**
     * Get the MinecraftServer instance. MC 26.1+ has no static {@code getServer()}.
     * Uses cached reference (set during SERVER_STARTED) with a
     * {@code static getServer()} fallback for older versions.
     */
    static Object getServer() {
        if (cachedServer != null) return cachedServer;
        // Fallback for 1.18–1.21.x which have static getServer()
        return callStatic("net.minecraft.server.MinecraftServer",
            "getServer", new Class<?>[0], new Object[0]);
    }

    /**
     * Get or create the Mojmap resolver. Returns null if Mojmap is not required
     * for the current Minecraft version or if the resolver could not be initialised.
     *
     * <p>The resolver is usable even when Fabric Loader injection fails, because
     * {@link InternalMappingResolver#loadAndInject()} now loads internal tables
     * before attempting injection. Two-step resolution (mojmap→official via
     * InternalMappingResolver, then official→runtime via Fabric's MappingResolver)
     * works independently of a successful mojmap namespace injection.</p>
     */
    private static synchronized InternalMappingResolver getMojmapResolver() {
        if (mojmapResolver != null) return mojmapResolver;
        try {
            String mcVersion = InternalMappingResolver.getMinecraftVersion();
            if (!InternalMappingResolver.isMojmapRequired(mcVersion)) {
                return null;
            }
            mojmapResolver = new InternalMappingResolver(
                    FabricLoader.getInstance().getGameDir(), mcVersion);
            mojmapResolver.loadAndInject();
            return mojmapResolver;
        } catch (Exception e) {
            // If the resolver itself was constructed and has tables loaded,
            // return it anyway — two-step resolution can still work.
            if (mojmapResolver != null && mojmapResolver.isCached()) {
                LOGGER.warning("Mojmap resolver injection failed but tables are available: " + e.getMessage());
                return mojmapResolver;
            }
            LOGGER.warning("Mojmap resolver not available: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolve a Mojmap class name to its runtime (obfuscated) name.
     * Uses the injected {@code "mojmap"} namespace in Fabric's MappingResolver.
     *
     * <p>Resolution strategy:
     * <ol>
     *   <li>Direct {@code mojmap → runtime} via {@link MappingResolver#unmapClassName}.</li>
     *   <li>If direct fails, try two-step: {@code mojmap → official} via
     *       {@link InternalMappingResolver}, then {@code official → runtime}
     *       via Fabric's resolver.</li>
     * </ol>
     *
     * @param mojmapName class name in Mojmap format (e.g. "net.minecraft.server.MinecraftServer")
     * @return runtime class name, or the original name if resolution fails
     */
    static String unmapMojmapClassName(String mojmapName) {
        // Strategy 1: Direct mojmap → runtime via Fabric's MappingResolver.
        // On intermediary servers, the mojmap namespace was never injected,
        // so Fabric returns the input unchanged. We detect this and fall
        // through to Strategy 2.
        String direct = mojmapName;
        boolean directWorked = false;
        try {
            direct = getMappingResolver().unmapClassName("mojmap", mojmapName);
            if (!mojmapName.equals(direct)) {
                directWorked = true;
                return direct;
            }
        } catch (Throwable t) {
            // Fall through to strategy 2.
        }

        // Strategy 2: Two-step via InternalMappingResolver (mojmap→official→runtime).
        // This works even when mojmap namespace injection failed, because
        // InternalMappingResolver has its own Tiny v2 parsing tables.
        InternalMappingResolver resolver = getMojmapResolver();
        if (resolver != null) {
            String official = resolver.mojmapToOfficial(mojmapName);
            if (official != null && !official.equals(mojmapName)) {
                try {
                    String runtime = getMappingResolver().unmapClassName("official", official);
                    if (!official.equals(runtime)) {
                        return runtime;
                    }
                } catch (Throwable ignored) {}
            }
        }

        return mojmapName;
    }

    /**
     * Resolve a Mojmap method name to its runtime (obfuscated) name.
     * Uses the injected {@code "mojmap"} namespace in Fabric's MappingResolver.
     *
     * <p>Resolution strategy (two-step chain):
     * <ol>
     *   <li>Try direct {@code mojmap → runtime} via {@link MappingResolver#mapMethodName}.
     *       This works when Fabric's resolver can chain through the injected
     *       {@code mojmap → official} segment into its built-in
     *       {@code official → intermediary/named} segment.</li>
     *   <li>If direct resolution fails, fall back to {@link InternalMappingResolver}
     *       to resolve {@code mojmap → official} first. Note that the descriptor
     *       must be constructed using Mojmap class names to match the internal tables.</li>
     * </ol>
     *
     * @param methodName the method name in Mojmap format (e.g. "getServer")
     * @param paramTypes the parameter types
     * @return runtime method name, or the original name if resolution fails
     */
    static String unmapMojmapMethodName(String methodName, Class<?>[] paramTypes) {
        // Strategy 1: Direct mojmap → runtime via Fabric's MappingResolver.
        String descriptor = buildDescriptor(paramTypes);
        String result = tryMapMethod("mojmap", RUNTIME_NAMESPACE, methodName, descriptor);
        if (result != null) return result;

        // Retry with "()V" descriptor — some namespace combos may not
        // map the descriptor correctly cross-namespace.
        result = tryMapMethod("mojmap", RUNTIME_NAMESPACE, methodName, "()V");
        if (result != null) return result;

        // Strategy 2: Two-step resolution via InternalMappingResolver.
        InternalMappingResolver resolver = getMojmapResolver();
        if (resolver != null) {
            String mojmapClass = inferMojmapClassFromParamTypes(paramTypes);
            if (mojmapClass != null) {
                // Build descriptor using Mojmap class names for accurate lookup in InternalResolver
                String mojmapDescriptor = buildMojmapDescriptor(paramTypes);
                
                String officialMethod = resolver.resolveMethodName(mojmapClass, methodName, mojmapDescriptor);
                if (officialMethod == null || officialMethod.equals(methodName)) {
                    // Fall back to name-only lookup
                    officialMethod = resolver.resolveMethodName(mojmapClass, methodName);
                }
                
                if (officialMethod != null && !officialMethod.equals(methodName)) {
                    result = tryMapMethod("official", RUNTIME_NAMESPACE, officialMethod, descriptor);
                    if (result != null) return result;
                    result = tryMapMethod("official", RUNTIME_NAMESPACE, officialMethod, "()V");
                    if (result != null) return result;
                }
            }
        }

        return methodName;
    }

    /**
     * Try a single {@link MappingResolver#mapMethodName} call, returning
     * the resolved name if it differs from the input, or {@code null} on
     * failure / no-op.
     */
    private static String tryMapMethod(String sourceNs, String targetNs,
                                        String methodName, String descriptor) {
        try {
            String result = getMappingResolver().mapMethodName(
                    sourceNs, targetNs, methodName, descriptor);
            if (result != null && !result.equals(methodName)) {
                return result;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Try to infer the Mojmap class name from the parameter types.
     * Looks for the first {@code net.minecraft.*} parameter type and
     * resolves it to its Mojmap equivalent.
     *
     * <p>Uses three strategies:
     * <ol>
     *   <li>Direct mojmap unmap via Fabric's MappingResolver (works when
     *       mojmap namespace was injected).</li>
     *   <li>Two-step: convert runtime → official via Fabric, then
     *       official → mojmap via {@link InternalMappingResolver}
     *       (works even when mojmap namespace injection failed).</li>
     *   <li>Direct official → mojmap via InternalMappingResolver
     *       (for intermediary-runtime servers where Fabric maps to
     *       "official" first).</li>
     * </ol>
     */
    private static String inferMojmapClassFromParamTypes(Class<?>[] paramTypes) {
        InternalMappingResolver resolver = getMojmapResolver();
        for (Class<?> pt : paramTypes) {
            String name = pt.getName();
            if (name.startsWith("net.minecraft.")) {
                // Strategy 1: Direct mojmap unmap via Fabric
                try {
                    String mojmap = getMappingResolver().unmapClassName("mojmap", name);
                    if (mojmap != null && !mojmap.equals(name)) {
                        return mojmap;
                    }
                } catch (Throwable ignored) {}

                // Strategy 2: Runtime → official via Fabric, then official → mojmap via resolver
                try {
                    String official = getMappingResolver().unmapClassName("official", name);
                    if (official != null && !official.equals(name) && resolver != null) {
                        String mojmap = resolver.officialToMojmap(official);
                        if (mojmap != null && !mojmap.equals(official)) {
                            return mojmap;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * Build a JVM type descriptor for a class name.
     */
    private static String descriptorForType(String typeName) {
        // Handle array types
        int arrayDims = 0;
        while (typeName.endsWith("[]")) {
            arrayDims++;
            typeName = typeName.substring(0, typeName.length() - 2);
        }

        String base;
        switch (typeName) {
            case "void": base = "V"; break;
            case "boolean": base = "Z"; break;
            case "byte": base = "B"; break;
            case "char": base = "C"; break;
            case "short": base = "S"; break;
            case "int": base = "I"; break;
            case "long": base = "J"; break;
            case "float": base = "F"; break;
            case "double": base = "D"; break;
            default:
                // Reference type
                base = "L" + typeName.replace('.', '/') + ";";
        }

        StringBuilder result = new StringBuilder(base);
        for (int i = 0; i < arrayDims; i++) {
            result.insert(0, "[");
        }
        return result.toString();
    }

    /**
     * Build a JVM method descriptor using Mojmap class names for parameter types.
     * This is required for looking up methods in {@link InternalMappingResolver}
     * because its internal tables use Mojmap namespace descriptors.
     *
     * <p>Tries direct mojmap unmap first, then two-step (runtime→official→mojmap)
     * via InternalMappingResolver for intermediary servers.</p>
     */
    private static String buildMojmapDescriptor(Class<?>[] paramTypes) {
        InternalMappingResolver resolver = getMojmapResolver();
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> paramType : paramTypes) {
            String name = paramType.getName();
            if (name.startsWith("net.minecraft.")) {
                // Strategy 1: Direct mojmap unmap via Fabric
                try {
                    String mojmap = getMappingResolver().unmapClassName("mojmap", name);
                    if (mojmap != null && !mojmap.equals(name)) {
                        name = mojmap;
                    }
                } catch (Throwable e) { /* keep original */ }
                // Strategy 2: Two-step via InternalMappingResolver
                if (name.equals(paramType.getName()) && resolver != null) {
                    try {
                        String official = getMappingResolver().unmapClassName("official", name);
                        if (official != null && !official.equals(name)) {
                            String mojmap = resolver.officialToMojmap(official);
                            if (mojmap != null && !mojmap.equals(official)) {
                                name = mojmap;
                            }
                        }
                    } catch (Throwable e) { /* keep original */ }
                }
            }
            sb.append(descriptorForType(name));
        }
        sb.append(")V"); // Using V as placeholder since InternalResolver primarily matches by name+desc key
        return sb.toString();
    }

    /** Invoke a static method by reflection; return null on failure. */
    static Object callStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> cls = forName(className);
            if (cls == null) return null;

            String runtimeMethodName = resolveMojmapMethodName(cls, methodName, paramTypes);

            try {
                Method m = cls.getDeclaredMethod(runtimeMethodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(null, args);
            } catch (NoSuchMethodException e) {
                Method m = cls.getMethod(runtimeMethodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(null, args);
            }
        } catch (IllegalAccessException | InvocationTargetException
                 | NoSuchMethodException e) {
            return null;
        }
    }

    /** Invoke an instance method by reflection; return null on failure. */
    static Object call(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try {
            Class<?> cls = target.getClass();
            String runtimeMethodName = resolveMojmapMethodName(cls, methodName, paramTypes);

            try {
                Method m = cls.getMethod(runtimeMethodName, paramTypes);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) {
                Method m = cls.getDeclaredMethod(runtimeMethodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    /** Invoke a method that may be declared on a supertype. */
    static Object callAny(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), methodName, paramTypes);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    /** Read a field by reflection; return null on failure. */
    @SuppressWarnings("unchecked")
    static <T> T getField(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Class<?> cls = target.getClass();
            String runtimeFieldName = resolveMojmapFieldName(cls, fieldName);
            Field f = findField(cls, runtimeFieldName);
            if (f == null) return null;
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Read a UUID from a Minecraft entity. MC 26.1+ uses
     * {@code getUUID()} (uppercase); 1.18–1.21 used {@code getUuid()}.
     * Tries both.
     */
    static Object callUuid(Object target) {
        if (target == null) return null;
        Object r = callAny(target, "getUUID", new Class<?>[0], new Object[0]);
        if (r != null) return r;
        return callAny(target, "getUuid", new Class<?>[0], new Object[0]);
    }

    /**
     * Get the dimension {@code ResourceKey<Level>} of a Minecraft world.
     * MC 26.1+ renamed the method from {@code getRegistryKey()} to
     * {@code dimension()}. Tries both.
     */
    static Object callDimension(Object world) {
        if (world == null) return null;
        Object r = callAny(world, "dimension", new Class<?>[0], new Object[0]);
        if (r != null) return r;
        return callAny(world, "getRegistryKey", new Class<?>[0], new Object[0]);
    }

    /**
     * Get the value (Identifier / ResourceLocation) of a {@code ResourceKey}.
     * MC 26.1+ renamed {@code location()} to {@code identifier()}.
     * 1.18-1.21 used {@code getValue()}. Tries all three in order.
     */
    static Object callResourceKeyValue(Object key) {
        if (key == null) return null;
        // 1) MC 26.1+: ResourceKey.identifier()
        Object r = callAny(key, "identifier", new Class<?>[0], new Object[0]);
        if (r != null) return r;
        // 2) MC 1.19-1.21: ResourceKey.location() (returned Identifier)
        r = callAny(key, "location", new Class<?>[0], new Object[0]);
        if (r != null) return r;
        // 3) MC 1.18: ResourceKey.getValue() (returned Identifier)
        return callAny(key, "getValue", new Class<?>[0], new Object[0]);
    }

    /**
     * Read a string value from a Minecraft object, handling both legacy and
     * modern (MC 26.1+) return types.
     *
     * <p>MC 1.18–1.21: {@code Entity.getName()} / {@code GameProfile.getName()}
     * return {@code String}. MC 26.1+: they return {@code Component} /
     * {@code String} depending on the method. This helper accepts both:
     * <ol>
     *   <li>If the result is already a {@code String}, return it directly.</li>
     *   <li>Otherwise, try {@code Component.getString()} (MC 26.1+).</li>
     *   <li>Otherwise, fall back to {@code toString()} and strip the
     *       {@code literal(...)} / {@code TextComponent{...}} wrapper that
     *       Component's default {@code toString()} produces.</li>
     * </ol>
     *
     * @return the string, or {@code null} if the source is null
     */
    static String readString(Object source) {
        if (source == null) return null;
        if (source instanceof String) return (String) source;
        try {
            Method m = source.getClass().getMethod("getString");
            Object r = m.invoke(source);
            if (r instanceof String) return (String) r;
        } catch (Throwable ignored) {}
        String s = source.toString();
        // Strip "literal(...)" wrapper that Component.toString() produces on MC 26.1+
        if (s.startsWith("literal(") && s.endsWith(")")) {
            return s.substring("literal(".length(), s.length() - 1);
        }
        return s;
    }

    /**
     * Resolve a Minecraft block (or any Registry entry) to its canonical
     * {@code minecraft:path} id. MC-version-aware.
     *
     * <p>Strategy (in order):
     * <ol>
     *   <li>Use the {@code DefaultedRegistry.getKey(T)} method which returns
     *       a non-null {@code Identifier}. MC 26.1+ has this on both
     *       {@code DefaultedRegistry} (used for the BLOCK registry, since
     *       {@code BuiltInRegistries.BLOCK} is a {@code DefaultedRegistry})
     *       and {@code MappedRegistry}. 1.18–1.21 have it on
     *       {@code SimpleRegistry} and {@code MappedRegistry}.</li>
     *   <li>Fall back to {@code getId(int)} → reverse-lookup key by id.</li>
     *   <li>Fall back to {@code block.builtInRegistryHolder().getKey()}.</li>
     *   <li>Fall back to {@code getResourceKey(T)} → unwrap {@code Optional}.</li>
     * </ol>
     *
     * @param block the Minecraft block instance (or any object registered in
     *              a registry with a {@code getKey(T)} / {@code getId(T)} method)
     * @return the canonical {@code minecraft:path} id, or null on failure
     */
    static String getBlockId(Object block) {
        if (block == null) return null;

        // ── 1. Resolve the BLOCK registry instance ─────────────────
        // MC 26.1+:  BuiltInRegistries.BLOCK is a DefaultedRegistry
        // 1.18-1.21: Registry.BLOCK is a DefaultedRegistry (also at core.Registry on 1.18)
        // We try all known paths in order.
        Object blockRegistry = null;

        // 1a. BuiltInRegistries.BLOCK (MC 26.1+ / 1.19.3+)
        try {
            Class<?> birCls = forName("net.minecraft.core.registries.BuiltInRegistries");
            if (birCls != null) {
                Field f = birCls.getField("BLOCK");
                blockRegistry = f.get(null);
            }
        } catch (Throwable t) { /* fall through */ }

        // 1b. Registry.BLOCK (1.18.2 — net.minecraft.core.Registry)
        if (blockRegistry == null) {
            try {
                Class<?> regCls = forName("net.minecraft.core.Registry");
                if (regCls != null) {
                    Field f = regCls.getField("BLOCK");
                    blockRegistry = f.get(null);
                }
            } catch (Throwable t) { /* fall through */ }
        }

        // 1c. Registries.BLOCK — on 1.19+ this is a ResourceKey, not the registry
        //     instance. Still try as last resort for edge-case modded setups.
        if (blockRegistry == null) {
            try {
                Class<?> regsCls = forName("net.minecraft.core.registries.Registries");
                if (regsCls == null) {
                    regsCls = forName("net.minecraft.registry.Registries");
                }
                if (regsCls != null) {
                    Field f = regsCls.getField("BLOCK");
                    Object maybeReg = f.get(null);
                    // Only use if it looks like a registry (has getKey/getId methods)
                    if (maybeReg != null) {
                        try {
                            maybeReg.getClass().getMethod("getKey", Object.class);
                            blockRegistry = maybeReg;
                        } catch (NoSuchMethodException e) {
                            // It's a ResourceKey, not a registry — skip
                        }
                    }
                }
            } catch (Throwable t) { /* fall through */ }
        }

        if (blockRegistry != null) {
            // DefaultedRegistry.getKey returns Identifier (non-null on MC 26.1+)
            try {
                Method m = blockRegistry.getClass().getMethod("getKey", Object.class);
                Object id = m.invoke(blockRegistry, block);
                if (id != null) {
                    String s = readString(id);
                    if (s != null) return s;
                }
            } catch (Throwable t) { /* fall through */ }

            // MappedRegistry.getKey returns Identifier (may be null for unregistered)
            try {
                Method m = findMethod(blockRegistry.getClass(), "getKey", new Class<?>[]{Object.class});
                if (m != null) {
                    Object id = m.invoke(blockRegistry, block);
                    if (id != null) {
                        String s = readString(id);
                        if (s != null) return s;
                    }
                }
            } catch (Throwable t) { /* fall through */ }

            // MappedRegistry.getResourceKey(T) returns Optional<ResourceKey<T>>
            try {
                Method m = findMethod(blockRegistry.getClass(), "getResourceKey", new Class<?>[]{Object.class});
                if (m != null) {
                    Object rv = m.invoke(blockRegistry, block);
                    if (rv instanceof java.util.Optional) {
                        java.util.Optional<?> opt = (java.util.Optional<?>) rv;
                        if (opt.isPresent()) {
                            Object key = opt.get();
                            Object loc = callResourceKeyValue(key);
                            if (loc != null) {
                                String s = readString(loc);
                                if (s != null) return s;
                            }
                        }
                    }
                }
            } catch (Throwable t) { /* fall through */ }

            // getId returns int (MC 26.1+); reverse-lookup via byId is risky,
            // but as a last resort try getId → toString
            try {
                Method m = findMethod(blockRegistry.getClass(), "getId", new Class<?>[]{Object.class});
                if (m != null) {
                    Object id = m.invoke(blockRegistry, block);
                    if (id instanceof Integer && (Integer) id >= 0) {
                        // Don't return a raw int as a "minecraft:" id — it would
                        // never match user config. Fall through to holder lookup.
                    }
                }
            } catch (Throwable t) { /* fall through */ }
        }

        // 2. Fall back to the block's builtInRegistryHolder (always available on
        //    MC 1.19.3+; MC 26.1+ exposes it as a method).
        try {
            Object holder = callAny(block, "builtInRegistryHolder", new Class<?>[0], new Object[0]);
            if (holder != null) {
                Object key = callAny(holder, "getKey", new Class<?>[0], new Object[0]);
                if (key != null) {
                    Object loc = callResourceKeyValue(key);
                    if (loc != null) {
                        String s = readString(loc);
                        if (s != null) return s;
                    }
                }
            }
        } catch (Throwable t) { /* fall through */ }

        return null;
    }

    /** Construct an instance by reflection. */
    static Object newInstance(String className, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> cls = forName(className);
            if (cls == null) return null;
            Constructor<?> c = cls.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (NoSuchMethodException | IllegalAccessException
                 | InstantiationException | InvocationTargetException e) {
            return null;
        }
    }

    /**
     * Resolve a Mojmap method name to its runtime (obfuscated) name.
     *
     * <p>Delegates to {@link #unmapMojmapMethodName} which uses the injected
     * {@code "mojmap"} namespace in Fabric's {@link MappingResolver}.
     * After injection, the resolver traverses the full chain:
     * {@code mojmap → official → intermediary → named}.</p>
     *
     * @param cls        the runtime class (used only for the {@code net.minecraft.} guard)
     * @param methodName the method name in Mojmap format (e.g. {@code "getServer"})
     * @param paramTypes the parameter types
     * @return the runtime (obfuscated) method name, or the original name if resolution fails
     */
    private static String resolveMojmapMethodName(Class<?> cls, String methodName, Class<?>[] paramTypes) {
        String className = cls.getName();
        if (!className.startsWith("net.minecraft.")) {
            return methodName;
        }
        return unmapMojmapMethodName(methodName, paramTypes);
    }

    /**
     * Resolve a Mojmap field name to its runtime (obfuscated) name.
     *
     * <p>Fabric's {@link MappingResolver} has no field-level mapping API.
     * We use a multi-step resolution strategy:
     * <ol>
     *   <li>Convert runtime class → mojmap class via Fabric's resolver.</li>
     *   <li>Look up the field in {@link InternalMappingResolver}'s tables
     *       (mojmap field → official field).</li>
     *   <li>Convert official class → runtime class via Fabric resolver,
     *       then do type-based field lookup on the runtime class using the
     *       mojmap field name as a candidate (works in dev/named environments).
     *       Falls back to type-constrained field scanning for intermediary.</li>
     * </ol>
     *
     * @param cls       the runtime class
     * @param fieldName the field name in Mojmap format (e.g. {@code "LIGHTNING_BOLT"})
     * @return the runtime field name, or the original name if resolution fails
     */
    private static String resolveMojmapFieldName(Class<?> cls, String fieldName) {
        String className = cls.getName();
        if (!className.startsWith("net.minecraft.")) {
            return fieldName;
        }
        InternalMappingResolver resolver = getMojmapResolver();
        if (resolver != null) {
            try {
                // 1. Runtime Class -> Mojmap Class
                String mojmapClass = getMappingResolver().unmapClassName("mojmap", className);
                if (mojmapClass != null && !mojmapClass.equals(className)) {
                    // 2. Mojmap Field -> Official Field (via internal Tiny v2 tables)
                    String officialField = resolver.resolveFieldName(mojmapClass, fieldName);
                    if (officialField != null && !officialField.equals(fieldName)) {
                        // 3a. Try the official field name directly on the runtime class.
                        //     On intermediary servers, Fabric often keeps field names
                        //     as their official (obfuscated) names.
                        try {
                            cls.getDeclaredField(officialField);
                            return officialField;
                        } catch (NoSuchFieldException ignored) {}
                        try {
                            cls.getField(officialField);
                            return officialField;
                        } catch (NoSuchFieldException ignored) {}

                        // 3b. Enumerate fields on the runtime class, resolve each
                        //     through official→mojmap, and match back to our target.
                        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                            String runtimeName = f.getName();
                            // Try official → mojmap to see if this field is ours
                            String candidateMojmap = resolver.officialToMojmapField(mojmapClass, runtimeName);
                            if (fieldName.equals(candidateMojmap)) {
                                return runtimeName;
                            }
                        }
                        for (java.lang.reflect.Field f : cls.getFields()) {
                            String runtimeName = f.getName();
                            String candidateMojmap = resolver.officialToMojmapField(mojmapClass, runtimeName);
                            if (fieldName.equals(candidateMojmap)) {
                                return runtimeName;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                LOGGER.warning("Field resolution failed for " + fieldName + ": " + e.getMessage());
            }
        }
        // 4. Fallback: return mojmap name — works in dev/named environments.
        return fieldName;
    }

    /**
     * Build a JVM method descriptor from parameter types.
     */
    private static String buildDescriptor(Class<?>[] paramTypes) {
        StringBuilder desc = new StringBuilder("(");
        for (Class<?> paramType : paramTypes) {
            desc.append(descriptorForType(paramType.getName()));
        }
        desc.append(")V");
        return desc.toString();
    }

    /** Resolve a class by name; return null on failure.
     * Includes automatic fallback for MC version changes.
     *
     * <p>For Minecraft 1.18–1.21.x, Mojmap class names (e.g.
     * {@code net.minecraft.server.MinecraftServer}) are resolved via the
     * injected {@code "mojmap"} namespace in Fabric's {@link MappingResolver}.</p>
     */
    static Class<?> forName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            Class<?> result = tryMcMigration(className);
            if (result != null) return result;

            if (className.startsWith("net.minecraft.")) {
                String unmapped = unmapMojmapClassName(className);
                if (!unmapped.equals(className)) {
                    try {
                        return Class.forName(unmapped);
                    } catch (ClassNotFoundException ignored) {}
                }
            }

            return null;
        }
    }

    /**
     * Try common MC version migration paths for renamed/moved classes.
     */
    private static Class<?> tryMcMigration(String className) {
        if ("net.minecraft.text.Text".equals(className)) {
            return tryLoad("net.minecraft.network.chat.Component");
        }
        if ("net.minecraft.text.LiteralText".equals(className)) {
            return tryLoad("net.minecraft.network.chat.Component");
        }
        if ("net.minecraft.text.MutableText".equals(className)) {
            return tryLoad("net.minecraft.network.chat.MutableComponent");
        }
        if ("net.minecraft.registry.Registries".equals(className)) {
            return tryLoad("net.minecraft.core.registries.Registries");
        }
        if ("net.minecraft.util.math.BlockPos".equals(className)) {
            return tryLoad("net.minecraft.core.BlockPos");
        }
        if ("net.minecraft.entity.LightningEntity".equals(className)) {
            return tryLoad("net.minecraft.world.entity.LightningBolt");
        }
        if ("net.minecraft.entity.EntityType".equals(className)) {
            return tryLoad("net.minecraft.world.entity.EntityType");
        }
        if ("net.minecraft.server.world.ServerWorld".equals(className)) {
            return tryLoad("net.minecraft.server.level.ServerLevel");
        }
        if ("net.minecraft.server.network.ServerPlayerEntity".equals(className)) {
            return tryLoad("net.minecraft.server.level.ServerPlayer");
        }
        if ("net.minecraft.server.command.ServerCommandSource".equals(className)) {
            return tryLoad("net.minecraft.commands.CommandSourceStack");
        }
        if ("net.minecraft.fluid.Fluids".equals(className)) {
            return tryLoad("net.minecraft.world.level.material.Fluids");
        }
        if ("net.minecraft.fluid.Fluid".equals(className)) {
            return tryLoad("net.minecraft.world.level.material.Fluid");
        }
        if ("net.minecraft.block.FluidBlock".equals(className)) {
            return tryLoad("net.minecraft.world.level.block.LiquidBlock");
        }
        if ("net.minecraft.block.Blocks".equals(className)) {
            return tryLoad("net.minecraft.world.level.block.Blocks");
        }
        if ("net.minecraft.block.Block".equals(className)) {
            return tryLoad("net.minecraft.world.level.block.Block");
        }
        if ("net.minecraft.block.BlockState".equals(className)) {
            return tryLoad("net.minecraft.world.level.block.state.BlockState");
        }
        
        // Reverse migrations
        if ("net.minecraft.network.chat.Component".equals(className)) {
            return tryLoad("net.minecraft.text.Text");
        }
        if ("net.minecraft.network.chat.TextComponent".equals(className)) {
            return tryLoad("net.minecraft.text.LiteralText");
        }
        if ("net.minecraft.core.registries.Registries".equals(className)) {
            return tryLoad("net.minecraft.registry.Registries");
        }
        if ("net.minecraft.core.BlockPos".equals(className)) {
            return tryLoad("net.minecraft.util.math.BlockPos");
        }
        if ("net.minecraft.world.entity.LightningBolt".equals(className)) {
            return tryLoad("net.minecraft.entity.LightningEntity");
        }
        if ("net.minecraft.world.entity.EntityType".equals(className)) {
            return tryLoad("net.minecraft.entity.EntityType");
        }
        if ("net.minecraft.server.level.ServerLevel".equals(className)) {
            return tryLoad("net.minecraft.server.world.ServerWorld");
        }
        if ("net.minecraft.server.level.ServerPlayer".equals(className)) {
            return tryLoad("net.minecraft.server.network.ServerPlayerEntity");
        }
        if ("net.minecraft.commands.CommandSourceStack".equals(className)) {
            return tryLoad("net.minecraft.server.command.ServerCommandSource");
        }
        if ("net.minecraft.world.level.material.Fluids".equals(className)) {
            return tryLoad("net.minecraft.fluid.Fluids");
        }
        if ("net.minecraft.world.level.material.Fluid".equals(className)) {
            return tryLoad("net.minecraft.fluid.Fluid");
        }
        if ("net.minecraft.world.level.block.LiquidBlock".equals(className)) {
            return tryLoad("net.minecraft.block.FluidBlock");
        }
        if ("net.minecraft.world.level.block.Blocks".equals(className)) {
            return tryLoad("net.minecraft.block.Blocks");
        }
        if ("net.minecraft.world.level.block.Block".equals(className)) {
            return tryLoad("net.minecraft.block.Block");
        }
        if ("net.minecraft.world.level.block.state.BlockState".equals(className)) {
            return tryLoad("net.minecraft.block.BlockState");
        }
        if ("net.minecraft.world.InteractionResult".equals(className)) {
            return tryLoad("net.minecraft.util.ActionResult");
        }
        return null;
    }

    private static Class<?> tryLoad(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Resolve a {@code net.minecraft.*} class by FQN. */
    static Class<?> mc(String className) {
        return forName("net.minecraft." + className);
    }

    /**
     * Find a method in the class hierarchy.
     *
     * <p>Uses {@link Class#getMethod(String, Class[])} as the primary path
     * since it resolves inherited public methods automatically. For non-public
     * methods, falls back to {@link Class#getDeclaredMethod(String, Class[])}.</p>
     *
     * <p>If the method is not found on the class itself, the Java superclass
     * chain is traversed via {@link Class#getSuperclass()} to find the declaring
     * class. The method name is resolved through Mojmap for each class in the
     * hierarchy if necessary.</p>
     */
    static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null) return null;

        // Resolve the method name through Mojmap first.
        String runtimeName = resolveMojmapMethodName(cls, name, paramTypes);

        // Primary path: getMethod resolves inherited public methods automatically.
        try {
            return cls.getMethod(runtimeName, paramTypes);
        } catch (NoSuchMethodException ignored) {}

        // Try getDeclaredMethod on the class itself (for non-public methods).
        try {
            return cls.getDeclaredMethod(runtimeName, paramTypes);
        } catch (NoSuchMethodException ignored) {}

        // Fix: Use Java reflection for superclass traversal instead of broken Mojmap hierarchy
        Class<?> superCls = cls.getSuperclass();
        if (superCls != null && superCls != Object.class) {
            return findMethod(superCls, name, paramTypes);
        }

        return null;
    }

    /**
     * Find a field in the class hierarchy.
     *
     * <p>For Minecraft classes, the field name is first resolved through the
     * Mojmap namespace. If the field is not found on the class itself, the
     * Java superclass chain is traversed via {@link Class#getSuperclass()}.</p>
     */
    private static Field findField(Class<?> cls, String name) {
        if (cls == null) return null;

        // Direct lookup on the class itself first.
        try {
            return cls.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {}

        // Java superclass traversal
        Class<?> superCls = cls.getSuperclass();
        if (superCls != null && superCls != Object.class) {
            return findField(superCls, name);
        }

        return null;
    }
}