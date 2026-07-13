package link.star_dust.MinerTrack.common;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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

    /**
     * Holder returned by {@link #parseGroupWorld(String, java.util.Set)}:
     * a {@code <group_name>:<world_folder_name>} pair. {@code group} is the
     * `Configuration/<group>.yml` file stem to look up; {@code world} is the
     * Bukkit world folder name (i.e. what {@code org.bukkit.World#getName()}
     * returns at runtime).
     */
    public static final class GroupWorld {
        public final String group;
        public final String world;
        public GroupWorld(String group, String world) {
            this.group = group;
            this.world = world;
        }
    }

    /**
     * Try to parse {@code raw} as the short form {@code <group>:<world>}
     * where {@code <group>} names a Configuration/<group>.yml group file
     * already present in {@code knownGroups}, and {@code <world>} is the
     * Bukkit world folder name.
     *
     * <p>Examples the loader wants to recognise:
     * <ul>
     *   <li>{@code twilightforest:twilight_forest} → group=twilightforest,
     *       world=twilight_forest (modded dimension under the
     *       TwilightForest group's settings)</li>
     *   <li>{@code aoa3:precasia} → group=aoa3, world=precasia</li>
     *   <li>{@code aoa3:lunalus} → group=aoa3, world=lunalus</li>
     * </ul>
     *
     * <p>Returns {@code null} when {@code raw} is not of the form, when
     * the left-hand side is the {@code minecraft} namespace (those
     * entries are vanilla canonical ids, not group references), or when
     * the left-hand side is not a known group. Callers should fall back
     * to {@link #normalize(String)} for the unsupported shapes.
     *
     * <p>Note: this deliberately does not inspect the {@code minecraft:}
     * namespace or the YAML structure of {@code xray.worlds}; it is
     * called once per list entry, and {@code knownGroups} is the
     * authoritative list of groups that have a corresponding
     * {@code Configuration/<name>.yml} file on disk.
     */
    public static GroupWorld parseGroupWorld(String raw, Set<String> knownGroups) {
        if (raw == null || knownGroups == null || knownGroups.isEmpty()) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        // `minecraft:<world>` is the vanilla canonical form, never a
        // group reference. Reject it explicitly so we don't accidentally
        // treat a vanilla world as belonging to a non-existent
        // `minecraft` group.
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("minecraft:")) return null;
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon >= trimmed.length() - 1) return null;
        // No second colon: an id with two colons (e.g. `minecraft:foo`)
        // is a vanilla-style namespace and was already filtered above;
        // anything beyond two colons is not a valid `<group>:<world>`
        // form either, so reject it.
        if (trimmed.indexOf(':', colon + 1) >= 0) return null;
        String group = trimmed.substring(0, colon).trim();
        String world = trimmed.substring(colon + 1).trim();
        if (group.isEmpty() || world.isEmpty()) return null;
        if (!knownGroups.contains(group)) return null;
        return new GroupWorld(group, world);
    }
}
