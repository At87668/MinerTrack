package link.star_dust.MinerTrack.common;

import java.util.Objects;

/**
 * Platform-agnostic block coordinate. Used as the key in
 * {@code Map<CommonLocation, Long>} tracking structures (placed blocks,
 * broken-air map, etc.) and in {@code Set<CommonLocation>} vein clusters
 * built by {@code DetectionEngine.getVeinLocations}.
 *
 * <p>Without explicit {@link #equals(Object)} and {@link #hashCode()},
 * hash-based lookups would fall back to reference identity, which means
 * two {@code CommonLocation} instances built from the same coordinates
 * (one captured at break time via {@code trackBrokenAir(loc)}, the other
 * reconstructed inside the 7x7x7 {@code isInNaturalEnvironment} scan
 * via {@code new CommonLocation(world, x, y, z)}) would never match.
 * Concretely, that broke two features in v2:
 * <ul>
 *   <li>{@code xray.natural-detection.cave.ignore-artificial-air} —
 *       every entry of the {@code brokenAir} map was effectively
 *       unread, so a player who manually carved a tunnel through stone
 *       had those freshly-broken air blocks counted as part of the
 *       surrounding cave air, biasing {@code airCount} upward and
 *       pushing more dig events into the "natural environment" branch
 *       (over-pardoning X-Ray diggers who tunnel straight to ore).</li>
 *   <li>{@code Set<CommonLocation>} lookups inside
 *       {@code MiningState.isNewVein} (cluster-overlap detection)
 *       could never match by coordinates; the vein counter only
 *       stayed accurate because the subsequent min-distance check
 *       was a pure geometric comparison.</li>
 * </ul>
 * Implementing value equality + a stable hash code fixes both, and is
 * a no-op for any caller that was already passing the same instance
 * through.
 */
public class CommonLocation {
    public final String world;
    public final int x;
    public final int y;
    public final int z;

    public CommonLocation(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommonLocation)) return false;
        CommonLocation other = (CommonLocation) o;
        return x == other.x
            && y == other.y
            && z == other.z
            && Objects.equals(world, other.world);
    }

    @Override
    public int hashCode() {
        // World is usually a small enum-like string ("minecraft:overworld");
        // mixing it with the int coordinates is enough to give a stable,
        // well-distributed hash without boxing every coordinate.
        return Objects.hashCode(world) * 31 * 31 * 31
            + x * 31 * 31
            + y * 31
            + z;
    }

    @Override
    public String toString() {
        return "CommonLocation{" + world + "," + x + "," + y + "," + z + "}";
    }
}
