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
    /**
     * Optional webhook engine injected by the platform during
     * {@code onEnable}. When non-null, the engine is queried with the
     * new VL after every increment and a Discord payload is dispatched
     * if the configured threshold is met. Held via a setter (rather
     * than via the constructor) so the violation engine can be built
     * before the platform finishes wiring its webhook config + sender.
     */
    private WebhookEngine webhookEngine;

    public ViolationEngine(ViolationManagerBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Wire the platform's webhook engine. Called once at startup,
     * after both the violation engine and the webhook engine have
     * been constructed. A {@code null} argument is tolerated and
     * treated as "no webhook configured" — useful for unit tests.
     */
    public void setWebhookEngine(WebhookEngine webhookEngine) {
        this.webhookEngine = webhookEngine;
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
        // The v2 design returns either a `Map` (Map-backed
        // configs) or a Bukkit `ConfigurationSection` (the
        // live delegate the v1-style in-place merger
        // produced). Normalise to a `Map<String, Object>`
        // view via the platform-neutral {@link
        // link.star_dust.MinerTrack.common.PlatformTypes}
        // helper (which uses reflection to call
        // {@code getKeys(false)} and {@code get(key)} on a
        // Bukkit section without ever referencing the class
        // literal).
        java.util.Map<String, Object> section = null;
        if (raw instanceof Map) {
            section = (java.util.Map<String, Object>) raw;
        } else if (link.star_dust.MinerTrack.common.PlatformTypes.isConfigurationSection(raw)) {
            java.util.Set<String> keys = link.star_dust.MinerTrack.common.PlatformTypes.getKeys(raw);
            section = new java.util.LinkedHashMap<>();
            if (keys != null) {
                for (String k : keys) {
                    Object v = link.star_dust.MinerTrack.common.PlatformTypes.getValue(raw, k);
                    section.put(k, v);
                }
            }
        }
        if (section != null) {
            try {
                for (java.util.Map.Entry<String, Object> entry : section.entrySet()) {
                    try {
                        // SnakeYAML parses numeric YAML keys as Integer, not
                        // String. entry.getKey() is typed String (erased to
                        // Object at runtime), so Integer.parseInt(entry.getKey())
                        // would throw ClassCastException when the key is
                        // actually an Integer. Use String.valueOf() to safely
                        // convert any key type to String first.
                        int threshold = Integer.parseInt(String.valueOf(entry.getKey()));
                        if (threshold > oldLevel && threshold <= newLevel) {
                            Object commandsRaw = entry.getValue();
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
            // `location.world` is the canonical Minecraft
            // dimension id (`minecraft:overworld`,
            // `minecraft:the_nether`, `minecraft:the_end`, or
            // `minecraft:<folder>` for non-vanilla worlds),
            // resolved from the Bukkit folder name at the
            // listener boundary. The platform's
            // `getDisplayWorldName` indirection is preserved
            // for future platforms that might want a different
            // display format (e.g. a localised name), but on
            // the current Bukkit path it returns the canonical
            // id unchanged.
            String worldKey = (location != null) ? location.world : "unknown";
            String worldName = bridge.getDisplayWorldName(worldKey);
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

            if (webhookEngine != null) {
                // The webhook engine reads its own enabled / vl-required
                // thresholds from WebhookConfig, so a simple dispatch is
                // enough. Engine decides whether to actually POST.
                webhookEngine.onViolationIncrease(playerId, playerName, newLevel,
                        blockType, vein, count, location);
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
