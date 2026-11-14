/*
 * This file is part of MinerTrack, licensed under the GNU General Public License v3.0.
 */

package link.star_dust.MinerTrack.common;

/**
 * Platform-neutral source of an explosion observed by a mining listener.
 */
public enum ExplosionSource {
    TNT("tnt"),
    CREEPER("creeper"),
    GHAST("ghast"),
    OTHER("other");

    private final String marker;

    ExplosionSource(String marker) {
        this.marker = marker;
    }

    /**
     * Stable source marker used in violation log block labels.
     */
    public String marker() {
        return marker;
    }

    /**
     * Only TNT attributed to a player can enter the scoring pipeline.
     */
    public boolean canScore() {
        return this == TNT;
    }
}
