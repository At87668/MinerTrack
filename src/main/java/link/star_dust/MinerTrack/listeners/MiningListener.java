/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/listeners/MiningListener.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack.listeners;

import link.star_dust.MinerTrack.FoliaCheck;
import link.star_dust.MinerTrack.MinerTrack;
import link.star_dust.MinerTrack.managers.ViolationManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.function.Consumer;
import org.bukkit.util.Vector;

import java.util.*;

public class MiningListener implements Listener {
    private final MinerTrack plugin;
    private final Map<UUID, Map<String, List<Location>>> miningPath = new HashMap<>();
    private final Map<UUID, Long> lastMiningTime = new HashMap<>();
    private final Map<UUID, Integer> violationLevel = new HashMap<>();
    private final Map<UUID, Integer> minedVeinCount = new HashMap<>();
    private final Map<UUID, Map<String, Location>> lastVeinLocation = new HashMap<>();
    private final Map<UUID, Set<Location>> placedOres = new HashMap<>();
    private final Map<Location, Long> explosionExposedOres = new HashMap<>();
    private final Map<UUID, Long> vlZeroTimestamp = new HashMap<>();
    private final Map<UUID, Integer> airViolationLevel = new HashMap<>();
    private final Map<UUID, Long> lastAirViolationTime = new HashMap<>();
    //private final MiningDetectionExtension ex;
    
    public MiningListener(MinerTrack plugin) {
        this.plugin = plugin;
		//this.ex = plugin.miningDetectionExtension;
        int interval = 20 * 60; // Scheduling interval (unit: tick)

        if (FoliaCheck.isFolia()) {
            try {
                Class<?> schedulerClass = Class.forName("org.bukkit.Bukkit");
                Object scheduler = schedulerClass.getMethod("getGlobalRegionScheduler").invoke(null);
                scheduler.getClass().getMethod("runAtFixedRate",
                    Plugin.class,
                    Class.forName("java.util.function.Consumer"),
                    long.class,
                    long.class
                ).invoke(scheduler, plugin, (Consumer<Object>) task -> {
                    try {
                        if (!plugin.isEnabled()) {
                            task.getClass().getMethod("cancel").invoke(task);
                            return;
                        }
                        checkAndResetPaths();
                        cleanUpAirViolations();
                        cleanupExpiredPaths();
                        cleanupExpiredExplosions();
                        cleanupExpiredPlacedBlocks();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, interval, interval);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    checkAndResetPaths();
                    cleanUpAirViolations();
                    cleanupExpiredPaths();
                    cleanupExpiredExplosions();
                    cleanupExpiredPlacedBlocks();
                }
            }.runTaskTimer(plugin, interval, interval);
        }

    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();
        plugin.getVerbosePlayers().remove(playerUUID);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
    	if (!plugin.getConfig().getBoolean("xray.enable", true)) {
            return;
        }
    	
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Material blockType = event.getBlock().getType();
        List<String> rareOres = plugin.getConfig().getStringList("xray.rare-ores");

        if (rareOres.contains(blockType.name())) {
            placedOres.putIfAbsent(playerId, new HashSet<>());
            placedOres.get(playerId).add(event.getBlock().getLocation());
        }
    }
    
    private boolean isPlayerPlacedBlock(Location blockLocation) {
        for (Set<Location> playerPlacedBlocks : placedOres.values()) {
            if (playerPlacedBlocks.contains(blockLocation)) {
                return true; // Block is placed by player
            }
        }
        return false; // not placed by player
    }
    
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        Player sourcePlayer = null;

        // Check if the TNT explosion is triggered by player
        if (entity instanceof TNTPrimed tnt) {
            if (tnt.getSource() instanceof Player player) {
                sourcePlayer = player;
            }
        } else if (entity instanceof ExplosiveMinecart minecart) {
            if (((TNTPrimed) minecart).getSource() instanceof Player player) {
                sourcePlayer = player;
            }
        } else if (entity instanceof EnderCrystal || entity instanceof WitherSkull || entity instanceof Creeper) {
            return; // Ignore special explosions that are not controlled by player
        }

        if (sourcePlayer != null) {
            UUID playerId = sourcePlayer.getUniqueId();
            List<String> rareOres = plugin.getConfig().getStringList("xray.rare-ores");
            long currentTime = System.currentTimeMillis();
            int retentionTime = plugin.getConfig().getInt("xray.explosion.explosion_retention_time", 600) * 1000; // Default 10 minutes
            int totalBlocks = 0;
            int rareOresCount = 0;

            // Count the number of blocks and rare ores affected by the explosion
            for (Block block : event.blockList()) {
            	Location blockLocation = block.getLocation();
            	if (isPlayerPlacedBlock(blockLocation)) {
                    continue;
                }
            	
                totalBlocks++;
                if (rareOres.contains(block.getType().name())) {
                    rareOresCount++;
                    explosionExposedOres.put(block.getLocation(), currentTime + retentionTime);
                }
            }

            // If there are no blocks within the blast range
            if (totalBlocks == 0) return;

            // Calculate rare ore hit rates
            double hitRate = (double) rareOresCount / totalBlocks;

            // Determine whether the hit rate is abnormal
            double suspiciousThreshold = plugin.getConfig().getDouble("xray.explosion.suspicious_hit_rate", 0.1); // Default 10%
            if (hitRate > suspiciousThreshold) {
                handleSuspiciousExplosion(sourcePlayer, rareOresCount, hitRate);
            }
        }
    }
    
