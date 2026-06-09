package link.star_dust.MinerTrack.core.violation;

import link.star_dust.MinerTrack.common.CommonLocation;
import link.star_dust.MinerTrack.core.CoreLogger;
import link.star_dust.MinerTrack.core.config.WebhookConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core webhook engine — lives in the {@code core/violation/} package
 * alongside {@link ViolationEngine}, which it complements.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Read the {@code DiscordWebHook.*} configuration via
 *       {@link WebhookConfig} (no stringly-typed paths leak into the
 *       engine).</li>
 *   <li>Format a Discord payload (standard embed <em>or</em> a custom
 *       JSON body, depending on the operator's config).</li>
 *   <li>Hand the rendered payload to a platform-supplied
 *       {@link Sender} for asynchronous HTTP delivery.</li>
 * </ul>
 *
 * <p>The engine itself is platform-agnostic; it owns <em>no</em>
 * reference to a Bukkit / Fabric / HTTP client. A platform instantiates
 * one during {@code onEnable}, injects its {@link Sender} (e.g.
 * {@code BukkitWebhookSender}), and passes the engine to
 * {@link ViolationEngine} via
 * {@link ViolationEngine#setWebhookEngine(WebhookEngine)}. Violation
 * events then flow:
 * <pre>
 *   MiningListener → ViolationEngine.increaseViolation()
 *                   → (threshold check)
 *                   → WebhookEngine.onViolationIncrease(...)
 *                   → Sender.sendAsync(url, payload)
 * </pre>
 *
 * <p>The class is intentionally final and stateless apart from the
 * injected {@link Sender} and {@link WebhookConfig} — making it safe to
 * call from any thread, and trivial to swap out in tests.
 */
public final class WebhookEngine {

    /**
     * Platform-supplied async HTTP transport. Implementations must not
     * block the calling thread; they own their own thread pool /
     * scheduler. The {@code url} argument is always already validated
     * non-null / non-empty by the engine.
     */
    public interface Sender {
        /** POST a pre-rendered JSON {@code payload} to {@code url}. */
        void sendAsync(String url, String payload);

        /**
         * Render and POST a custom-JSON body. {@code placeholders} map
         * logical keys (e.g. {@code "player"}, {@code "world"}) to
         * resolved values; the engine handles the placeholder →
         * {@code %key%} substitution so the sender does not need to
         * know the placeholder syntax.
         */
        void sendAsync(String url, Map<String, String> placeholders, String jsonFormat);
    }

    private final WebhookConfig config;
    private final Sender sender;

    public WebhookEngine(WebhookConfig config, Sender sender) {
        this.config = config;
        this.sender = sender;
    }

    /**
     * Trigger a webhook if the player's current violation level meets
     * the configured threshold. A no-op when the webhook is disabled,
     * the threshold is not met, or the URL is missing.
     */
    public void onViolationIncrease(UUID playerId, String playerName, int vl,
                                    String oreType, int minedVeins, int oreCount,
                                    CommonLocation location) {
        if (config == null || !config.isEnabled()) return;
        if (vl < config.getVlRequired()) return;
        if (config.getUrl() == null || config.getUrl().isEmpty()) {
            CoreLogger.warning("Webhook enabled but WebHookURL is empty; skipping send.");
            return;
        }

        Map<String, String> placeholders = buildPlaceholders(playerId, playerName, vl,
                oreType, minedVeins, oreCount, location);

        WebhookConfig.CustomJson custom = config.getCustomJson();
        if (custom.isEnabled() && custom.getFormat() != null && !custom.getFormat().isEmpty()) {
            sender.sendAsync(config.getUrl(), placeholders, custom.getFormat());
            return;
        }

        String payload = buildEmbedPayload(placeholders);
        sender.sendAsync(config.getUrl(), payload);
    }

    /**
     * Build the placeholder map shared by both the custom-JSON path and
     * the standard-embed path. Centralised so the two renderers cannot
     * drift apart on what {@code %world%} etc. expand to.
     */
    private Map<String, String> buildPlaceholders(UUID playerId, String playerName, int vl,
                                                  String oreType, int minedVeins, int oreCount,
                                                  CommonLocation location) {
        String worldName = location != null ? location.world : "unknown";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", playerName == null ? "" : playerName);
        placeholders.put("player_uuid", playerId == null ? "" : playerId.toString());
        placeholders.put("player_vl", String.valueOf(vl));
        placeholders.put("ore_type", oreType == null ? "" : oreType);
        placeholders.put("mined_veins", String.valueOf(minedVeins));
        placeholders.put("ore_count", String.valueOf(oreCount));
        placeholders.put("world", worldName);
        placeholders.put("pos_x", location != null ? String.valueOf(location.x) : "0");
        placeholders.put("pos_y", location != null ? String.valueOf(location.y) : "0");
        placeholders.put("pos_z", location != null ? String.valueOf(location.z) : "0");
        placeholders.put("timestamp", timestamp);
        return placeholders;
    }

    /**
     * Render a standard Discord embed payload by substituting
     * placeholders into each line of the configured description
     * template, then wrapping the result in a
     * {@code {"embeds":[{...}]}} envelope.
     */
    private String buildEmbedPayload(Map<String, String> placeholders) {
        WebhookConfig.Embed embed = config.getEmbed();
        String description = renderDescription(embed.getText(), placeholders);
        return "{\"embeds\":[{\"title\":" + escapeJson(embed.getTitle())
                + ",\"description\":" + escapeJson(description)
                + ",\"color\":" + embed.getColor() + "}]}";
    }

    private String renderDescription(List<String> template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < template.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(substitute(template.get(i), placeholders));
        }
        return sb.toString();
    }

    /**
     * Replace every {@code %key%} in {@code template} with the matching
     * value from {@code placeholders}. Unknown keys are left as-is
     * (matching the v1 behaviour) so the operator can spot a typo in
     * their config instead of getting silent blanks.
     */
    static String substitute(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '%' && i + 1 < n) {
                int end = template.indexOf('%', i + 1);
                if (end > i + 1) {
                    String key = template.substring(i + 1, end);
                    String value = placeholders.get(key);
                    if (value != null) {
                        out.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Minimal JSON string escaper for the standard-embed path. The
     * custom-JSON path is sent verbatim, so a malformed user template
     * is the operator's problem (same behaviour as v1).
     */
    static String escapeJson(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Drop-in replacement used by the custom-JSON path: render the
     * user's template by substituting {@code %key%} tokens, then
     * hand the result to the sender. Exposed for the
     * {@code BukkitWebhookSender} shim to call without needing to
     * know the placeholder syntax.
     */
    public String renderCustomJson(String jsonFormat, Map<String, String> placeholders) {
        return substitute(jsonFormat, placeholders);
    }
}
