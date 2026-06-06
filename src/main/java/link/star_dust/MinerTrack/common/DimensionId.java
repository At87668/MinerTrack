package link.star_dust.MinerTrack.common;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonical Minecraft dimension identifiers.
 *
 * <p>Every platform maps a "world" to a {@code minecraft:<key>} string:
 * <ul>
 *   <li><b>Bukkit / Spigot / Paper</b>: {@code org.bukkit.World#getName()}
 *       returns the world <i>folder name</i> (e.g. {@code world},
 *       {@code world_nether}). Each world has a
 *       {@code org.bukkit.World.Environment} ({@code NORMAL},
 *       {@code NETHER}, {@code THE_END}) that uniquely identifies which
 *       Minecraft dimension the world belongs to.</li>
 *   <li><b>Fabric</b>: worlds are referenced by their dimension id
 *       directly (e.g. {@code minecraft:overworld}).</li>
 * </ul>
 *
 * <p>This class is the platform-neutral canonical form used everywhere in
 * the {@code core/} package. The platform-specific {@code bukkit} bridge
 * resolves folder names to dimension ids (see
 * {@link link.star_dust.MinerTrack.bukkit.BukkitDetectionBridge}), so the
 * detection code never has to care which world <i>folder</i> a server
 * happens to be using.
 *
 * <p>The string constants follow the same rules as {@link BlockId}:
 * canonical form is {@code minecraft:<lower_snake_case>}.
 */
public final class DimensionId {

    public static final String OVERWORLD  = BlockId.namespace("overworld");
    public static final String THE_NETHER = BlockId.namespace("the_nether");
    public static final String THE_END    = BlockId.namespace("the_end");

    private DimensionId() {}

    /**
     * Return a normalised dimension id. Accepts:
     * <ul>
     *   <li>canonical {@code minecraft:xxx} ids (returned as-is, lowercase)</li>
     *   <li>Bukkit folder names (e.g. {@code world}, {@code world_nether},
     *       {@code world_the_end}) — these are mapped to their canonical
     *       dimension id via {@link #fromBukkitFolder(String)}</li>
     *   <li>Any user-supplied alias — normalised by lowercasing + namespacing</li>
     * </ul>
     * Returns {@code null} for null/blank input.
     */
    public static String normalize(String id) {
        if (id == null) return null;
        String trimmed = id.trim();
        if (trimmed.isEmpty()) return null;

        // Well-known Bukkit folder names → canonical dimension ids.
        String mapped = fromBukkitFolder(trimmed);
        if (mapped != null) return mapped;

        // Well-known legacy aliases (the strings some legacy configs use).
        if (trimmed.equalsIgnoreCase("overworld") || trimmed.equalsIgnoreCase("normal")) return OVERWORLD;
        if (trimmed.equalsIgnoreCase("nether") || trimmed.equalsIgnoreCase("the_nether")) return THE_NETHER;
        if (trimmed.equalsIgnoreCase("the_end") || trimmed.equalsIgnoreCase("end")) return THE_END;

        // Generic normalisation: lowercase + namespace prefix.
        return BlockId.normalize(trimmed);
    }

    /**
     * Map common Bukkit world folder names to their canonical dimension id.
     * Returns {@code null} if the input is not recognised; callers should
     * fall back to {@link BlockId#normalize(String)} in that case.
     */
    public static String fromBukkitFolder(String folderName) {
        if (folderName == null) return null;
        String f = folderName.toLowerCase(Locale.ROOT);
        switch (f) {
            case "world":
            case "overworld":
                return OVERWORLD;
            case "world_nether":
            case "nether":
            case "the_nether":
                return THE_NETHER;
            case "world_the_end":
            case "the_end":
            case "dim_end":
            case "end":
                return THE_END;
            default:
                return null;
        }
    }
}
