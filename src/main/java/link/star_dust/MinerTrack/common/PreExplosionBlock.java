/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 */

package link.star_dust.MinerTrack.common;

import java.util.Objects;

/**
 * A block captured before an explosion changes the world.
 */
public record PreExplosionBlock(CommonLocation location, String blockType, boolean playerPlaced) {
    public PreExplosionBlock {
        Objects.requireNonNull(location, "location");
        blockType = Objects.requireNonNull(BlockId.normalize(blockType), "blockType");
    }

    public PreExplosionBlock(CommonLocation location, String blockType) {
        this(location, blockType, false);
    }
}
