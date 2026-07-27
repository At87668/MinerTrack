/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 *
 *  Copyright (c) At87668 (Author87668) <https://github.com/At87668>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
        // The v2 design returns either a `Map` (Map-backed
        // configs) or a Bukkit `ConfigurationSection` (the
        // live delegate the v1-style in-place merger
        // produced). Normalise both to a
        // `Map<String, Object>` view via the platform-neutral
        // {@link link.star_dust.MinerTrack.common.PlatformTypes}
        // helper (which uses reflection to call
        // {@code getValues(false)} on a Bukkit section
        // without ever referencing the class literal).
        Map<String, Object> section;
        if (root instanceof Map) {
            section = (Map<String, Object>) root;
        } else if (link.star_dust.MinerTrack.common.PlatformTypes.isConfigurationSection(root)) {
            section = link.star_dust.MinerTrack.common.PlatformTypes.getValues(root);
            if (section == null) section = java.util.Collections.emptyMap();
        } else {
            return new WebhookConfig(false, "", Integer.MAX_VALUE,
                    Embed.defaults(), CustomJson.disabled());
        }

        boolean enabled = readBoolean(section, "enable", false);
        String url = readString(section, "WebHookURL", "");
        // The user can write `vl-required` (kebab) or `vlRequired` / `vl_required`
        // depending on the YAML editor; check both for robustness.
        int vlRequired = readInt(section, "vl-required",
                readInt(section, "vlRequired",
                readInt(section, "vl_required", Integer.MAX_VALUE)));

        // The nested `vl-add-message` and `custom-json`
        // sections may also be Map-backed or
        // ConfigurationSection-backed. Resolve them with
        // the same normaliser.
        Embed embed = Embed.fromSection(normaliseSection(section.get("vl-add-message")));
        CustomJson customJson = CustomJson.fromSection(normaliseSection(section.get("custom-json")));

        return new WebhookConfig(enabled, url, vlRequired, embed, customJson);
    }

    /**
     * Coerce a possibly-`ConfigurationSection` value into a
     * `Map<String, Object>` for the typed `readBoolean` /
     * `readInt` / `readString` accessors below. Returns an
     * empty map for null / unknown shapes so the read
     * helpers fall through to their default values.
     */
    private static Map<String, Object> normaliseSection(Object raw) {
        if (raw instanceof Map) {
            return (Map<String, Object>) raw;
        }
        if (link.star_dust.MinerTrack.common.PlatformTypes.isConfigurationSection(raw)) {
            Map<String, Object> v = link.star_dust.MinerTrack.common.PlatformTypes.getValues(raw);
            return v == null ? java.util.Collections.emptyMap() : v;
        }
        return java.util.Collections.emptyMap();
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
