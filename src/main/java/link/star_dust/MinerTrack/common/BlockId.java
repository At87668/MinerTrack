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

package link.star_dust.MinerTrack.common;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonical Minecraft block / item identifiers used everywhere the plugin
 * compares or serialises block names.
 *
 * Historically the project used Bukkit's {@code Material} enum names
 * (e.g. {@code DIAMOND_ORE}, {@code WATER}, {@code AIR}). Those are
 * platform-specific and have to be translated on Fabric. The internal
 * canonical format is the Minecraft namespace id
 * ({@code minecraft:<lower_snake_case>}), as it appears in
 * {@code /give}, world save data and the Fabric API
 * ({@code Identifier.of("minecraft", "diamond_ore")}).
 *
 * This class is the single point of truth for:
 * <ul>
 *   <li>Normalising arbitrary user input (Bukkit enum, raw namespace,
 *       mixed case) into a canonical {@code minecraft:xxx} string.</li>
 *   <li>Recognising the canonical ids of the few special blocks
 *       (air, water, lava, cave air) the detection logic cares about.</li>
 * </ul>
 *
 * <p>Instance equality / hashing use the canonical form so configs and
 * runtime lookups can share a single map.
 */
public final class BlockId {

    // ── Canonical namespace id constants ────────────────────────────────
    public static final String MINECRAFT = "minecraft";
    public static final String NAMESPACE_PREFIX = MINECRAFT + ":";

    public static final String AIR = namespace("air");
    public static final String CAVE_AIR = namespace("cave_air");
    public static final String WATER = namespace("water");
    public static final String LAVA = namespace("lava");

    // Common ore ids kept here so detection code does not have to repeat
    // the magic strings. Users can still reference any id in their config.
    public static final String DIAMOND_ORE = namespace("diamond_ore");
    public static final String DEEPSLATE_DIAMOND_ORE = namespace("deepslate_diamond_ore");
    public static final String EMERALD_ORE = namespace("emerald_ore");
    public static final String DEEPSLATE_EMERALD_ORE = namespace("deepslate_emerald_ore");
    public static final String ANCIENT_DEBRIS = namespace("ancient_debris");

    private BlockId() {}

    /**
     * Build a {@code minecraft:<path>} id from a snake_case path
     * (no namespace separator allowed).
     */
    public static String namespace(String path) {
        Objects.requireNonNull(path, "path");
        if (path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("path must not contain a namespace separator: " + path);
        }
        return NAMESPACE_PREFIX + path;
    }

    /**
     * Normalise an arbitrary block identifier (Bukkit enum, raw namespace,
     * mixed case, with or without {@code minecraft:}) into the canonical
     * form {@code minecraft:<lower_snake_case>}.
     *
     * Returns {@code null} for null or blank input so callers can pass
     * possibly-missing config values without explicit null guards.
     */
    public static String normalize(String id) {
        if (id == null) return null;
        String trimmed = id.trim();
        if (trimmed.isEmpty()) return null;
        int colon = trimmed.indexOf(':');
        String path = colon >= 0 ? trimmed.substring(colon + 1) : trimmed;
        // Bukkit enums use UPPER_SNAKE_CASE; Minecraft ids use lower_snake_case.
        String lowered = path.toLowerCase(Locale.ROOT);
        return NAMESPACE_PREFIX + lowered;
    }

    /** Same as {@link #normalize} but never returns null (empty string instead). */
    public static String normalizeOrEmpty(String id) {
        String n = normalize(id);
        return n == null ? "" : n;
    }

    /** Strip the {@code minecraft:} prefix and return the path portion. */
    public static String pathOf(String canonicalId) {
        if (canonicalId == null) return null;
        int colon = canonicalId.indexOf(':');
        return colon >= 0 ? canonicalId.substring(colon + 1) : canonicalId;
    }

    public static boolean isAir(String id) {
        return AIR.equals(id) || CAVE_AIR.equals(id);
    }

    public static boolean isCaveAir(String id) {
        return CAVE_AIR.equals(id);
    }

    public static boolean isWater(String id) {
        return WATER.equals(id);
    }

    public static boolean isLava(String id) {
        return LAVA.equals(id);
    }
}
