package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.detection.MiningCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Consumer;

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
        String worldName = e.getBlock().getWorld().getName();

        miningCore.onBlockBreak(playerId, player.getName(),
            worldName, e.getBlock().getType().name(),
            e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (e.isCancelled()) return;
        Player player = e.getPlayer();
        UUID playerId = player.getUniqueId();
        String worldName = e.getBlock().getWorld().getName();

        // Only track rare ores
        var rareOres = miningCore.getState().getRareOres(worldName);
        if (rareOres.contains(e.getBlock().getType().name())) {
            CommonLocation loc = new CommonLocation(worldName, e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
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

