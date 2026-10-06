package dev.moui.galaxycraft.gravity;

import org.joml.Vector3d;

/**
 * Where the player is in the galaxy between ticks: part of the way from the last tick's place to
 * this one's, both taken at the end of their tick. In galaxy units, so whatever the frame did in
 * between (turned to a new up, lined up with a planet's blocks, rebased) does not show as a jump.
 */
public final class GalPath {
    /** A move this long in one tick is a teleport: drawn there at once. */
    static final double JUMP_BLOCKS = 16;
    private Vector3d prev, cur;

    /** End of a tick: where the player is now (galaxy units). */
    public void tick(Vector3d gal) {
        prev = cur == null || cur.distance(gal) * GravityFrame.SCALE > JUMP_BLOCKS ? new Vector3d(gal) : cur;
        cur = new Vector3d(gal);
    }

    /** partial of the way from the last tick's place to this tick's; null before any tick. */
    public Vector3d at(double partial) {
        return cur == null ? null : new Vector3d(prev).lerp(cur, partial);
    }

    public void reset() {
        prev = cur = null;
    }
}
