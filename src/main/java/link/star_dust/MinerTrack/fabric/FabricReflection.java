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
 * to map method names from intermediary to runtime (obfuscated) names when necessary.</p>
 *
 * <p>In a named environment (development or modern Fabric servers with "named" mappings),
 * class and method names are already human-readable. In an obfuscated production environment,
 * this class will automatically resolve the correct obfuscated names.</p>
 *
 * <p>For Minecraft 1.18–1.21.x, the {@link InternalMappingResolver} provides an additional
 * "mojmap" namespace that maps Mojang's official deobfuscated names to runtime names.
 * This is injected during the pre-launch phase by {@link MinerTrackPreLaunch}.</p>
 */
final class FabricReflection {
    private static final Logger LOGGER = Logger.getLogger("MinerTrack/FabricReflection");
    private static final MappingResolver MAPPING_RESOLVER = FabricLoader.getInstance().getMappingResolver();
    private static final String INTERMEDIARY_NAMESPACE = "intermediary";
    private static final String RUNTIME_NAMESPACE = FabricLoader.getInstance().isDevelopmentEnvironment() ? "named" : getRuntimeNamespace();

    // Lazily initialised Mojmap resolver (only for 1.18–1.21.x)
    private static InternalMappingResolver mojmapResolver;

    /** Determine the runtime namespace based on environment */
    private static String getRuntimeNamespace() {
        try {
            // Check if we're in a named environment by trying to load a known class
            Class.forName("net.minecraft.text.Text");
            return "named";
        } catch (ClassNotFoundException e) {
            // Likely obfuscated, use intermediary as fallback
            return "intermediary";
        }
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
            // If already cached and injected, the tables will be available
            return mojmapResolver;
        } catch (Exception e) {
            LOGGER.fine("Mojmap resolver not available: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolve an intermediary class name to its runtime (obfuscated) name.
     * @param intermediaryName class name in intermediary format (e.g. "net.minecraft.class_2561")
     * @return runtime class name, or null if resolution fails
     */
    static String unmapClassName(String intermediaryName) {
        try {
            return MAPPING_RESOLVER.unmapClassName(INTERMEDIARY_NAMESPACE, intermediaryName);
        } catch (Throwable t) {
            return intermediaryName;
        }
    }

    /**
     * Resolve a Mojmap class name to its runtime (obfuscated) name.
     * Uses the "mojmap" namespace injected by {@link InternalMappingResolver}.
     *
     * @param mojmapName class name in Mojmap format (e.g. "net.minecraft.server.network.ServerPlayerEntity")
     * @return runtime class name, or the original name if resolution fails
     */
    static String unmapMojmapClassName(String mojmapName) {
        InternalMappingResolver resolver = getMojmapResolver();
        if (resolver != null) {
            String official = resolver.mojmapToOfficial(mojmapName);
            if (!official.equals(mojmapName)) {
                // The official name is the obfuscated name — use it directly
                return official;
            }
        }
        // Fallback: try the mojmap namespace in the standard resolver
        try {
            return MAPPING_RESOLVER.unmapClassName("mojmap", mojmapName);
        } catch (Throwable t) {
            return mojmapName;
        }
    }

    /**
     * Resolve an intermediary method name to its runtime (obfuscated) name.
     * @param className class name in intermediary format
     * @param methodName method name in intermediary format
     * @param paramTypes parameter types in intermediary format (as Class objects)
     * @return runtime method name, or the original name if resolution fails
     */
    static String unmapMethodName(String className, String methodName, Class<?>[] paramTypes) {
        try {
            // Convert intermediary param type names to runtime names
            String[] runtimeParamTypes = new String[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                runtimeParamTypes[i] = unmapClassName(paramTypes[i].getName());
            }
            // Use mapMethodName with from=intermediary, to=runtime
            // Signature: mapMethodName(fromNamespace, toNamespace, methodName, descriptor)
            // Note: Fabric 0.14.x MappingResolver.mapMethodName takes 4 args:
            //       mapMethodName(String fromNamespace, String toNamespace, String name, String descriptor)
            // The className is NOT used - method names are global in intermediary.
            // We need to build a method descriptor string
            StringBuilder desc = new StringBuilder("(");
            for (String paramType : runtimeParamTypes) {
                desc.append(descriptorForType(paramType));
            }
            desc.append(")V"); // return type doesn't matter for lookup
            return MAPPING_RESOLVER.mapMethodName(INTERMEDIARY_NAMESPACE, RUNTIME_NAMESPACE, methodName, desc.toString());
        } catch (Throwable t) {
            return methodName;
        }
    }

    /**
     * Resolve a Mojmap method name to its runtime (obfuscated) name.
     * Uses the "mojmap" namespace injected by {@link InternalMappingResolver}.
     *
     * @param mojmapClass  the Mojmap class name
     * @param mojmapMethod the Mojmap method name
     * @return runtime method name, or the original name if resolution fails
     */
    static String unmapMojmapMethodName(String mojmapClass, String mojmapMethod) {
        InternalMappingResolver resolver = getMojmapResolver();
        if (resolver != null) {
            String official = resolver.resolveMethodName(mojmapClass, mojmapMethod);
            if (!official.equals(mojmapMethod)) {
                return official;
            }
        }
        // Fallback: try the mojmap namespace in the standard resolver
        try {
            return MAPPING_RESOLVER.mapMethodName("mojmap", RUNTIME_NAMESPACE, mojmapMethod, "()V");
        } catch (Throwable t) {
            return mojmapMethod;
        }
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

    /** Invoke a static method by reflection; return null on failure. */
    static Object callStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> cls = Class.forName(className);
            Method m = findMethod(cls, methodName, paramTypes);
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(null, args);
            }
            // Fallback: try to find a compatible method by name
            // and parameter count (handles classloader / signature
            // mismatches when types come from different loaders,
            // e.g. between Minecraft versions).
            Method m2 = findMethodByNameAndParamCount(cls, methodName, args == null ? 0 : args.length);
            if (m2 != null) {
                m2.setAccessible(true);
                return m2.invoke(null, args == null ? new Object[0] : args);
            }
            return null;
        } catch (ClassNotFoundException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    /** Invoke an instance method by reflection; return null on failure. */
    static Object call(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName, paramTypes);
            return m.invoke(target, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // Fallback: try to find a compatible method by name and
            // parameter count (handles classloader / signature
            // mismatches when types come from different loaders).
            try {
                Method m2 = findMethodByNameAndParamCount(target.getClass(), methodName, args == null ? 0 : args.length);
                if (m2 == null) return null;
                m2.setAccessible(true);
                return m2.invoke(target, args == null ? new Object[0] : args);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                return null;
            }
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
            // Fallback: try a permissive lookup by name and
            // parameter count when exact signature lookup fails
            // due to classloader/type mismatches.
            try {
                Method m2 = findMethodByNameAndParamCount(target.getClass(), methodName, args == null ? 0 : args.length);
                if (m2 == null) return null;
                m2.setAccessible(true);
                return m2.invoke(target, args == null ? new Object[0] : args);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                return null;
            }
        }
    }

    /** Read a field by reflection; return null on failure. */
    @SuppressWarnings("unchecked")
    static <T> T getField(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Field f = findField(target.getClass(), fieldName);
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
            Class<?> cls = Class.forName(className);
            Constructor<?> c = cls.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InstantiationException | InvocationTargetException e) {
            return null;
        }
    }

