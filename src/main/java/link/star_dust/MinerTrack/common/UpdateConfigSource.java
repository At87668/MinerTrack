package link.star_dust.MinerTrack.common;

/**
 * Platform-agnostic configuration + logging source for the update checker.
 *
 * <p>The {@link link.star_dust.MinerTrack.core.update.UpdateManagerCore}
 * depends only on this interface, so any platform (Bukkit, Fabric, …) can
 * wire it up without dragging the platform's config API into the
 * {@code core/} layer.
 *
 * <p>The platform implementation is expected to read the already-merged
 * {@code check_update} / {@code check_update_channel} config values
 * (i.e. <em>after</em> {@code ConfigMerger} has run) so the update checker
 * sees the same values the rest of the plugin does. Returning a sensible
 * default from {@link #isUpdateCheckEnabled()} when the config is missing
 * keeps the update checker safe to construct before the config is fully
 * loaded.
 */
public interface UpdateConfigSource {
    /**
     * @return the {@code check_update} config value; default {@code true}
     *         (matches the v1 behaviour and the documented default in
     *         {@code config.yml}).
     */
    boolean isUpdateCheckEnabled();

    /**
     * @return the {@code check_update_channel} config value: one of
     *         {@code "stable"}, {@code "beta"}, {@code "alpha"}.
     *         Default: {@code "stable"}.
     */
    String getUpdateCheckChannel();

    /**
     * Log a non-fatal message. Implementations should route to whatever
     * the platform's standard logger is (e.g. {@code plugin.getLogger()}
     * on Bukkit). Used to surface network failures from the Modrinth
     * fetch without aborting startup.
     */
    void log(String message);
}
