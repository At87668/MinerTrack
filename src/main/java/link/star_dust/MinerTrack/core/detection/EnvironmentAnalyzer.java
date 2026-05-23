package link.star_dust.MinerTrack.core.detection;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.DetectionBridge;

import java.util.List;
import java.util.UUID;

public class EnvironmentAnalyzer {
    private final DetectionBridge bridge;

    public EnvironmentAnalyzer(DetectionBridge bridge) {
        this.bridge = bridge;
    }

    public boolean isInNaturalEnvironment(String worldName, UUID playerId, CommonLocation location, List<CommonLocation> path) {
        if (bridge.getConfig("xray.natural.enable") instanceof Boolean) {
            boolean globalEnable = (Boolean) bridge.getConfig("xray.natural.enable");
            if (!globalEnable) return false;
        }

        int caveAirMultiplier = ((Number) bridge.getConfig("xray.natural.cave_air_multiplier")).intValue();
        int airThreshold = ((Number) bridge.getConfig("xray.natural.cave_bypass_air_threshold")).intValue();
        int detectionRange = ((Number) bridge.getConfig("xray.natural.cave_detection_range")).intValue();

        int waterThreshold = ((Number) bridge.getConfig("xray.natural.water_threshold")).intValue();
        int lavaThreshold = ((Number) bridge.getConfig("xray.natural.lava_threshold")).intValue();
        boolean checkRunningWater = Boolean.TRUE.equals(bridge.getConfig("xray.natural.check_running_water"));

        int airCount = 0;
        int waterCount = 0;
        int lavaCount = 0;

        int baseX = location.x;
        int baseY = location.y;
        int baseZ = location.z;

        for (int x = -detectionRange; x <= detectionRange; x++) {
            for (int y = -detectionRange; y <= detectionRange; y++) {
                for (int z = -detectionRange; z <= detectionRange; z++) {
                    CommonLocation checkLoc = new CommonLocation(worldName, baseX + x, baseY + y, baseZ + z);
                    String type = bridge.getBlockType(checkLoc.world, checkLoc.x, checkLoc.y, checkLoc.z);

                    boolean isArtificialAir = false;
                    if (Boolean.TRUE.equals(bridge.getConfig("xray.natural.ignore_artificial_air")) && ("AIR".equals(type) || "CAVE_AIR".equals(type))) {
                        if (bridge.isArtificialAir(playerId, checkLoc)) {
                            isArtificialAir = true;
                        }
                    }

                    if (isArtificialAir) continue;

                    switch (type) {
                        case "CAVE_AIR":
                            airCount += caveAirMultiplier;
                            break;
                        case "AIR":
                            airCount++;
                            break;
                        case "WATER":
                            if (!checkRunningWater || bridge.isWaterStill(checkLoc.world, checkLoc.x, checkLoc.y, checkLoc.z)) {
                                waterCount++;
                            }
                            break;
                        case "LAVA":
                            lavaCount++;
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        if (airCount > airThreshold && Boolean.TRUE.equals(bridge.getConfig("xray.natural.cave_skip_vl"))) return true;
        if (waterCount > waterThreshold && Boolean.TRUE.equals(bridge.getConfig("xray.natural.sea_skip_vl"))) return true;
        if (lavaCount > lavaThreshold && Boolean.TRUE.equals(bridge.getConfig("xray.natural.lava_sea_skip_vl"))) return true;

        return false;
    }
}
