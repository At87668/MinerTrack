/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 */

package link.star_dust.MinerTrack.common;

import java.util.Objects;
import java.util.UUID;

/**
 * The player who caused an explosion, when the platform can establish that
 * attribution from the event itself.
 */
public record ExplosionAttribution(UUID playerId, String playerName) {
    public ExplosionAttribution {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
    }
}
