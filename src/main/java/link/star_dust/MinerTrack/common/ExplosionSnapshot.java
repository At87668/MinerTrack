/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 */

package link.star_dust.MinerTrack.common;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable platform-neutral snapshot of an explosion and the blocks it
 * destroyed before those blocks become air in the live world.
 */
public record ExplosionSnapshot(
        CommonLocation origin,
        ExplosionSource source,
        ExplosionAttribution attribution,
        List<PreExplosionBlock> blocks) {

    public ExplosionSnapshot {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(source, "source");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    }

    /**
     * Convenience constructor for platforms that expose player identity as
     * separate event fields. A null player id intentionally means that the
     * explosion is unattributed and cannot be scored.
     */
    public ExplosionSnapshot(CommonLocation origin, ExplosionSource source,
                             UUID playerId, String playerName,
                             List<PreExplosionBlock> blocks) {
        this(origin, source,
                playerId == null ? null : new ExplosionAttribution(playerId, playerName),
                blocks);
    }

    public boolean isAttributedToPlayer() {
        return attribution != null;
    }

    public String sourceMarker() {
        return "explosion:" + source.marker();
    }
}
