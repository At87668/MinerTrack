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

package link.star_dust.MinerTrack.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.util.logging.Logger;

/**
 * PreLaunch entrypoint for MinerTrack Fabric support.
 *
 * <p>Since Fabric Loader remaps the Minecraft JAR to intermediary names at
 * startup, and intermediary names are the same across all Minecraft versions,
 * no mapping download or injection is needed. Class name redirects are
 * hardcoded in {@link FabricReflection#tryMcMigration(String)} using the
 * cross-version stable {@code net.minecraft.class_NNNN} names.
 */
public class MinerTrackPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = Logger.getLogger("MinerTrack/PreLaunch");

    @Override
    public void onPreLaunch() {
        String mcVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        LOGGER.info("MinerTrack pre-launch initialized for Minecraft " + mcVersion
                + ".");
    }
}