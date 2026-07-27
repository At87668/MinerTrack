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