    private void handleSuspiciousExplosion(Player player, int rareOresCount, double hitRate) {
        UUID playerId = player.getUniqueId();
        int currentVL = violationLevel.getOrDefault(playerId, 0);

        // Dynamically increases VL, based on rare ore count and hit rate.
        int increaseAmount = calculateExplosionVLIncrease(rareOresCount, hitRate);
        violationLevel.put(playerId, currentVL + increaseAmount);

        // send log
        //plugin.getLogger().warning(player.getName() + " 's explosive behavior is abnormal! Ores: " + rareOresCount + ", Hit rate: " + String.format("%.2f%%", hitRate * 100) + " (VL added " + increaseAmount + ")");
    }

    private int calculateExplosionVLIncrease(int rareOresCount, double hitRate) {
        // Calculate VL growth
        double baseRate = plugin.getConfig().getDouble("xray.explosion.base_vl_rate", 2.0);
        return (int) Math.ceil(rareOresCount * hitRate * baseRate);
    }

    private void cleanupExpiredExplosions() {
        long currentTime = System.currentTimeMillis();
        explosionExposedOres.entrySet().removeIf(entry -> currentTime > entry.getValue());

        // plugin.getLogger().info("Cleared expired blasting records. Total number of current records: " + explosionExposedOres.size());
    }
    
