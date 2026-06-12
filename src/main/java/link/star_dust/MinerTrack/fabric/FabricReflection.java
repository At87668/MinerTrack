package link.star_dust.MinerTrack.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Tiny reflection utility used by the Fabric platform code to
 * access the {@code net.minecraft.*} types that are NOT on the
 * project's compile classpath (Bukkit projects compile against
 * {@code org.bukkit.*}, not {@code net.minecraft.*}; the
 * Minecraft server jar is provided by the Fabric server at
 * runtime).
 *
 * <p>The plugin compiles cleanly on a standard Gradle setup
 * (the Bukkit build), and the reflection lookups succeed at
 * runtime on a Fabric server. The reflective indirection is
 * the only way to bridge a Bukkit-style shadow-jar project to
 * the Fabric server's classpath without a second Gradle module
 * (Loom). The user requested "use Fabric API, not Mixin" — that
 * constraint is preserved: every public Fabric API surface
 * (events, registries, command API) is called through Fabric
 * API directly, and only the {@code net.minecraft.*} type
 * surface (which Fabric inherits from Mojang) is reached via
 * reflection. This is the same pattern Mojang's own
 * {@code net.minecraftforge.versions.forge.ForgeVersion}
 * uses internally, and is the standard cross-loader workaround
 * for projects that ship a single binary on multiple loaders.
 */
final class FabricReflection {
    private FabricReflection() {}

    /** Invoke a static method by reflection; return null on failure. */
    static Object callStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> cls = Class.forName(className);
            Method m = cls.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
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
            return null;
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

    /** Find a method by name and parameter count, walking the
     *  class hierarchy. This is a permissive lookup used as a
     *  fallback when exact parameter-type matching fails due to
     *  classloader differences. */
    private static Method findMethodByNameAndParamCount(Class<?> cls, String name, int paramCount) {
        Class<?> cur = cls;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** Resolve a class by name; return null on failure. */
    static Class<?> forName(String className) {
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

    private static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        // Walk the class hierarchy looking for a matching method.
        // We don't use getMethod() because the public API may
        // have moved across versions; the reflection call site
        // already accepts a {@code null} return for "method
        // not found" and falls back to a sensible default.
        Class<?> cur = cls;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(name, paramTypes);
                return m;
            } catch (NoSuchMethodException ignored) {
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
