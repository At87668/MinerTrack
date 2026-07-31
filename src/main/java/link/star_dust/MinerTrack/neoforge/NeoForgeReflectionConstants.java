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

package link.star_dust.MinerTrack.neoforge;

import link.star_dust.MinerTrack.fabric.FabricReflection;

/**
 * NeoForge reflection constants. Delegates to the shared Fabric reflection
 * infrastructure for Minecraft internal access, adding NeoForge-specific
 * class and method constants.
 *
 * <p>NeoForge uses {@code net.neoforged.*} packages at runtime.
 */
final class NeoForgeReflectionConstants {

    private NeoForgeReflectionConstants() {}

    static final Class<?>[] NO_ARGS = new Class<?>[0];
    static final Object[]   NO_VALS = new Object[0];

    // NeoForge-specific class names
    static final String CLS_NEOFORGE_MOD_LIST        = "net.neoforged.fml.ModList";
    static final String CLS_NEOFORGE_CONFIG_DIR       = "net.neoforged.fml.loading.FMLPaths";
    static final String CLS_NEOFORGE_EVENT_BUS        = "net.neoforged.neoforge.common.NeoForge";
    static final String CLS_NEOFORGE_SERVER_STARTING  = "net.neoforged.neoforge.event.server.ServerStartingEvent";
    static final String CLS_NEOFORGE_SERVER_STOPPING  = "net.neoforged.neoforge.event.server.ServerStoppingEvent";

    static final String M_FML_GET_MOD_CONTAINER   = "getModContainerById";
    static final String M_FML_PATHS_CONFIG_DIR    = "CONFIGDIR";
    static final String M_EVENT_BUS               = "EVENT_BUS";
    static final String M_REGISTER                = "register";
}
