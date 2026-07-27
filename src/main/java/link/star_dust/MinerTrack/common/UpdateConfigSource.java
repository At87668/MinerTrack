/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 *
 *  Copyright (c) At87668 (Author87668) <https://github.com/At87668>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
