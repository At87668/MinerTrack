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
        if (!bridge.getConfigBoolean("xray.natural-detection.enable", true)) return false;

        int caveAirMultiplier = bridge.getConfigForWorld(worldName, "xray.natural-detection.cave.CaveAirMultiplier", 5);
        int airThreshold = bridge.getConfigForWorld(worldName, "xray.natural-detection.cave.air-threshold", 14);
        int detectionRange = bridge.getConfigForWorld(worldName, "xray.natural-detection.cave.detection-range", 3);

        int waterThreshold = bridge.getConfigForWorld(worldName, "xray.natural-detection.sea.water-threshold", 14);
        int lavaThreshold = bridge.getConfigForWorld(worldName, "xray.natural-detection.lava-sea.lava-threshold", 14);
        boolean checkRunningWater = bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.sea.check-running-water", false);

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
                    if (bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.cave.ignore-artificial-air", true)
                            && ("AIR".equals(type) || "CAVE_AIR".equals(type))) {
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

        if (airCount > airThreshold && bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.cave.check_skip_vl", true)) return true;
        if (waterCount > waterThreshold && bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.sea.check_skip_vl", true)) return true;
        if (lavaCount > lavaThreshold && bridge.getConfigForWorldBoolean(worldName, "xray.natural-detection.lava-sea.check_skip_vl", true)) return true;

        return false;
    }
}
