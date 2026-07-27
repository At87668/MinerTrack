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

package link.star_dust.MinerTrack.bukkit;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Keeps the {@link BukkitDetectionBridge}'s runtime dimension-id
 * ↔ Bukkit-world registry in sync with the server's world lifecycle.
 *
 * <p>The registry is the lookup table
 * {@link BukkitDetectionBridge#resolveWorld(String)} uses to recover
 * the correct live {@link World} from a canonical dimension id (e.g.
 * resolving {@code minecraft:overworld} → the world whose folder is
 * {@code world2} on a server that set
 * {@code level-name=world2} in {@code server.properties}). Without
 * these listeners, multi-world servers that mount custom dimensions
 * or rename their main-world folder would either resolve to the
 * wrong world (the Environment-based fallback returns the first
 * matching world in {@code Bukkit.getWorlds()}) or fail to find
 * a world at all if the dimension was loaded by a plugin mid-run
 * (e.g. a dungeon plugin that creates a new world on first
 * player entry).
 *
 * <p>{@code WorldLoadEvent} fires only for worlds that load after
 * the listener is registered; the seed call in
 * {@link BukkitPlatform#onEnable()} covers worlds that were
 * already loaded at enable time.
 */
public class WorldRegistryListener implements Listener {
    private final BukkitDetectionBridge bridge;

    public WorldRegistryListener(BukkitDetectionBridge bridge) {
        this.bridge = bridge;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        bridge.registerWorld(event.getWorld());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        World w = event.getWorld();
        if (w == null) return;
        bridge.unregisterWorld(w.getName());
    }
}
