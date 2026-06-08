package link.star_dust.MinerTrack.core;

import link.star_dust.MinerTrack.common.DebugConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Platform-agnostic debug logger for the {@code core/} layer.
 *
 * <p>The detection code path is performance-sensitive: every block
 * break runs through {@link CoreLogger#debug(String)}, so the no-op
 * branch must be a single volatile read with no string formatting.
 * The class also exposes {@link #info(String)} / {@link #warning(String)}
 * for code paths that should always log (regardless of debug mode)
 * without taking a direct dependency on a particular platform's logger
 * API.
 *
 * <p>Initialisation order: the platform calls {@link #init(DebugConfig, Logger)}
 * exactly once during {@code onEnable}, before any detection event
 * is processed. The {@code DebugConfig} parameter is read by every
 * {@link #debug(String)} call to decide whether to do the work; the
 * {@link Logger} parameter is the platform's standard logger (e.g.
 * the {@code JavaPlugin#getLogger()} on Bukkit). If {@link #init}
 * was never called the static state is "debug disabled, no logger
 * attached" so debug calls become no-ops and info / warning calls
 * fall back to {@link java.util.logging.Logger#getLogger(String)}.
 */
public final class CoreLogger {

    /** Prefix prepended to every debug line so it is easy to grep for. */
    public static final String DEBUG_PREFIX = "[MinerTrack:DEBUG] ";

    private static volatile DebugConfig debugConfig = () -> false;
    private static volatile Logger logger = java.util.logging.Logger.getLogger("MinerTrack");

    private CoreLogger() {}

    /**
     * Wire the static logger to a platform-supplied debug toggle and
     * logger. Safe to call more than once (later calls replace the
     * references), but the platform is expected to call it exactly
     * once at startup.
     */
    public static void init(DebugConfig debugConfig, Logger logger) {
        if (debugConfig != null) CoreLogger.debugConfig = debugConfig;
        if (logger != null) CoreLogger.logger = logger;
    }

    /**
     * @return {@code true} when debug mode is on. Read of a single
     *         volatile field — safe to call on the hot path.
     */
    public static boolean isDebug() {
        return debugConfig.isDebugEnabled();
    }

    /**
     * Emit a debug line. Short-circuits to a no-op when debug mode is
     * off so the caller does not need to gate every call site. The
     * caller is responsible for the {@code String} allocation: the
     * helper does not lazy-format the message because doing so would
     * require either varargs boxing or a {@code Supplier<String>} at
     * every call site, which makes the call site uglier than just
     * gating with {@code if (CoreLogger.isDebug()) CoreLogger.debug(...)}.
     */
    public static void debug(String message) {
        if (!isDebug()) return;
        logger.log(Level.FINE, DEBUG_PREFIX + message);
    }

    /**
     * Emit an info-level line. Always logged (regardless of debug
     * mode). The {@code core/} layer uses this for one-off startup
     * notices that the platform logger should pick up; per-event
     * logging belongs in {@link #debug(String)} instead.
     */
    public static void info(String message) {
        logger.info(message);
    }

    /**
     * Emit a warning. Always logged. The {@code core/} layer uses this
     * to surface recovered errors (e.g. an unparseable config value)
     * that should not be silent but are not fatal.
     */
    public static void warning(String message) {
        logger.warning(message);
    }

    /**
     * Emit a warning with a {@link Throwable}'s message. The throwable
     * itself is not logged here; the platform can attach it to its
     * own log call if it wants the stack trace.
     */
    public static void warning(String message, Throwable t) {
        logger.log(Level.WARNING, message, t);
    }
}
