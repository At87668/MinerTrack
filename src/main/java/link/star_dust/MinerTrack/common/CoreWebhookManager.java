package link.star_dust.MinerTrack.common;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Core webhook management - platform-agnostic formatting and trigger logic.
 * Actual HTTP sending is delegated to the platform via WebhookSenderBridge.
 */
public class CoreWebhookManager {

    public interface WebhookSenderBridge {
        void sendAsync(String url, String jsonPayload);
        void sendAsync(String url, Map<String, String> placeholders, String jsonFormat);
    }

    private final WebhookSenderBridge sender;
    private final ViolationManagerBridge vlBridge;

    public CoreWebhookManager(ViolationManagerBridge vlBridge, WebhookSenderBridge sender) {
        this.vlBridge = vlBridge;
        this.sender = sender;
    }

    public void onViolationIncrease(UUID playerId, String oreType, int minedVeins, int oreCount, CommonLocation location) {
        if (!vlBridge.isWebHookEnabled()) return;

        int vl = vlBridge.getViolationLevel(playerId);
        int required = vlBridge.getWebHookVLRequired();

        if (vl >= required) {
            sendWebhook(playerId, oreType, minedVeins, oreCount, location);
        }
    }

    private void sendWebhook(UUID playerId, String oreType, int minedVeins, int oreCount, CommonLocation location) {
        String worldName = location != null ? location.world : "unknown";
        int vl = vlBridge.getViolationLevel(playerId);

        // Check custom JSON first
        Object customJsonSection = vlBridge.getConfigSection("DiscordWebHook.custom-json");
        if (customJsonSection instanceof Map && !((Map) customJsonSection).isEmpty()) {
            String jsonFormat = (String) vlBridge.getConfig("DiscordWebHook.custom-json.format");
            if (jsonFormat != null && !jsonFormat.isEmpty()) {
                java.util.Map<String, String> placeholders = new java.util.HashMap<>();
                placeholders.put("player", getPlayerName(playerId));
                placeholders.put("player_uuid", playerId.toString());
                placeholders.put("player_vl", String.valueOf(vl));
                placeholders.put("ore_type", oreType);
                placeholders.put("mined_veins", String.valueOf(minedVeins));
                placeholders.put("ore_count", String.valueOf(oreCount));
                placeholders.put("world", worldName);
                placeholders.put("pos_x", location != null ? String.valueOf(location.x) : "0");
                placeholders.put("pos_y", location != null ? String.valueOf(location.y) : "0");
                placeholders.put("pos_z", location != null ? String.valueOf(location.z) : "0");
                placeholders.put("timestamp", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_DATE_TIME));

                String url = (String) vlBridge.getConfig("DiscordWebHook.WebHookURL");
                sender.sendAsync(url, placeholders, jsonFormat);
                return;
            }
        }

        // Standard embed format
        String title = (String) vlBridge.getConfig("DiscordWebHook.vl-add-message.title");
        List<String> textTemplate = (List<String>) vlBridge.getConfig("DiscordWebHook.vl-add-message.text");
        int color = (int) vlBridge.getConfig("DiscordWebHook.vl-add-message.color");

        String description = buildDescription(textTemplate, playerId, oreType, minedVeins, oreCount, worldName, location, vl);
        String payload = buildEmbedPayload(title, description, color);

        String url = (String) vlBridge.getConfig("DiscordWebHook.WebHookURL");
        sender.sendAsync(url, payload);
    }

    private String getPlayerName(UUID playerId) {
        return "Player";
    }

    private String buildDescription(List<String> textTemplate, UUID playerId, String oreType,
                                    int minedVeins, int oreCount, String worldName,
                                    CommonLocation location, int vl) {
        if (textTemplate == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : textTemplate) {
            sb.append(line.replace("%player%", getPlayerName(playerId))
                .replace("%player_uuid%", playerId.toString())
                .replace("%player_vl%", String.valueOf(vl))
                .replace("%ore_type%", oreType)
                .replace("%mined_veins%", String.valueOf(minedVeins))
                .replace("%ore_count%", String.valueOf(oreCount))
                .replace("%world%", worldName)
                .replace("%pos_x%", location != null ? String.valueOf(location.x) : "0")
                .replace("%pos_y%", location != null ? String.valueOf(location.y) : "0")
                .replace("%pos_z%", location != null ? String.valueOf(location.z) : "0")
                .replace("%timestamp%", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_DATE_TIME)));
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildEmbedPayload(String title, String description, int color) {
        // Simple JSON building without Gson dependency in core
        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{\"title\":");
        sb.append(escapeJson(title));
        sb.append(",\"description\":");
        sb.append(escapeJson(description));
        sb.append(",\"color\":");
        sb.append(color);
        sb.append("}]}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
