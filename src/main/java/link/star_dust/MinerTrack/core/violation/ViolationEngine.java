package link.star_dust.MinerTrack.core.violation;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.common.ViolationManagerBridge;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ViolationEngine {
    private final ViolationManagerBridge bridge;
    private final Map<UUID, Integer> violationLevels = new HashMap<>();
    private final Map<UUID, Long> vlChangedTimestamp = new HashMap<>();
    private final Map<UUID, Long> vlZeroTimestamp = new HashMap<>();

    public ViolationEngine(ViolationManagerBridge bridge) {
        this.bridge = bridge;
    }

    public int getViolationLevel(UUID playerId) {
        return violationLevels.getOrDefault(playerId, 0);
    }

    public void increaseViolation(UUID playerId, String playerName, int increment, String blockType, int count, int vein, CommonLocation location) {
        long now = System.currentTimeMillis();
        vlZeroTimestamp.remove(playerId);
        vlChangedTimestamp.put(playerId, now);

        int oldLevel = getViolationLevel(playerId);
        int newLevel = oldLevel + increment;
        violationLevels.put(playerId, newLevel);

        // Run configured commands if thresholds passed
        Object raw = bridge.getConfig("xray.commands");
        if (raw instanceof Map<?, ?>) {
            try {
                Map<?,?> section = (Map<?,?>) raw;
                for (Object keyObj : section.keySet()) {
                    try {
                        int threshold = Integer.parseInt(String.valueOf(keyObj));
                        if (threshold > oldLevel && threshold <= newLevel) {
                            Object commandsRaw = section.get(keyObj);
                            if (commandsRaw instanceof Iterable<?>) {
                                for (Object o : (Iterable<?>) commandsRaw) {
                                    if (o != null) {
                                        String cmd = String.valueOf(o).replace("%player%", playerName);
                                        bridge.runConsoleCommand(cmd);
                                        bridge.appendCommandLog(cmd);
                                    }
                                }
                            } else if (commandsRaw != null) {
                                String cmd = String.valueOf(commandsRaw).replace("%player%", playerName);
                                bridge.runConsoleCommand(cmd);
                                bridge.appendCommandLog(cmd);
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception ignored) {}
        }

        if (newLevel >= 1) {
            LocalDateTime nowDateTime = LocalDateTime.now();
            String year = String.valueOf(nowDateTime.getYear());
            String month = String.format("%02d", nowDateTime.getMonthValue());
            String day = String.format("%02d", nowDateTime.getDayOfMonth());
            String hour = String.format("%02d", nowDateTime.getHour());
            String minute = String.format("%02d", nowDateTime.getMinute());
            String second = String.format("%02d", nowDateTime.getSecond());

            // Build verbose message
            String verboseFormat = bridge.getPrefixedMessage("verbose-format");
            String worldName = (location != null) ? location.world : "unknown";
            String formattedMessage = verboseFormat
                .replace("%player%", playerName)
                .replace("%vl%", String.valueOf(newLevel))
                .replace("%add_vl%", String.valueOf(increment))
                .replace("%block_type%", blockType)
                .replace("%count%", String.valueOf(count))
                .replace("%vein_count%", String.valueOf(vein))
                .replace("%world%", worldName)
                .replace("%pos_x%", location != null ? String.valueOf(location.x) : "0")
                .replace("%pos_y%", location != null ? String.valueOf(location.y) : "0")
                .replace("%pos_z%", location != null ? String.valueOf(location.z) : "0");

            for (UUID uuid : bridge.getVerbosePlayers()) {
                bridge.sendMessageToPlayer(uuid, formattedMessage);
            }

            if (bridge.isVerboseConsoleEnabled()) {
                bridge.runConsoleCommand("echo " + formattedMessage);
            }

            // Log to file
            if (bridge.isLogFileEnabled()) {
                String logFormat = bridge.getLogFormat();
                String line = logFormat
                    .replace("%year%", year)
                    .replace("%month%", month)
                    .replace("%day%", day)
                    .replace("%hour%", hour)
                    .replace("%minute%", minute)
                    .replace("%second%", second)
                    .replace("%player%", playerName)
                    .replace("%vl%", String.valueOf(newLevel))
                    .replace("%add_vl%", String.valueOf(increment))
                    .replace("%block_type%", blockType)
                    .replace("%count%", String.valueOf(count))
                    .replace("%vein_count%", String.valueOf(vein))
                    .replace("%world%", worldName)
                    .replace("%pos_x%", location != null ? String.valueOf(location.x) : "0")
                    .replace("%pos_y%", location != null ? String.valueOf(location.y) : "0")
                    .replace("%pos_z%", location != null ? String.valueOf(location.z) : "0");
                bridge.appendLogLine(line);
            }

            if (bridge.isWebHookEnabled() && newLevel >= bridge.getWebHookVLRequired()) {
                bridge.sendWebhook(playerId, blockType, vein, count, location);
            }
        }
    }

    public void processDecay() {
        long now = System.currentTimeMillis();
        Set<UUID> keys = new HashSet<>(violationLevels.keySet());
        for (UUID playerId : keys) {
            int currentVL = violationLevels.getOrDefault(playerId, 0);
            if (currentVL > 0) {
                long decayIntervalMillis = bridge.getConfigInt("xray.decay.interval", 3) * 60 * 1000L;
                Long lastChanged = vlChangedTimestamp.get(playerId);
                if (lastChanged != null && now - lastChanged > decayIntervalMillis) {
                    int decayAmount = bridge.getConfigInt("xray.decay.amount", 1);
                    double decayFactor = bridge.getConfigDouble("xray.decay.factor", 0.9);
                    boolean useFactor = bridge.getConfigBoolean("xray.decay.use_factor", false);
                    int newVL = useFactor ? (int)Math.ceil(currentVL * decayFactor) : Math.max(0, currentVL - decayAmount);
                    violationLevels.put(playerId, newVL);
                    vlChangedTimestamp.put(playerId, now);
                    if (newVL == 0) vlZeroTimestamp.put(playerId, now);
                }
            } else {
                vlZeroTimestamp.putIfAbsent(playerId, now);
            }
        }
    }

    public void resetViolation(UUID playerId) {
        violationLevels.remove(playerId);
        vlChangedTimestamp.remove(playerId);
        vlZeroTimestamp.remove(playerId);
        bridge.resetViolation(playerId);
    }
}
