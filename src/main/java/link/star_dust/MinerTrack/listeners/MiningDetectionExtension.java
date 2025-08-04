/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/listeners/MiningDetectionExtension.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/

package link.star_dust.MinerTrack.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import link.star_dust.MinerTrack.MinerTrack;

import java.util.*;

public class MiningDetectionExtension implements Listener {

    private final Map<UUID, List<MiningFeature>> miningHistory = new HashMap<>();
    private final Map<UUID, Integer> playerSuspicionMap = new HashMap<>();
    private final MinerTrack plugin;

    public MiningDetectionExtension(MinerTrack plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        //plugin.getLogger().info("ExtensionLoader > MiningDetectionExtension loaded!");
    }

    private static class MiningFeature {
        int airBlocks;
        int solidBlocks;

        MiningFeature(int airBlocks, int solidBlocks) {
            this.airBlocks = airBlocks;
            this.solidBlocks = solidBlocks;
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        analyzeMiningPattern(player, block);
    }

    private void analyzeMiningPattern(Player player, Block block) {
        World world = block.getWorld();
        if (world == null) return;

        Location location = block.getLocation();
        int airBlocks = 0, solidBlocks = 0;

        // Analyze surrounding blocks
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue; // Ignore center block
                    Block nearby = world.getBlockAt(location.clone().add(x, y, z));
                    if (nearby.getType() == Material.AIR && nearby.getType() == Material.CAVE_AIR) {
                        airBlocks++;
                    } else if (nearby.getType().isSolid()) {
                        solidBlocks++;
                    }
                }
            }
        }

        // Preserve mine features
        UUID playerId = player.getUniqueId();
        MiningFeature currentFeature = new MiningFeature(airBlocks, solidBlocks);
        miningHistory.computeIfAbsent(playerId, k -> new ArrayList<>()).add(currentFeature);

        // keep window size of history to 5
        List<MiningFeature> history = miningHistory.get(playerId);
        if (history.size() > 5) {
            history.remove(0);
        }

        // Calculate historical feature averages
        int totalAir = 0, totalSolid = 0;
        for (MiningFeature feature : history) {
            totalAir += feature.airBlocks;
            totalSolid += feature.solidBlocks;
        }
        int averageAir = totalAir / history.size();
        int averageSolid = totalSolid / history.size();

        // Adjust suspicious values based on feature trends
        int suspicionLevel = playerSuspicionMap.getOrDefault(playerId, 0);
        if (averageSolid >= 7 && averageAir <= 1) {
            // road
            suspicionLevel += 5;
        } else if (averageAir >= 6) {
            // cave
            suspicionLevel -= 5;
        } else {
            // normal
            suspicionLevel += 1;
        }

        // update suspicious values
        playerSuspicionMap.put(playerId, suspicionLevel);

        // Output debug info
        plugin.getLogger().info(player.getName() + " -> Air: " + averageAir + ", Solid: " + averageSolid + ", Suspicion: " + suspicionLevel);
    }

    // Interface to obtain suspicious values
    public int getSuspicionLevel(UUID playerId) {
        return playerSuspicionMap.getOrDefault(playerId, 0);
    }
    
    public int getSuspicionLevel(Player player) {
    	UUID playerID = Bukkit.getOfflinePlayer(player.getName()).getUniqueId();
        return getSuspicionLevel(playerID);
    }
    
    public void resetSuspicionLevel(Player player) {
    	UUID playerID = Bukkit.getOfflinePlayer(player.getName()).getUniqueId();
    	playerSuspicionMap.put(playerID, 0);
    }
}
