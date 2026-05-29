package link.star_dust.MinerTrack.bukkit;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;
import link.star_dust.MinerTrack.core.detection.DetectionEngine;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.UUID;

public class MiningListener implements Listener {
    private final DetectionEngine detectionEngine;
    private final DetectionBridge detectionBridge;
    private final ViolationManagerBridge vlManager;

    public MiningListener(DetectionEngine detectionEngine, DetectionBridge detectionBridge, ViolationManagerBridge vlManager) {
        this.detectionEngine = detectionEngine;
        this.detectionBridge = detectionBridge;
        this.vlManager = vlManager;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.isCancelled()) return;
        org.bukkit.block.Block b = e.getBlock();
        String type = b.getType().name();
        org.bukkit.entity.Player p = e.getPlayer();

        CommonLocation loc = new CommonLocation(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());

        // basic vein detection: count connected blocks within max distance (use config default 5)
        int maxDist = 5;
        int veinSize = detectionEngine.countVeinBlocks(loc, type, maxDist);

        // threshold from config: default 3
        int threshold = 3;
        if (veinSize >= threshold) {
            vlManager.increaseViolationLevel(p.getUniqueId(), p.getName(), 1, type, veinSize, veinSize, loc);
        }
    }
}
