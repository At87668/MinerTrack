package link.star_dust.MinerTrack.common;

/**
 * Platform-agnostic debug-mode toggle source.
 *
 * <p>The {@code core/} layer reads the debug flag through
 * {@link link.star_dust.MinerTrack.core.CoreLogger}, which is initialised
 * once at startup with a {@code DebugConfig} supplied by the platform
 * (Bukkit reads {@code debug} from {@code config.yml}; Fabric would
 * read it from its own config). Keeping this in {@code common/} means
 * the detection code never imports any platform API just to check
 * whether a debug line should be logged.
 *
 * <p>The default implementation is "debug disabled" (most restrictive
 * option); the platform is expected to inject a real source before the
 * first mining event is processed.
 */
public interface DebugConfig {
    /**
     * @return {@code true} when the operator set {@code debug: true} in
     *         the plugin's config and the platform is ready to emit
     *         developer-level log lines.
     */
    boolean isDebugEnabled();
}