    /** Permissive lookup: find a method by name+param count, walking class hierarchy and interfaces. */
    private static Method findMethodByNameAndParamCount(Class<?> cls, String name, int paramCount) {
        Class<?> cur = cls;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            for (Class<?> iface : cur.getInterfaces()) {
                Method m = findMethodByNameAndParamCount(iface, name, paramCount);
                if (m != null) return m;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** Resolve a class by name; return null on failure.
     * Includes automatic fallback for MC version changes:
     * - net.minecraft.text.Text → net.minecraft.network.chat.Component
     * - net.minecraft.registry.* → net.minecraft.core.registries.*
     * - net.minecraft.util.math.BlockPos → net.minecraft.core.BlockPos
     *
     * <p>For Minecraft 1.18–1.21.x, Mojmap class names (e.g.
     * {@code net.minecraft.server.network.ServerPlayerEntity}) are resolved
     * via the {@link InternalMappingResolver} which maps them to their
     * official (obfuscated) runtime names.</p>
     */
    static Class<?> forName(String className) {
        try {
            // Try direct load first (for already unmapped or non-Minecraft classes)
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            // Try MC version migration fallbacks
            Class<?> result = tryMcMigration(className);
            if (result != null) return result;

            // Try Mojmap resolution for net.minecraft classes (1.18–1.21.x)
            if (className.startsWith("net.minecraft.")) {
                String unmapped = unmapMojmapClassName(className);
                if (!unmapped.equals(className)) {
                    try {
                        return Class.forName(unmapped);
                    } catch (ClassNotFoundException ignored) {}
                }
            }

            // If it looks like an intermediary name, try unmapping
            if (className.contains("class_") || className.startsWith("net.minecraft.")) {
                try {
                    String unmapped = unmapClassName(className);
                    if (!unmapped.equals(className)) {
                        return Class.forName(unmapped);
                    }
                } catch (ClassNotFoundException ignored) {}
            }
            return null;
        }
    }

    /**
     * Try common MC version migration paths for renamed/moved classes.
     */
    private static Class<?> tryMcMigration(String className) {
        // net.minecraft.text.Text → net.minecraft.network.chat.Component (MC 26.1+)
        if ("net.minecraft.text.Text".equals(className)) {
            return tryLoad("net.minecraft.network.chat.Component");
        }
        if ("net.minecraft.text.LiteralText".equals(className)) {
            // LiteralText removed; Component.literal() is the replacement
            return tryLoad("net.minecraft.network.chat.Component");
        }
        // net.minecraft.text.MutableText → net.minecraft.network.chat.MutableComponent
        if ("net.minecraft.text.MutableText".equals(className)) {
            return tryLoad("net.minecraft.network.chat.MutableComponent");
        }
        // net.minecraft.registry.Registries → net.minecraft.core.registries.Registries
        if ("net.minecraft.registry.Registries".equals(className)) {
            return tryLoad("net.minecraft.core.registries.Registries");
        }
        // net.minecraft.util.math.BlockPos → net.minecraft.core.BlockPos
        if ("net.minecraft.util.math.BlockPos".equals(className)) {
            return tryLoad("net.minecraft.core.BlockPos");
        }
        // net.minecraft.entity.LightningEntity → net.minecraft.world.entity.LightningBolt
        if ("net.minecraft.entity.LightningEntity".equals(className)) {
            return tryLoad("net.minecraft.world.entity.LightningBolt");
        }
        // net.minecraft.entity.EntityType → net.minecraft.world.entity.EntityType
        if ("net.minecraft.entity.EntityType".equals(className)) {
            return tryLoad("net.minecraft.world.entity.EntityType");
        }
        // net.minecraft.server.world.ServerWorld → net.minecraft.server.level.ServerLevel
        if ("net.minecraft.server.world.ServerWorld".equals(className)) {
            return tryLoad("net.minecraft.server.level.ServerLevel");
        }
        // net.minecraft.server.network.ServerPlayerEntity → net.minecraft.server.level.ServerPlayer
        if ("net.minecraft.server.network.ServerPlayerEntity".equals(className)) {
            return tryLoad("net.minecraft.server.level.ServerPlayer");
        }
        // net.minecraft.server.command.ServerCommandSource → net.minecraft.commands.CommandSourceStack
        if ("net.minecraft.server.command.ServerCommandSource".equals(className)) {
            return tryLoad("net.minecraft.commands.CommandSourceStack");
        }
        // net.minecraft.fluid.Fluids → net.minecraft.world.level.material.Fluids (MC 1.21+)
        if ("net.minecraft.fluid.Fluids".equals(className)) {
            return tryLoad("net.minecraft.world.level.material.Fluids");
        }
        // net.minecraft.fluid.Fluid → net.minecraft.world.level.material.Fluid
        if ("net.minecraft.fluid.Fluid".equals(className)) {
            return tryLoad("net.minecraft.world.level.material.Fluid");
        }
        // net.minecraft.block.FluidBlock → net.minecraft.world.level.block.LiquidBlock (MC 26.1+)
        if ("net.minecraft.block.FluidBlock".equals(className)) {
            return tryLoad("net.minecraft.world.level.block.LiquidBlock");
        }
        // net.minecraft.block.Blocks → net.minecraft.world.level.block.Blocks
        if ("net.minecraft.block.Blocks".equals(className)) {
            return tryLoad("net.minecraft.world.level.block.Blocks");
        }
        if ("net.minecraft.block.Block".equals(className)) {
            return tryLoad("net.minecraft.world.level.block.Block");
        }
        // net.minecraft.block.BlockState → net.minecraft.world.level.block.state.BlockState
        if ("net.minecraft.block.BlockState".equals(className)) {
            return tryLoad("net.minecraft.world.level.block.state.BlockState");
        }
        // Reverse migrations (new name → old, for newer Fabric API versions)
        if ("net.minecraft.network.chat.Component".equals(className)) {
            return tryLoad("net.minecraft.text.Text");
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
     * Find a method in the class hierarchy including interfaces.
     * Uses getMethod (public API) as the primary path since it
     * resolves interface methods automatically.
     *
     * <p>For Minecraft classes on 1.18–1.21.x, if the method is not found
     * directly, the Mojmap superclass chain is traversed via
     * {@link InternalMappingResolver#getSuperclass(String)} before falling
     * back to Java's native superclass walk. This ensures that methods
     * declared on superclasses within the Mojmap namespace are found even
     * when the runtime class hierarchy differs from the Mojmap hierarchy.</p>
     */
    static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null) return null;
        try {
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {}

        // Try unmapping the method name if this is a Minecraft class
        String className = cls.getName();
        if (className.startsWith("net.minecraft.")) {
            String unmappedName = unmapMethodName(className, name, paramTypes);
            if (!unmappedName.equals(name)) {
                try {
                    return cls.getMethod(unmappedName, paramTypes);
                } catch (NoSuchMethodException ignored2) {}
            }
        }

        // Mojmap superclass traversal: if the class is a net.minecraft type
        // and the Mojmap resolver is available, walk the Mojmap superclass
        // chain to find the declaring class for the method.
        if (className.startsWith("net.minecraft.")) {
            InternalMappingResolver resolver = getMojmapResolver();
            if (resolver != null) {
                // Convert runtime class name to Mojmap name
                String mojmapClass = resolver.officialToMojmap(className);
                if (!mojmapClass.equals(className)) {
                    Method m = findMethodInMojmapHierarchy(resolver, mojmapClass, name, paramTypes);
                    if (m != null) return m;
                }
            }
        }

        // Fallback: walk declared methods up the hierarchy
        Class<?> cur = cls;
        while (cur != null) {
            try {
                return cur.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {}

            // Also check interfaces at each level
            for (Class<?> iface : cur.getInterfaces()) {
                Method m = findMethod(iface, name, paramTypes);
                if (m != null) return m;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * Walk the Mojmap superclass chain to find a method declared on a
     * superclass within the Mojmap namespace. This is used instead of
     * generic recursion when the Mojmap resolver is available.
     */
    private static Method findMethodInMojmapHierarchy(
            InternalMappingResolver resolver, String mojmapClass,
            String methodName, Class<?>[] paramTypes) {
        String current = resolver.getSuperclass(mojmapClass);
        while (current != null && !"java.lang.Object".equals(current)) {
            String officialClass = resolver.mojmapToOfficial(current);
            if (!officialClass.equals(current)) {
                try {
                    Class<?> superCls = Class.forName(officialClass);
                    try {
                        return superCls.getDeclaredMethod(methodName, paramTypes);
                    } catch (NoSuchMethodException ignored) {}
                    // Also try unmapped method name
                    String unmapped = unmapMethodName(officialClass, methodName, paramTypes);
                    if (!unmapped.equals(methodName)) {
                        try {
                            return superCls.getDeclaredMethod(unmapped, paramTypes);
                        } catch (NoSuchMethodException ignored) {}
                    }
                } catch (ClassNotFoundException ignored) {}
            }
            current = resolver.getSuperclass(current);
        }
        return null;
    }

    /**
     * Find a field in the class hierarchy.
     *
     * <p>For Minecraft classes on 1.18–1.21.x, if the field is not found
     * directly, the Mojmap superclass chain is traversed via
     * {@link InternalMappingResolver#getSuperclass(String)} before falling
     * back to Java's native superclass walk.</p>
     */
    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
            cur = cur.getSuperclass();
        }

        // Mojmap superclass traversal: if the class is a net.minecraft type
        // and the Mojmap resolver is available, walk the Mojmap superclass
        // chain to find the declaring class for the field.
        if (cls != null && cls.getName().startsWith("net.minecraft.")) {
            InternalMappingResolver resolver = getMojmapResolver();
            if (resolver != null) {
                String mojmapClass = resolver.officialToMojmap(cls.getName());
                if (!mojmapClass.equals(cls.getName())) {
                    Field f = findFieldInMojmapHierarchy(resolver, mojmapClass, name);
                    if (f != null) return f;
                }
            }
        }

        return null;
    }

    /**
     * Walk the Mojmap superclass chain to find a field declared on a
     * superclass within the Mojmap namespace.
     */
    private static Field findFieldInMojmapHierarchy(
            InternalMappingResolver resolver, String mojmapClass, String fieldName) {
        String current = resolver.getSuperclass(mojmapClass);
        while (current != null && !"java.lang.Object".equals(current)) {
            String officialClass = resolver.mojmapToOfficial(current);
            if (!officialClass.equals(current)) {
                try {
                    Class<?> superCls = Class.forName(officialClass);
                    try {
                        return superCls.getDeclaredField(fieldName);
                    } catch (NoSuchFieldException ignored) {}
                } catch (ClassNotFoundException ignored) {}
            }
            current = resolver.getSuperclass(current);
        }
        return null;
    }
}