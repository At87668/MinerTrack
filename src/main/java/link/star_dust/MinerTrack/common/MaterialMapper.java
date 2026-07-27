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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bidirectional mapping between Bukkit's {@code Material} enum names
 * (e.g. {@code DIAMOND_ORE}) and the canonical Minecraft namespace id
 * ({@code minecraft:diamond_ore}).
 *
 * Only the entries the plugin needs at runtime are pre-populated.
 * Unknown Bukkit names are converted on the fly using
 * {@link #bukkitToMinecraft(String)} (lowercase + namespace), and unknown
 * Minecraft ids are converted to Bukkit enum names using
 * {@link #minecraftToBukkit(String)} (uppercase + enum-style). The on-the-fly
 * conversions are intentionally lossless; Bukkit's
 * {@code Material#matchMaterial(String)} accepts both forms (the
 * {@code Material} enum exposes a method that maps a {@code NamespacedKey}
 * back to an enum constant).
 */
public final class MaterialMapper {

    private static final Map<String, String> BUKKIT_TO_MINECRAFT = new HashMap<>();
    private static final Map<String, String> MINECRAFT_TO_BUKKIT = new HashMap<>();

    static {
        // Pre-populated table — keeps detection logic and configs in sync
        // without depending on the Bukkit classloader at compile time.
        register("AIR",                  "air");
        register("CAVE_AIR",             "cave_air");
        register("WATER",                "water");
        register("LAVA",                 "lava");
        register("DIAMOND_ORE",          "diamond_ore");
        register("DEEPSLATE_DIAMOND_ORE","deepslate_diamond_ore");
        register("EMERALD_ORE",          "emerald_ore");
        register("DEEPSLATE_EMERALD_ORE","deepslate_emerald_ore");
        register("ANCIENT_DEBRIS",       "ancient_debris");
    }

    private MaterialMapper() {}

    private static void register(String bukkit, String path) {
        BUKKIT_TO_MINECRAFT.put(bukkit, BlockId.namespace(path));
        MINECRAFT_TO_BUKKIT.put(BlockId.namespace(path), bukkit);
    }

    /**
     * Convert a Bukkit {@code Material} enum name (e.g. {@code DIAMOND_ORE})
     * to the canonical Minecraft id. Returns the normalised id even when
     * the enum is unknown, so config / runtime lookups stay consistent.
     */
    public static String bukkitToMinecraft(String bukkitMaterial) {
        if (bukkitMaterial == null) return null;
        String upper = bukkitMaterial.toUpperCase(Locale.ROOT);
        String mapped = BUKKIT_TO_MINECRAFT.get(upper);
        if (mapped != null) return mapped;
        // Fallback: trust that the input is already snake_case-ish and just
        // wrap it in the namespace.
        return BlockId.normalize(upper);
    }

    /**
     * Convert a canonical Minecraft id to the Bukkit {@code Material} enum
     * name (e.g. {@code minecraft:diamond_ore} → {@code DIAMOND_ORE}).
     */
    public static String minecraftToBukkit(String minecraftId) {
        if (minecraftId == null) return null;
        String mapped = MINECRAFT_TO_BUKKIT.get(minecraftId);
        if (mapped != null) return mapped;
        return BlockId.pathOf(minecraftId).toUpperCase(Locale.ROOT);
    }
}
