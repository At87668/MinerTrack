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
import org.bukkit.event.player.PlayerJoinEvent;

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
        // Resolve to canonical minecraft:xxx dimension id (minecraft:overworld
        // / minecraft:the_nether / minecraft:the_end) for the core layer.
        // The folder name is preserved on the location for log output.
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
        String worldFolder = e.getBlock().getWorld().getName();
        String dimensionId = bridge.resolveDimensionId(worldFolder);

        // Only track rare ores; the list is already normalised to
        // minecraft:xxx ids by CoreConfig / ConfigEngine.
        String blockType = link.star_dust.MinerTrack.common.MaterialMapper
                .bukkitToMinecraft(e.getBlock().getType().name());
        var rareOres = miningCore.getState().getRareOres(dimensionId);
        if (rareOres.contains(blockType)) {
            // The CommonLocation uses the world folder name (preserved for
            // log/webhook output); config lookups go through dimensionId.
            CommonLocation loc = new CommonLocation(worldFolder, e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
            bukkitBridge.trackPlacedBlock(playerId, loc);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        UUID playerId = e.getPlayer().getUniqueId();
        // Remove verbose tracking on join (legacy behavior)
        vlBridge.getVerbosePlayers().remove(playerId);
    }
}

