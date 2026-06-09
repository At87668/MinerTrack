package link.star_dust.MinerTrack.core.config;

import link.star_dust.MinerTrack.common.CommonYaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strongly-typed view of the {@code DiscordWebHook.*} section of
 * {@code config.yml}. Built once from a {@link CommonYaml} snapshot of the
 * merged main config; immutable thereafter.
 *
 * <p>Reading every key through this façade keeps the
 * {@code core/violation} layer free of stringly-typed config paths and
 * gives the engine a single object to pass around. Values that the user
 * has not configured fall back to the same defaults the v1 webhook code
 * used (so the embed / custom-json shape is stable across upgrades).
 *
 * <p>Format strings and embed text use the placeholder syntax documented
 * in {@code config.yml}: {@code %player%}, {@code %player_uuid%},
 * {@code %player_vl%}, {@code %ore_type%}, {@code %ore_count%},
 * {@code %mined_veins%}, {@code %pos_x%}, {@code %pos_y%}, {@code %pos_z%},
 * {@code %world%}, {@code %timestamp%}.
 */
public final class WebhookConfig {

    /** All keys live under this top-level section in {@code config.yml}. */
    public static final String ROOT = "DiscordWebHook";

    private final boolean enabled;
    private final String url;
    private final int vlRequired;
    private final Embed embed;
    private final CustomJson customJson;

    private WebhookConfig(boolean enabled, String url, int vlRequired,
                          Embed embed, CustomJson customJson) {
        this.enabled = enabled;
        this.url = url;
        this.vlRequired = vlRequired;
        this.embed = embed;
        this.customJson = customJson;
    }

    /**
     * Read the {@code DiscordWebHook.*} section out of {@code main} and
     * return an immutable snapshot. Missing keys fall back to safe
     * defaults so the engine can still call getters on a partially
     * configured webhook.
     */
    @SuppressWarnings("unchecked")
    public static WebhookConfig from(CommonYaml main) {
        if (main == null) {
            return new WebhookConfig(false, "", Integer.MAX_VALUE,
                    Embed.defaults(), CustomJson.disabled());
        }

        Object root = main.get(ROOT);
        if (!(root instanceof Map)) {
            return new WebhookConfig(false, "", Integer.MAX_VALUE,
                    Embed.defaults(), CustomJson.disabled());
        }
        Map<String, Object> section = (Map<String, Object>) root;

        boolean enabled = readBoolean(section, "enable", false);
        String url = readString(section, "WebHookURL", "");
        // The user can write `vl-required` (kebab) or `vlRequired` / `vl_required`
        // depending on the YAML editor; check both for robustness.
        int vlRequired = readInt(section, "vl-required",
                readInt(section, "vlRequired",
                readInt(section, "vl_required", Integer.MAX_VALUE)));

        Embed embed = Embed.fromSection((Map<String, Object>) section.get("vl-add-message"));
        CustomJson customJson = CustomJson.fromSection((Map<String, Object>) section.get("custom-json"));

        return new WebhookConfig(enabled, url, vlRequired, embed, customJson);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getUrl() {
        return url;
    }

    public int getVlRequired() {
        return vlRequired;
    }

    public Embed getEmbed() {
        return embed;
    }

    public CustomJson getCustomJson() {
        return customJson;
    }

    // ── Nested config objects ─────────────────────────────────────────────

    /**
     * Standard Discord embed used when {@code custom-json} is disabled.
     * Holds the title, description template lines, and the embed color.
     */
    public static final class Embed {
        private final String title;
        private final List<String> text;
        private final int color;

        private Embed(String title, List<String> text, int color) {
            this.title = title;
            this.text = text;
            this.color = color;
        }

        @SuppressWarnings("unchecked")
        static Embed fromSection(Map<String, Object> section) {
            if (section == null) return defaults();
            String title = readString(section, "title", "X-Ray Alert");
            int color = readInt(section, "color", 0xFF5733);
            Object textRaw = section.get("text");
            List<String> text = new ArrayList<>();
            if (textRaw instanceof List) {
                for (Object o : (List<Object>) textRaw) {
                    if (o != null) text.add(String.valueOf(o));
                }
            }
            if (text.isEmpty()) text = defaults().text;
            return new Embed(title, Collections.unmodifiableList(text), color);
        }

        static Embed defaults() {
            List<String> text = new ArrayList<>();
            text.add("Player Name: %player%");
            text.add("Player UUID: %player_uuid%");
            text.add("Player Violation Level: %player_vl%");
            text.add("");
            text.add("Mining Ore: %ore_type%x%ore_count%");
            text.add("Mined Veins: %mined_veins%");
            text.add("");
            text.add("World: %world%");
            text.add("Pos: %pos_x% %pos_y% %pos_z%");
            text.add("");
            text.add("%timestamp%");
            return new Embed("X-Ray Alert", Collections.unmodifiableList(text), 0xFF5733);
        }

        public String getTitle() { return title; }
        public List<String> getText() { return text; }
        public int getColor() { return color; }
    }

    /**
     * Custom JSON body template. When {@link #isEnabled()} returns
     * {@code true}, the engine substitutes placeholders into
     * {@link #getFormat()} and POSTs the result verbatim (no extra
     * wrapping in {@code embeds:[...]}).
     */
    public static final class CustomJson {
        private final boolean enabled;
        private final String format;

        private CustomJson(boolean enabled, String format) {
            this.enabled = enabled;
            this.format = format;
        }

        static CustomJson fromSection(Map<String, Object> section) {
            if (section == null) return disabled();
            boolean enabled = readBoolean(section, "enable", false);
            String format = readString(section, "format", "");
            return new CustomJson(enabled, format == null ? "" : format);
        }

        static CustomJson disabled() {
            return new CustomJson(false, "");
        }

        public boolean isEnabled() { return enabled; }
        public String getFormat() { return format; }
    }

    // ── Map / list readers (defensive against bad user YAML) ─────────────

    private static boolean readBoolean(Map<String, Object> section, String key, boolean def) {
        Object v = section.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes")) return true;
            if (s.equalsIgnoreCase("false") || s.equals("0") || s.equalsIgnoreCase("no")) return false;
        }
        return def;
    }

    private static String readString(Map<String, Object> section, String key, String def) {
        Object v = section.get(key);
        if (v == null) return def;
        return String.valueOf(v);
    }

    private static int readInt(Map<String, Object> section, String key, int def) {
        Object v = section.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /**
     * Defensive deep-clone for callers that want to inspect a section
     * as a plain {@code Map<String, Object>} (e.g. legacy logging
     * sites). Never returns {@code null} — returns an empty map for a
     * missing / non-map section.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> snapshotSection(CommonYaml main, String path) {
        Object o = main == null ? null : main.get(path);
        if (o instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) o);
        }
        return new LinkedHashMap<>();
    }
}
