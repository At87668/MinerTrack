package link.star_dust.MinerTrack.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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
 */
final class FabricReflection {
    private static final MappingResolver MAPPING_RESOLVER = FabricLoader.getInstance().getMappingResolver();
    private static final String INTERMEDIARY_NAMESPACE = "intermediary";
    private static final String RUNTIME_NAMESPACE = FabricLoader.getInstance().isDevelopmentEnvironment() ? "named" : getRuntimeNamespace();

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

    /** Resolve a class by name; return null on failure. */
    static Class<?> forName(String className) {
        try {
            // Try direct load first (for already unmapped or non-Minecraft classes)
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
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

    /** Resolve a {@code net.minecraft.*} class by FQN. */
    static Class<?> mc(String className) {
        return forName("net.minecraft." + className);
    }

    /**
     * Find a method in the class hierarchy including interfaces.
     * Uses getMethod (public API) as the primary path since it
     * resolves interface methods automatically.
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

    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
            cur = cur.getSuperclass();
        }
        return null;
    }
}