    /* Deprecated
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        // Record all ore locations exposed by the explosion
        List<String> rareOres = plugin.getConfig().getStringList("xray.rare-ores");
        long currentTime = System.currentTimeMillis();
        int retentionTime = plugin.getConfig().getInt("xray.explosion_retention_time", 600) * 1000; // Default 10 minutes

        for (var block : event.blockList()) {
            if (rareOres.contains(block.getType().name())) {
                explosionExposedOres.put(block.getLocation(), currentTime + retentionTime);
            }
        }
    }
    */

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("xray.enable", true)) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Material blockType = event.getBlock().getType();
        Location blockLocation = event.getBlock().getLocation();
        
        if (isPlayerPlacedBlock(blockLocation)) {
            return;
        }
        
        List<String> rareOres = plugin.getConfig().getStringList("xray.rare-ores");

        if (!player.hasPermission("minertrack.bypass") || player.hasPermission("minertrack.bypass") && plugin.getConfigManager().DisableBypass()) {
        	if (violationLevel.getOrDefault(playerId, 0) == 0) {
        		vlZeroTimestamp.put(playerId, System.currentTimeMillis());
        	}

        	// Check if detection is enabled for the player's world
        	String worldName = player.getWorld().getName();
        	if (!plugin.getConfigManager().isWorldDetectionEnabled(worldName)) {
        		return; // Detection is disabled for this world
        	}

        	// Check if the block height exceeds max height for detection
        	int maxHeight = plugin.getConfigManager().getWorldMaxHeight(worldName);
        	if (maxHeight != -1 && blockLocation.getY() > maxHeight) {
        		return;
        	}

        	// Ignore ores exposed by explosions within the retention time
        	if (explosionExposedOres.containsKey(blockLocation)) {
        		long expirationTime = explosionExposedOres.get(blockLocation);
        		if (System.currentTimeMillis() < expirationTime) {
        			return;
        		} else {
        			explosionExposedOres.remove(blockLocation); // Remove expired entry
        		}
        	}

        	// Proceed with X-Ray detection if the broken block is a rare ore
        	if (rareOres.contains(blockType.name())) {
        		handleXRayDetection(player, blockType, blockLocation);
        	}
        }
    }

    private void handleXRayDetection(Player player, Material blockType, Location blockLocation) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        int maxPathLength = plugin.getConfig().getInt("xray.max_path_length", 500);

        // Initialize player's mining path information
        miningPath.putIfAbsent(playerId, new HashMap<>());
        Map<String, List<Location>> worldPaths = miningPath.get(playerId);
        String worldName = blockLocation.getWorld().getName();

        worldPaths.putIfAbsent(worldName, new ArrayList<>());
        List<Location> path = worldPaths.get(worldName);

        // Update mining path and time
        path.add(blockLocation);
        lastMiningTime.put(playerId, currentTime);

        if (path.size() > maxPathLength) {
            path.remove(0);
        }

        // Check new veins
        checkForArtificialAir(player, path);
        if (!isInNaturalEnvironment(player, blockLocation, path) && !isSmoothPath(path)) {
            if (isNewVein(playerId, worldName, blockLocation, blockType)) {
                minedVeinCount.put(playerId, minedVeinCount.getOrDefault(playerId, 0) + 1);
                lastVeinLocation.putIfAbsent(playerId, new HashMap<>());
                lastVeinLocation.get(playerId).put(worldName, blockLocation);

                int veinCount = minedVeinCount.getOrDefault(playerId, 0);
                if (veinCount >= plugin.getConfigManager().getVeinCountThreshold()) {
                    analyzeMiningPath(player, path, blockType, countVeinBlocks(blockLocation, blockType), blockLocation);
                }
            }
        }
    }

    private void cleanupExpiredPaths() {
        long now = System.currentTimeMillis();
        long traceBackLength = plugin.getConfigManager().traceBackLength(); // Get the length of time to look back

        miningPath.forEach((playerId, paths) -> {
            paths.values().forEach(path -> path.removeIf(loc -> now - loc.getWorld().getTime() > traceBackLength));
        });
    }

    private boolean isNewVein(UUID playerId, String worldName, Location location, Material oreType) {
        // Gets a record of the player's last vein location in the current world
        Map<String, Location> lastLocations = lastVeinLocation.getOrDefault(playerId, new HashMap<>());
        Location lastLocation = lastLocations.get(worldName);

        int maxDistance = plugin.getConfigManager().getMaxVeinDistance();
        int smallVeinThreshold = plugin.getConfigManager().getSmallVeinSize();

        // If no previous record, it's a new vein
        if (lastLocation == null) {
            lastLocations.put(worldName, location);
            lastVeinLocation.put(playerId, lastLocations);
            return true;
        }

        // If the same exact block position, treat as not a new vein
        if (lastLocation.getBlockX() == location.getBlockX()
                && lastLocation.getBlockY() == location.getBlockY()
                && lastLocation.getBlockZ() == location.getBlockZ()
                && lastLocation.getWorld().equals(location.getWorld())) {
            return false;
        }

        // Build clusters (sets of connected ore locations) around both the current location and last recorded location
        Set<Location> currentCluster = getVeinLocations(location, oreType, maxDistance);
        Set<Location> lastCluster = getVeinLocations(lastLocation, oreType, maxDistance);

        // If clusters intersect (share any block), consider same vein
        for (Location l : currentCluster) {
            if (lastCluster.contains(l)) {
                return false; // same vein
            }
        }

        // Compute minimum distance between clusters
        double minDist = Double.MAX_VALUE;
        for (Location a : currentCluster) {
            for (Location b : lastCluster) {
                double d = a.distance(b);
                if (d < minDist) minDist = d;
            }
        }

        // If clusters are adjacent within maxDistance, treat as same vein
        if (minDist <= maxDistance) {
            return false;
        }

        // Special handling for very small veins: be conservative and avoid treating tiny nearby clusters as the same
        int currentSize = currentCluster.size();
        int lastSize = lastCluster.size();

        if (currentSize > 0 && currentSize <= smallVeinThreshold && lastSize > 0 && lastSize <= smallVeinThreshold) {
            // If both are small but not overlapping and not within maxDistance, consider new
            lastLocations.put(worldName, location);
            lastVeinLocation.put(playerId, lastLocations);
            return true;
        }

        // For general case, if clusters are far apart, it's a new vein
        if (minDist > maxDistance) {
            lastLocations.put(worldName, location);
            lastVeinLocation.put(playerId, lastLocations);
            return true; // is new
        }

        return false; // fallback: treat as same
    }

    // Helper: find all connected ore locations starting from startLocation within a search limit
    private Set<Location> getVeinLocations(Location startLocation, Material type, int maxDistance) {
        Set<Location> visited = new HashSet<>();
        Queue<Location> toVisit = new LinkedList<>();
        if (startLocation == null || !startLocation.getBlock().getType().equals(type)) return visited;
        toVisit.add(startLocation);

        // Safety cap to prevent excessive work
        int safetyCap = 2000;

        while (!toVisit.isEmpty() && visited.size() < safetyCap) {
            Location current = toVisit.poll();
            if (visited.contains(current)) continue;
            if (!current.getBlock().getType().equals(type)) continue;

            visited.add(current);

            // traverse neighbors (including diagonals) but only enqueue neighbors within a bounding cube
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Location neighbor = current.clone().add(dx, dy, dz);
                        if (visited.contains(neighbor)) continue;
                        if (!neighbor.getBlock().getType().equals(type)) continue;
                        if (neighbor.distance(startLocation) <= maxDistance * 2) { // allow a slightly larger search radius for clustering
                            toVisit.add(neighbor);
                        }
                    }
                }
            }
        }

        return visited;
    }
    
    public int countVeinBlocks(Location startLocation, Material type) {
        if (startLocation == null || !startLocation.getBlock().getType().equals(type)) {
            return 0; // 起始位置无效或矿物类型不匹配
        }

        double maxDistance = plugin.getConfigManager().getMaxVeinDistance();
        Set<Location> visited = new HashSet<>();
        Queue<Location> toVisit = new LinkedList<>();
        toVisit.add(startLocation);

        int blockCount = 0;

        while (!toVisit.isEmpty()) {
            Location current = toVisit.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            // 如果当前方块类型匹配，计入矿脉总数
            if (current.getBlock().getType().equals(type)) {
                blockCount++;

                // 遍历邻接方块，包括直接邻接和角点
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue; // 跳过当前方块

                            Location neighbor = current.clone().add(dx, dy, dz);
                            if (!visited.contains(neighbor) 
                                    && neighbor.distance(current) <= maxDistance 
                                    && neighbor.getBlock().getType().equals(type)) {
                                toVisit.add(neighbor);
                            }
                        }
                    }
                }
            }
        }
        return blockCount;
    }
    
    
    private int totalTurns = 0;
    private int branchCount = 0;
    private int yChanges = 0;
    
    private boolean isSmoothPath(List<Location> path) {
        if (path.size() < 2) return true;
        
        int turnThreshold = plugin.getConfigManager().getTurnCountThreshold();
        int branchThreshold = plugin.getConfigManager().getBranchCountThreshold();
        int yChangeThreshold = plugin.getConfigManager().getYChangeThreshold();

        Location lastLocation = null;
        Vector lastDirection = null;

        for (int i = 0; i < path.size(); i++) {
            Location currentLocation = path.get(i);
            if (lastLocation != null) {
                // 当前方向向量
                Vector currentDirection = currentLocation.toVector().subtract(lastLocation.toVector()).normalize();

                if (lastDirection != null) {
                    // 计算方向变化的角度（转向幅度）
                    double dotProduct = lastDirection.dot(currentDirection);
                    if (dotProduct < Math.cos(Math.toRadians(30))) { // 夹角大于30度，记为一次转向
                        totalTurns++;
                    }
                }

                // 检查Y轴的变化
                if (Math.abs(currentLocation.getY() - lastLocation.getY()) > plugin.getConfigManager().getYPosChangeThresholdAddRequired()) {
                    yChanges++;
                }

                // 检查分支（检测是否突然偏离主方向）
                if (i > 1) {
                    Location prevLocation = path.get(i - 1);
                    Vector prevDirection = prevLocation.toVector().subtract(lastLocation.toVector()).normalize();
                    if (currentDirection.angle(prevDirection) > Math.toRadians(60)) { // 分支角度大于60°
                        branchCount++;
                    }
                }

                lastDirection = currentDirection;
            }
            lastLocation = currentLocation;
        }

        // 检查总转向次数、分支次数和Y轴变化是否超过阈值
        return totalTurns < turnThreshold && branchCount < branchThreshold && yChanges < yChangeThreshold;
    }
    
    private boolean isInNaturalEnvironment(Player player, Location location, List<Location> path) {
    	if (!plugin.getConfigManager().getNaturalEnable()) return false;
    	
        int airCount = 0;
        int waterCount = 0;
        int lavaCount = 0;

        int caveAirMultiplier = plugin.getConfigManager().getCaveAirMultiplier();
        int airThreshold = plugin.getConfigManager().getCaveBypassAirThreshold();
        int detectionRange = plugin.getConfigManager().getCaveDetectionRange();

        int waterThreshold = plugin.getConfigManager().getWaterThreshold();
        int lavaThreshold = plugin.getConfigManager().getLavaThreshold();

        boolean checkRunningWater = plugin.getConfigManager().isRunningWaterCheckEnabled();

        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();

        for (int x = -detectionRange; x <= detectionRange; x++) {
            for (int y = -detectionRange; y <= detectionRange; y++) {
                for (int z = -detectionRange; z <= detectionRange; z++) {
                    Material type = location.getWorld().getBlockAt(baseX + x, baseY + y, baseZ + z).getType();
                    switch (type) {
                        case CAVE_AIR:
                            airCount += caveAirMultiplier;
                            break;
                        case AIR:
                            airCount++;
                            break;
                        case WATER:
                            if (checkRunningWater || isWaterStill(location.getWorld(), baseX + x, baseY + y, baseZ + z)) {
                                waterCount++;
                            }
                            break;
                        case LAVA:
                            lavaCount++;
                            break;
                        default:
                            break;
                    }
                }
            }
        }

		if (airCount > airThreshold && plugin.getConfigManager().isCaveSkipVL() && airViolationLevel.getOrDefault(player, 0) < plugin.getConfigManager().AirMonitorVLT()) return true;
        if (waterCount > waterThreshold && plugin.getConfigManager().isSeaSkipVL()) return true;
        if (lavaCount > lavaThreshold && plugin.getConfigManager().isLavaSeaSkipVL()) return true;

        return false;
    }

	private boolean isWaterStill(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == Material.WATER) {
            return block.getBlockData() instanceof Levelled && ((Levelled) block.getBlockData()).getLevel() == 0;
        }
        return false;
    }

    
    private void analyzeMiningPath(Player player, List<Location> path, Material blockType, int count, Location blockLocation) {
        UUID playerId = player.getUniqueId();
        Map<String, Location> lastVeins = lastVeinLocation.getOrDefault(playerId, new HashMap<>());
        String worldName = blockLocation.getWorld().getName();
        Location lastVeinLocation = lastVeins.get(worldName);

        /*
        // 如果有上一个矿脉记录，检查路径联通性
        if (lastVeinLocation != null) {
            double veinDistance = lastVeinLocation.distance(blockLocation);

            // 如果路径不联通，认为是在洞穴中挖矿
            if (!isPathConnected(lastVeinLocation, blockLocation, path)) {
                if (plugin.getConfigManager().caveSkipVL()) {
                    return;
                }
            }
        }*/

        // 如果路径分析通过，继续处理违规逻辑
        int disconnectedSegments = 0;
        double totalDistance = 0.0;
        Location lastLocation = null;

        for (Location currentLocation : path) {
            if (lastLocation != null) {
                double distance = currentLocation.distance(lastLocation);
                totalDistance += distance;

                if (distance > 3) {
                    disconnectedSegments++;
                }
            }
            lastLocation = currentLocation;
        }

        int veinCount = minedVeinCount.getOrDefault(playerId, 0);
        increaseViolationLevel(player, 1, blockType.name(), count, veinCount, blockLocation);
        //minedVeinCount.put(playerId, 0);
    }
    
    private boolean isPathConnected(Location start, Location end, List<Location> path) {
        // 如果路径为空，直接返回 false
        if (path == null || path.isEmpty()) {
            return false;
        }

        double maxDistance = plugin.getConfigManager().getMaxVeinDistance();
        Set<Location> visited = new HashSet<>();
        Queue<Location> queue = new LinkedList<>();

        // 初始化搜索队列
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            Location current = queue.poll();

            // 如果当前节点可以直接到达终点，则路径联通
            if (current.distance(end) <= maxDistance) {
                return true;
            }

            // 检查路径中所有未访问的点
            for (Location point : path) {
                if (!visited.contains(point) && current.distance(point) <= maxDistance) {
                    queue.add(point);
                    visited.add(point);
                }
            }
        }

        // 如果搜索完成后仍未找到连接路径，则不联通
        return false;
    }
    
    private void checkForArtificialAir(Player player, List<Location> path) {
        if (!plugin.getConfig().getBoolean("xray.natural-detection.cave.air-monitor.enable", true)) {
            return;
        }

        int minPathLength = plugin.getConfig().getInt("xray.natural-detection.cave.air-monitor.min-path-length", 10);
        if (path.size() < minPathLength) {
            return;
        }

        int airBlockCount = 0;
        for (Location loc : path) {
            Material type = loc.getBlock().getType();
            if (type == Material.AIR || type == Material.CAVE_AIR) {
                airBlockCount++;
            }
        }

        double airRatio = (double) airBlockCount / path.size();
        double threshold = plugin.getConfig().getDouble("xray.natural-detection.cave.air-monitor.air-ratio-threshold", 0.3);

        if (airRatio > threshold) {
            UUID playerId = player.getUniqueId();
            int increase = plugin.getConfig().getInt("xray.natural-detection.cave.air-monitor.violation-increase", 1);
            airViolationLevel.put(playerId, airViolationLevel.getOrDefault(playerId, 0) + increase);
            lastAirViolationTime.put(playerId, System.currentTimeMillis());
        }
    }
    
    private void cleanupExpiredPlacedBlocks() {
        long currentTime = System.currentTimeMillis();
        long expirationTime = plugin.getConfig().getInt("xray.trace_remove", 15) * 60 * 1000L;

        placedOres.forEach((playerId, blockSet) -> blockSet.removeIf(blockLocation -> {
            long placedTime = blockLocation.getWorld().getTime();
            return (currentTime - placedTime) > expirationTime;
        }));

        //plugin.getLogger().info("清理了过期的放置方块记录。当前记录总数: " + placedOres.size());
    }
    
    private void cleanUpAirViolations() {
        long now = System.currentTimeMillis();
        long decayTime = plugin.getConfig().getLong("xray.natural-detection.cave.air-monitor.remove-time", 20) * 60 * 1000L;

        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, Long> entry : lastAirViolationTime.entrySet()) {
            if (now - entry.getValue() > decayTime) {
                toRemove.add(entry.getKey());
            }
        }

        for (UUID uuid : toRemove) {
            airViolationLevel.remove(uuid);
            lastAirViolationTime.remove(uuid);
        }
    }
    
    private void checkAndResetPaths() {
        long now = System.currentTimeMillis();
        long traceRemoveMillis = plugin.getConfig().getInt("xray.trace_remove", 15) * 60 * 1000L; // 默认15分钟

        for (UUID playerId : new HashSet<>(vlZeroTimestamp.keySet())) {
            Long lastZeroTime = vlZeroTimestamp.get(playerId);
            int vl = ViolationManager.getViolationLevel(playerId);

            if (lastZeroTime != null && vl == 0 && now - lastZeroTime > traceRemoveMillis) {
                miningPath.remove(playerId); // 清除路径
                minedVeinCount.remove(playerId); // 清除矿脉计数
                vlZeroTimestamp.remove(playerId); // 清除时间戳
                
                totalTurns = 0;  // 移除转向计数
                branchCount = 0; // 移除分支计数
                yChanges = 0;    // 移除y坐标变化计数

                // plugin.getLogger().info("Path reset for player: " + playerId + " due to VL=0 and timeout.");
            }
        }
    }

    private void increaseViolationLevel(Player player, int amount, String blockType, int count, int vein, Location location) {
        UUID playerId = player.getUniqueId();
        violationLevel.put(playerId, violationLevel.getOrDefault(playerId, 0) + amount);
        vlZeroTimestamp.remove(playerId); // When the violation level increases, remove the timestamp with VL of 0
        plugin.getViolationManager().increaseViolationLevel(player, amount, blockType, count, vein, location);
    }
}
