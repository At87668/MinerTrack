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
        /**
         * POST a pre-rendered JSON {@code payload} to {@code url}.
         * Implementations are responsible for off-thread dispatch
         * (Bukkit async scheduler / Paper async scheduler / plain
         * daemon thread) and for surfacing non-2xx responses in a
         * way the operator can diagnose from the server log.
         */
        void sendAsync(String url, String payload);
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
            // Pre-render here (with JSON-aware value escaping) so the
            // sender only has to do the HTTP POST. We do NOT delegate
            // the escape-for-JSON work to the sender: the engine owns
            // JSON correctness, the sender owns transport.
            String customPayload = substitute(custom.getFormat(), placeholders, true);
            sender.sendAsync(config.getUrl(), customPayload);
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
     *
     * <p>Per
     * <a href="https://discord.com/developers/docs/resources/channel#embed-object-embed-limits">Discord's
     * embed limits</a>, the wire payload is constrained as follows
     * (these limits are enforced server-side and a request that
     * violates any of them returns HTTP 400):
     * <ul>
     *   <li>{@code title} — 256 characters</li>
     *   <li>{@code description} — 4096 characters</li>
     *   <li>Sum of all character counts in an embed — 6000 characters</li>
     * </ul>
     * We clamp {@code title} and {@code description} to those limits
     * here so an admin who edits the language file with a 5000-char
     * title gets a truncated embed, not a webhook that stops working
     * silently. The {@code 6000} sum limit is dominated by the 4096
     * description cap (we never set fields / footer / author from the
     * v2 config), so a single clamp on description is enough.
     */
    private String buildEmbedPayload(Map<String, String> placeholders) {
        WebhookConfig.Embed embed = config.getEmbed();
        String description = clamp(renderDescription(embed.getText(), placeholders),
                DISCORD_DESCRIPTION_MAX);
        String title = clamp(embed.getTitle() == null ? "" : embed.getTitle(),
                DISCORD_TITLE_MAX);
        return "{\"embeds\":[{\"title\":" + escapeJson(title)
                + ",\"description\":" + escapeJson(description)
                + ",\"color\":" + embed.getColor() + "}]}";
    }

    // Discord embed field limits (kept in one place so the constants
    // cannot drift apart between the clamp and the diagnostics).
    private static final int DISCORD_TITLE_MAX = 256;
    private static final int DISCORD_DESCRIPTION_MAX = 4096;

    /**
     * Truncate {@code s} to {@code max} characters without breaking a
     * UTF-16 surrogate pair. Returning a string that ends on a high
     * surrogate would be re-encoded to UTF-8 as 3 malformed bytes,
     * which Discord would reject as a malformed JSON string. We
     * shorten by one code point when the cut would land on a high
     * surrogate.
     */
    static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
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
     *
     * <p>When {@code escapeForJson} is {@code true}, every replaced
     * value is JSON-escaped. The result is dropped into the template
     * <em>without</em> added quotes, but the operator is expected to
     * write the placeholder <em>inside</em> an existing JSON string
     * literal:
     * <pre>
     *   "name": "%player%"     ->  "name": "Steve"
     *   "x":    %pos_x%        ->  "x": "100"   (Discord accepts the
     *                                            string form, the
     *                                            original `100` had
     *                                            no quotes and would
     *                                            have produced a 400
     *                                            because
     *                                            {@code minecraft:overworld}
     *                                            was emitted unquoted)
     * </pre>
     * To handle both styles — value-in-quotes and bare-value — the
     * placeholder substitution <em>always</em> emits a JSON string
     * literal regardless of what surrounds it in the template. If the
     * template already supplied a pair of double quotes around the
     * placeholder ({@code "%player%"}), we strip them on the way
     * through, so the final wire body is well-formed JSON in every
     * case:
     * <pre>
     *   "name": "%player%"     template  ->  "name": "Steve"
     *   "x":    %pos_x%        template  ->  "x":    "100"
     * </pre>
     * Use {@code false} for the standard embed path, where the
     * value is later wrapped in our own JSON string and escaped by
     * {@link #escapeJson(String)} — the placeholder replacement here
     * must not add a second pair of quotes and must not strip
     * anything.
     */
    static String substitute(String template, Map<String, String> placeholders, boolean escapeForJson) {
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
                        if (escapeForJson) {
                            // Peek at the character immediately before
                            // the `%` and the character immediately
                            // after the closing `%`. If the template
                            // already wrapped the placeholder in a
                            // pair of double quotes (the convention in
                            // the default config.yml), strip those
                            // surrounding quotes so we don't emit
                            // double-quoted output. Otherwise leave
                            // the template characters alone.
                            boolean leftIsQuote = i > 0 && out.length() > 0
                                && out.charAt(out.length() - 1) == '"';
                            boolean rightIsQuote = end + 1 < n
                                && template.charAt(end + 1) == '"';
                            if (leftIsQuote) {
                                out.setLength(out.length() - 1);
                            }
                            out.append('"').append(escapeJsonStringContent(value)).append('"');
                            if (rightIsQuote) {
                                // skip the template's closing quote
                                i = end + 2;
                                continue;
                            }
                        } else {
                            out.append(value);
                        }
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

    /** Backwards-compatible overload: standard embed path, no JSON
     *  escaping of values (the outer {@link #escapeJson(String)} does
     *  the work). */
    static String substitute(String template, Map<String, String> placeholders) {
        return substitute(template, placeholders, false);
    }

    /**
     * Minimal JSON string escaper for the standard-embed path. The
     * custom-JSON path used to send values verbatim (matching v1's
     * {@code String.replace} behaviour), which produced invalid JSON
     * whenever a placeholder resolved to a value containing a quote,
     * backslash, or newline — Discord then returned HTTP 400 and the
     * operator had no way to tell why. We now also escape the values
     * substituted into the custom-JSON template (see
     * {@link #substitute(String, Map, boolean)}), so an unparseable
     * template is the operator's problem, but a malicious / unusual
     * player name or world name is not.
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
     * Escape the <em>contents</em> of a JSON string literal — same
     * rules as {@link #escapeJson(String)} but without the surrounding
     * quotes. Used by the custom-JSON path so a placeholder value
     * can be dropped into a {@code "..."} literal that the operator
     * already wrote in the template, without breaking the surrounding
     * JSON.
     */
    static String escapeJsonStringContent(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
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
