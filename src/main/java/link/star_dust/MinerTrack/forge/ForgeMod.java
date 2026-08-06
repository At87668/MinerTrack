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

package link.star_dust.MinerTrack.forge;

/**
 * Forge mod entry point. Registers event handlers on the Forge mod event bus
 * for ServerStartingEvent and ServerStoppingEvent, which delegate to
 * {@link ForgePlatform}.
 *
 * <p><strong>Build note:</strong> When building with ForgeGradle, annotate
 * this class with {@code @Mod("minertrack")} from
 * {@code net.minecraftforge.fml.common.Mod}. The annotation is omitted
 * here to avoid a compile-time dependency on the full Forge universal JAR.
 * The mod metadata in {@code META-INF/mods.toml} declares the modid.</p>
 */
@net.minecraftforge.fml.common.Mod("minertrack")
public class ForgeMod {

    private final ForgePlatform platform = new ForgePlatform();

    public ForgeMod() {
        // Register on the Forge main event bus (server-side lifecycle).
        // The Forge @Mod constructor runs before the server starts, so we
        // must defer server-specific init to ServerStartingEvent.
        Object eventBus = ForgeReflection.getMainEventBus();
        if (eventBus != null) {
            // PermissionGatherEvent.Nodes also fires during MinecraftServer
            // construction (PermissionAPI.initializePermissionAPI), BEFORE
            // ServerStartingEvent — register its listener now so the native
            // minertrack.* PermissionNodes exist before the permission handler
            // is built.
            ForgePermissionRegistry.registerGatherListener();

            // RegisterCommandsEvent fires during MinecraftServer construction,
            // which happens BEFORE ServerStartingEvent. Register its listener
            // now (in the mod constructor) so commands are registered in time.
            platform.registerCommandsEarly();

            // ServerStartingEvent -> call platform.onServerStarting()
            ForgeReflection.registerEventListener(eventBus,
                ForgeReflection.forgeClass("net.minecraftforge.event.server.ServerStartingEvent"),
                platform::onServerStarting);

            // ServerStoppingEvent -> call platform.onServerStopping()
            ForgeReflection.registerEventListener(eventBus,
                ForgeReflection.forgeClass("net.minecraftforge.event.server.ServerStoppingEvent"),
                event -> platform.onServerStopping());
        }
    }
}
