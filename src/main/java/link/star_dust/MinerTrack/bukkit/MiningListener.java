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

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.UUID;

/**
 * Bukkit MiningListener: registers events, tracks placed blocks,
 * delegates detection to MiningCore, and runs periodic cleanup tasks.
 */
public class MiningListener implements Listener {
    private final MiningCore miningCore;
    private final DetectionBridge bridge;
    private final ViolationManagerBridge vlBridge;
    private final BukkitDetectionBridge bukkitBridge;

    public MiningListener(MiningCore miningCore, DetectionBridge bridge, ViolationManagerBridge vlBridge, BukkitDetectionBridge bukkitBridge) {
        this.miningCore = miningCore;
        this.bridge = bridge;
        this.vlBridge = vlBridge;
        this.bukkitBridge = bukkitBridge;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.isCancelled()) return;
        Player player = e.getPlayer();
        UUID playerId = player.getUniqueId();
        // World identity: translate the Bukkit folder name to the
        // canonical Minecraft dimension id (`minecraft:overworld`,
        // `minecraft:the_nether`, `minecraft:the_end`, or
        // `minecraft:<folder>` for non-vanilla worlds) using the
        // layered rules in
        // `BukkitDetectionBridge.resolveDimensionId`. The
        // dimension id is what the core layer keys all of its
        // data structures on (placedBlocks, brokenAir, path
        // history, vein clusters) and what the
        // `xray.worlds` config mapping uses. Storing the
        // dimension id here means:
        //   1. Debug logs and the "World:" field of the X-Ray
        //      log show the canonical id the operator expects
        //      (`minecraft:overworld`, not `world2`).
        //   2. `CoreConfig.getGroupConfigForWorld` does a
        //      single `Map.get` against the same keys the
        //      `xray.worlds` mapping built at startup — no
        //      per-call normalisation, no accidental misses.
        //   3. On a multi-NORMAL-world server (e.g. a
        //      `level-name=world2` main world plus a Multiverse
        //      `flatland`), the two worlds map to DIFFERENT
        //      canonical ids (`minecraft:overworld` vs
        //      `minecraft:flatland`) because the layered rule
        //      namespaces non-level-name NORMAL worlds. Their
        //      `placedBlocks` entries are therefore keyed
        //      differently and never cross-pollinate, even
        //      though they live in the same `Map<CommonLocation,
        //      Long>`.
        String worldFolder = e.getBlock().getWorld().getName();
        String dimensionId = bridge.resolveDimensionId(worldFolder);

        // Translate Bukkit Material enum → canonical Minecraft namespace id
        // (e.g. DIAMOND_ORE → minecraft:diamond_ore) so the core layer can
        // compare against a platform-neutral id.
        String blockType = link.star_dust.MinerTrack.common.MaterialMapper
                .bukkitToMinecraft(e.getBlock().getType().name());

        miningCore.onBlockBreak(playerId, player.getName(),
            dimensionId, blockType,
            e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (e.isCancelled()) return;
        Player player = e.getPlayer();
        UUID playerId = player.getUniqueId();
        // World identity: must match the key used in
        // `onBlockBreak` exactly. Both call sites resolve
        // the folder name through
        // `BukkitDetectionBridge.resolveDimensionId` so the
        // `trackPlacedBlock` map and the `onBlockBreak`
        // `isPlayerPlacedBlock` lookup see the same key for
        // the same physical block.
        String worldFolder = e.getBlock().getWorld().getName();
        String dimensionId = bridge.resolveDimensionId(worldFolder);

        // Only track rare ores; the list is already normalised to
        // minecraft:xxx ids by CoreConfig / ConfigEngine.
        String blockType = link.star_dust.MinerTrack.common.MaterialMapper
                .bukkitToMinecraft(e.getBlock().getType().name());
        var rareOres = miningCore.getState().getRareOres(dimensionId);
        if (rareOres.contains(blockType)) {
            CommonLocation loc = new CommonLocation(dimensionId, e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
            bukkitBridge.trackPlacedBlock(playerId, loc);
        }
    }
}

