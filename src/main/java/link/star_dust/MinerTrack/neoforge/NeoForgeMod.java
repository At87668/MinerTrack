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

/**
 * NeoForge mod entry point. Registers event handlers on the NeoForge event bus
 * for ServerStartingEvent and ServerStoppingEvent, which delegate to
 * {@link NeoForgePlatform}.
 *
 * <p><strong>Build note:</strong> When building with NeoGradle, annotate
 * this class with {@code @Mod("minertrack")} from
 * {@code net.neoforged.fml.common.Mod}. The annotation is omitted
 * here to avoid a compile-time dependency on the full NeoForge JAR.
 * The mod metadata in {@code META-INF/neoforge.mods.toml} declares the modid.</p>
 */
@net.neoforged.fml.common.Mod("minertrack")
public class NeoForgeMod {

    private final NeoForgePlatform platform = new NeoForgePlatform();

    public NeoForgeMod() {
        Object eventBus = NeoForgeReflection.getMainEventBus();
        if (eventBus != null) {
            // PermissionGatherEvent.Nodes also fires during MinecraftServer
            // construction (PermissionAPI.initializePermissionAPI), BEFORE
            // ServerStartingEvent — register its listener now so the native
            // minertrack.* PermissionNodes exist before the permission handler
            // is built.
            NeoForgePermissionRegistry.registerGatherListener();

            // RegisterCommandsEvent fires during MinecraftServer construction,
            // which happens BEFORE ServerStartingEvent. Register its listener
            // now (in the mod constructor) so commands are registered in time.
            platform.registerCommandsEarly();

            // ServerStartingEvent
            NeoForgeReflection.registerEventListener(eventBus,
                NeoForgeReflection.neoClass("net.neoforged.neoforge.event.server.ServerStartingEvent"),
                platform::onServerStarting);

            // ServerStoppingEvent
            NeoForgeReflection.registerEventListener(eventBus,
                NeoForgeReflection.neoClass("net.neoforged.neoforge.event.server.ServerStoppingEvent"),
                event -> platform.onServerStopping());
        }
    }
}
