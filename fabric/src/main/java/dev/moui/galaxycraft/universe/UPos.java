package dev.moui.galaxycraft.universe;

import org.joml.Vector3d;

/**
 * A point anywhere in an endless universe, galaxy units: a cell of CELL units (a long per axis)
 * and the offset inside it (a double in [0, CELL)). A double alone stops telling units apart past
 * 2^53 of them; a long cell never runs out, so the universe has no edge and no far lands. No
 * Minecraft types, so it is unit tested.
 */
public record UPos(long cx, long cy, long cz, double x, double y, double z) {
    /**
     * 2^16 galaxy units (819.2 blocks). A float moved by a whole number of cells keeps every bit
     * it needs (see Origin), so moving the game's origin by cells never makes anything jump.
     */
    public static final double CELL = 65536;
    public static final UPos ZERO = new UPos(0, 0, 0, 0, 0, 0);

    /** The same point with each offset in [0, CELL). */
    public static UPos of(long cx, long cy, long cz, double x, double y, double z) {
        long kx = (long) Math.floor(x / CELL), ky = (long) Math.floor(y / CELL), kz = (long) Math.floor(z / CELL);
        return new UPos(cx + kx, cy + ky, cz + kz, x - kx * CELL, y - ky * CELL, z - kz * CELL);
    }

    /** A point given in units from the universe's (0, 0, 0): what a galaxy of before was in. */
    public static UPos of(Vector3d units) {
        return of(0, 0, 0, units.x, units.y, units.z);
    }

    public UPos plus(Vector3d d) {
        return of(cx, cy, cz, x + d.x, y + d.y, z + d.z);
    }

    /**
     * This point minus o, units. Cells subtract as longs first, so two points near each other are
     * exact however far both are from the universe's (0, 0, 0).
     */
    public Vector3d minus(UPos o) {
        return new Vector3d((cx - o.cx) * CELL + (x - o.x), (cy - o.cy) * CELL + (y - o.y), (cz - o.cz) * CELL + (z - o.z));
    }
}
