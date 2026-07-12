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

    /** Determine the runtime namespace based on environment */
    private static String getRuntimeNamespace() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return "named";
        }
        return "intermediary";
    }

    private FabricReflection() {}

    /**
     * Get or create the Mojmap resolver. Returns null if Mojmap is not required
     * for the current Minecraft version or if the resolver could not be initialised.
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
        // Strategy 1: Direct mojmap → runtime.
        try {
            return getMappingResolver().unmapClassName("mojmap", mojmapName);
        } catch (Throwable t) {
            // Fall through to strategy 2.
        }

        // Strategy 2: Two-step via InternalMappingResolver.
        InternalMappingResolver resolver = getMojmapResolver();
        if (resolver != null) {
            String official = resolver.mojmapToOfficial(mojmapName);
            if (official != null && !official.equals(mojmapName)) {
                try {
                    return getMappingResolver().unmapClassName("official", official);
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
     */
    private static String inferMojmapClassFromParamTypes(Class<?>[] paramTypes) {
        for (Class<?> pt : paramTypes) {
            String name = pt.getName();
            if (name.startsWith("net.minecraft.")) {
                try {
                    String mojmap = getMappingResolver().unmapClassName("mojmap", name);
                    if (mojmap != null && !mojmap.equals(name)) {
                        return mojmap;
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
     */
    private static String buildMojmapDescriptor(Class<?>[] paramTypes) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> paramType : paramTypes) {
            String name = paramType.getName();
            if (name.startsWith("net.minecraft.")) {
                try {
                    // Convert runtime class name back to Mojmap for the descriptor
                    name = getMappingResolver().unmapClassName("mojmap", name);
                } catch (Throwable e) {
                    // Keep original if conversion fails
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
     * <p>Fabric's {@link MappingResolver} has no field resolution API.
     * We use the manual Tiny v2 parsing tables in {@link InternalMappingResolver},
     * which map {@code official → mojmap}. We first convert the runtime class
     * name to its Mojmap equivalent, look up the field to get its Official name,
     * and then convert that Official name to the Runtime name.</p>
     *
     * <p>Resolution strategy:
     * <ol>
     *   <li>Convert runtime → mojmap class name via Fabric's resolver.</li>
     *   <li>Look up the field in {@link InternalMappingResolver}'s tables
     *       (mojmap field → official field).</li>
     *   <li>Convert official field → runtime field via Fabric's resolver.</li>
     * </ol>
     *
     * @param cls       the runtime class
     * @param fieldName the field name in Mojmap format (e.g. {@code "LIGHTNING_BOLT"})
     * @return the runtime (obfuscated) field name, or the original name if resolution fails
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
                    // 2. Mojmap Field -> Official Field
                    String officialField = resolver.resolveFieldName(mojmapClass, fieldName);
                    if (officialField != null && !officialField.equals(fieldName)) {
                        // 3. Official Field -> Runtime Field (CRITICAL FIX)
                        return getMappingResolver().unmapClassName("official", officialField);
                    }
                }
            } catch (Throwable e) {
                LOGGER.warning("Field resolution failed for " + fieldName + ": " + e.getMessage());
            }
        }
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