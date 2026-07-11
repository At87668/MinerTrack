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
            // Use forName() which includes Mojmap resolution for 1.18–1.21.x.
            // Direct Class.forName() would fail on obfuscated servers when the
            // caller passes a Mojmap class name like "net.minecraft.server.MinecraftServer".
            Class<?> cls = forName(className);
            if (cls == null) return null;

            // Resolve the method name through Mojmap if this is a net.minecraft class.
            // On obfuscated servers the method name like "getServer" must be
            // translated to its official (obfuscated) name.
            String runtimeMethodName = resolveMojmapMethodName(cls, methodName, paramTypes);

            try {
                Method m = cls.getDeclaredMethod(runtimeMethodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(null, args);
            } catch (NoSuchMethodException e) {
                // Fallback: try getMethod (public API) which resolves
                // inherited public methods automatically.
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

            // Resolve the method name through Mojmap if this is a net.minecraft class.
            // On obfuscated servers the method name must be translated.
            String runtimeMethodName = resolveMojmapMethodName(cls, methodName, paramTypes);

            // Use getMethod (public API) which resolves inherited methods
            // automatically without manual hierarchy walking.
            Method m = cls.getMethod(runtimeMethodName, paramTypes);
            return m.invoke(target, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // Fallback: try getDeclaredMethod on the runtime class.
            // This handles non-public methods declared directly on the
            // target's class (e.g. package-private Minecraft internals).
            try {
                Class<?> cls = target.getClass();
                String runtimeMethodName = resolveMojmapMethodName(cls, methodName, paramTypes);
                Method m = cls.getDeclaredMethod(runtimeMethodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
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
            return null;
        }
    }

    /** Read a field by reflection; return null on failure. */
    @SuppressWarnings("unchecked")
    static <T> T getField(Object target, String fieldName) {
        if (target == null) return null;
        try {
            // Resolve the field name through Mojmap first, then find it
            // in the class hierarchy.
            String runtimeFieldName = resolveMojmapFieldName(target.getClass(), fieldName);
            Field f = findField(target.getClass(), runtimeFieldName);
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
            // Use forName() which includes Mojmap resolution for 1.18–1.21.x.
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
     * Resolve a Mojmap method name to its runtime (obfuscated) name for the
     * given class. Uses the Mojmap resolver when available, otherwise falls
     * back to the intermediary-based resolver.
     *
     * @param cls        the runtime class
     * @param methodName the method name (in Mojmap format for net.minecraft classes)
     * @param paramTypes the parameter types
     * @return the runtime (obfuscated) method name, or the original name if resolution fails
     */
    private static String resolveMojmapMethodName(Class<?> cls, String methodName, Class<?>[] paramTypes) {
        String className = cls.getName();
        if (!className.startsWith("net.minecraft.")) {
            return methodName;
        }
        // Try Mojmap resolver first (1.18–1.21.x).
        InternalMappingResolver resolver = getMojmapResolver();
        if (resolver != null) {
            String mojmapClass = resolver.officialToMojmap(className);
            if (!mojmapClass.equals(className)) {
                String official = resolver.resolveMethodName(mojmapClass, methodName);
                if (!official.equals(methodName)) {
                    return official;
                }
            }
        }
        // Fallback: try intermediary resolver.
        String unmapped = unmapMethodName(className, methodName, paramTypes);
        if (!unmapped.equals(methodName)) {
            return unmapped;
        }
        return methodName;
    }

    /**
     * Resolve a Mojmap field name to its runtime (obfuscated) name for the
     * given class. Uses the Mojmap resolver when available.
     *
     * @param cls       the runtime class
     * @param fieldName the field name (in Mojmap format for net.minecraft classes)
     * @return the runtime (obfuscated) field name, or the original name if resolution fails
     */
    private static String resolveMojmapFieldName(Class<?> cls, String fieldName) {
        String className = cls.getName();
        if (!className.startsWith("net.minecraft.")) {
            return fieldName;
        }
        InternalMappingResolver resolver = getMojmapResolver();
        if (resolver != null) {
            String mojmapClass = resolver.officialToMojmap(className);
            if (!mojmapClass.equals(className)) {
                String official = resolver.resolveFieldName(mojmapClass, fieldName);
                if (!official.equals(fieldName)) {
                    return official;
                }
            }
        }
        return fieldName;
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
     * Find a method in the class hierarchy.
     *
     * <p>Uses {@link Class#getMethod(String, Class[])} as the primary path
     * since it resolves inherited public methods automatically without
     * manual hierarchy walking. For non-public methods, falls back to
     * {@link Class#getDeclaredMethod(String, Class[])} on the declaring
     * class directly.
     *
     * <p>For Minecraft classes on 1.18–1.21.x, the method name is first
     * resolved through the Mojmap namespace — the caller passes a Mojmap
     * method name (e.g. {@code "getServer"}), and we translate it to the
     * official (obfuscated) name before looking it up on the runtime class.
     * If the Mojmap resolver is unavailable, the intermediary-based resolver
     * is used as fallback.
     *
     * <p>If none of the above finds the method, the Mojmap superclass chain
     * is traversed to handle methods declared on Mojmap superclasses whose
     * runtime hierarchy may differ from the compile-time hierarchy.</p>
     *
     * <p>No generic recursive hierarchy walk is performed — the declaring
     * class is either resolved via Mojmap or assumed to be the class itself.</p>
     */
    static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null) return null;

        // Resolve the method name through Mojmap first.
        // On obfuscated 1.18–1.21.x servers, the caller passes a Mojmap name
        // (e.g. "getServer") and we need the obfuscated name to look it up.
        String runtimeName = resolveMojmapMethodName(cls, name, paramTypes);

        // Primary path: getMethod resolves inherited public methods automatically.
        try {
            return cls.getMethod(runtimeName, paramTypes);
        } catch (NoSuchMethodException ignored) {}

        // Try getDeclaredMethod on the class itself (for non-public methods).
        try {
            return cls.getDeclaredMethod(runtimeName, paramTypes);
        } catch (NoSuchMethodException ignored) {}

        // Mojmap superclass traversal: if the class is a net.minecraft type
        // and the Mojmap resolver is available, walk the Mojmap superclass
        // chain to find the declaring class for the method. This replaces
        // the generic recursive hierarchy walk with a Mojmap-aware traversal.
        String className = cls.getName();
        if (className.startsWith("net.minecraft.")) {
            InternalMappingResolver resolver = getMojmapResolver();
            if (resolver != null) {
                String mojmapClass = resolver.officialToMojmap(className);
                if (!mojmapClass.equals(className)) {
                    Method m = findMethodInMojmapHierarchy(resolver, mojmapClass, runtimeName, paramTypes);
                    if (m != null) return m;
                }
            }
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
                } catch (ClassNotFoundException ignored) {}
            }
            current = resolver.getSuperclass(current);
        }
        return null;
    }

    /**
     * Find a field in the class hierarchy.
     *
     * <p>For Minecraft classes on 1.18–1.21.x, the field name is first
     * resolved through the Mojmap namespace — the caller passes a Mojmap
     * field name (e.g. {@code "LIGHTNING_BOLT"}), and we translate it to
     * the official (obfuscated) name before looking it up on the runtime
     * class. If the Mojmap resolver is unavailable, the field name is
     * used as-is.
     *
     * <p>If the field is not found on the class itself, the Mojmap
     * superclass chain is traversed via
     * {@link InternalMappingResolver#getSuperclass(String)} instead of
     * a generic Java superclass walk.</p>
     *
     * <p>No generic recursive hierarchy walk is performed — the declaring
     * class is either resolved via Mojmap or assumed to be the class itself.</p>
     */
    private static Field findField(Class<?> cls, String name) {
        if (cls == null) return null;

        // Resolve the field name through Mojmap first.
        String runtimeName = resolveMojmapFieldName(cls, name);

        // Direct lookup on the class itself first.
        try {
            return cls.getDeclaredField(runtimeName);
        } catch (NoSuchFieldException ignored) {}

        // Mojmap superclass traversal: if the class is a net.minecraft type
        // and the Mojmap resolver is available, walk the Mojmap superclass
        // chain to find the declaring class for the field.
        if (cls.getName().startsWith("net.minecraft.")) {
            InternalMappingResolver resolver = getMojmapResolver();
            if (resolver != null) {
                String mojmapClass = resolver.officialToMojmap(cls.getName());
                if (!mojmapClass.equals(cls.getName())) {
                    Field f = findFieldInMojmapHierarchy(resolver, mojmapClass, runtimeName);
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