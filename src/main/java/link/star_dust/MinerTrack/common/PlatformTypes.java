package link.star_dust.MinerTrack.common;

import java.lang.reflect.Method;

/**
 * Platform-classifier helpers for use in {@code common} and
 * {@code core} code paths.
 *
 * <p>The v2 design treats {@code common} and {@code core} as
 * platform-neutral: the code there may NOT import
 * {@code org.bukkit.*} types directly, because the shadow JAR
 * excludes the {@code org/bukkit/} prefix and the runtime
 * classpath on a Fabric server does not provide a Bukkit
 * implementation. The v1 legacy code had several
 * {@code org.bukkit.configuration.ConfigurationSection}
 * references (a Bukkit-only class) that survive in the
 * merger / engine / config code because they all started life
 * on the Bukkit build.
 *
 * <p>The fix is to identify Bukkit sections by class NAME
 * rather than by class literal. A class literal
 * ({@code X.class}) causes the JVM to verify the class is
 * loadable at the moment any code path that mentions it is
 * class-loaded, which on a Fabric server triggers
 * {@link ClassNotFoundException} on the first invocation of
 * the merger / engine / config code path. Comparing
 * {@code v.getClass().getName()} against the literal
 * {@code "org.bukkit.configuration.ConfigurationSection"}
 * lets the code path no-op the Bukkit branch when the class
 * isn't on the runtime classpath.
 *
 * <p>The {@link #isConfigurationSection(Object)} helper here
 * centralises that check so the merger / engine / config code
 * stays free of any direct {@code org.bukkit.*} reference. The
 * small {@link #getKeys(Object)} helper extracts a
 * section's direct keys (also a Bukkit-only operation) via
 * reflection, so the merger can iterate a Bukkit
 * {@code ConfigurationSection} without ever referencing its
 * class literal.
 */
public final class PlatformTypes {
    /** Bukkit's {@code ConfigurationSection} class name. */
    public static final String BUKKIT_SECTION = "org.bukkit.configuration.ConfigurationSection";
    /** Bukkit's {@code MemorySection} class name (Bukkit's
     *  internal section implementation; some Bukkit
     *  configurations expose it directly). */
    public static final String BUKKIT_MEMORY_SECTION = "org.bukkit.configuration.MemorySection";

    private PlatformTypes() {}

    /**
     * Return {@code true} if {@code v} is a Bukkit
     * {@code ConfigurationSection} (or any subclass thereof,
     * including {@code MemorySection}). The check is
     * classloader-safe: it compares
     * {@code v.getClass().getName()} against the FQN rather
     * than using {@code instanceof} (which would force the
     * JVM to verify the Bukkit class is loadable, and fail
     * on Fabric).
     */
    public static boolean isConfigurationSection(Object v) {
        if (v == null) return false;
        String name = v.getClass().getName();
        if (BUKKIT_SECTION.equals(name) || BUKKIT_MEMORY_SECTION.equals(name)) return true;
        // Subclass check via the same getName() walk — if the
        // value's class is in the Bukkit config package and
        // the JVM can resolve the parent class (i.e. we are on
        // a Bukkit classpath), we treat it as a section. We
        // don't do an exhaustive isAssignableFrom because
        // that would again force the Bukkit class to be
        // loadable.
        if (name.startsWith("org.bukkit.configuration.")) {
            // The class IS in the Bukkit config package; that
            // alone is enough to know it's some kind of
            // section (YAML sections in Bukkit all live in
            // that package). This is safe on both platforms:
            // on Fabric, the check returns false (no Bukkit
            // class is ever loaded); on Bukkit, the section
            // class itself has loaded as part of the merger
            // caller's chain.
            return true;
        }
        return false;
    }

    /**
     * Return the direct keys of a Bukkit
     * {@code ConfigurationSection} via reflection. Returns
     * {@code null} when {@code section} isn't a Bukkit
     * section (e.g. on the Fabric path) or when the section
     * is empty.
     *
     * <p>The merger uses this to walk a section's
     * {@code xray.worlds: { overworld: [...] }} block without
     * ever referencing {@code ConfigurationSection} as a
     * class literal.
     */
    @SuppressWarnings("unchecked")
    public static java.util.Set<String> getKeys(Object section) {
        if (section == null) return null;
        try {
            Method m = section.getClass().getMethod("getKeys", boolean.class);
            Object result = m.invoke(section, false);
            return result instanceof java.util.Set ? (java.util.Set<String>) result : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read a single key from a Bukkit
     * {@code ConfigurationSection} via reflection. Returns
     * {@code null} when the key isn't present.
     */
    public static Object getValue(Object section, String key) {
        if (section == null || key == null) return null;
        try {
            Method m = section.getClass().getMethod("get", String.class);
            return m.invoke(section, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read {@code getValues(false)} from a Bukkit
     * {@code ConfigurationSection} via reflection. Returns
     * {@code null} when the value isn't a Bukkit section.
     */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> getValues(Object section) {
        if (section == null) return null;
        try {
            Method m = section.getClass().getMethod("getValues", boolean.class);
            Object result = m.invoke(section, false);
            return result instanceof java.util.Map ? (java.util.Map<String, Object>) result : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
