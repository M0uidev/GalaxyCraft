package dev.moui.galaxycraft.universe;

import java.util.ArrayDeque;
import java.util.Deque;
import org.joml.Vector3d;

/**
 * The floating origin: the universe cell the game counts from. SMG2 (and the GPU Dolphin
 * emulates) work in floats, which shake past a few thousand blocks from (0, 0, 0): at 100,000
 * blocks a float only tells half a unit apart, at a million 8 units (a tenth of a block). So
 * everything sent to the game is relative to this origin, which moves now and then (OriginPolicy)
 * and keeps the game's numbers small wherever the player is in the universe.
 *
 * It moves by whole cells (2^16 units): a float x moved by a whole number of cells toward 0 stays
 * exactly the same point (x is a multiple of its own last bit, which divides 2^16 below 2^40, and
 * the result needs no finer bit than x had), so Mario and the camera never jump at a move.
 *
 * The game applies a move a few frames after the mod decides it (the inbox is a frame behind):
 * each move has an epoch, the module echoes the epoch its positions are in, and local() converts
 * a position of an older epoch to this one.
 */
public final class Origin {
    /** A move of the origin: to epoch, by (dx, dy, dz) cells. Positions in the game go the other way. */
    public record Shift(int epoch, long dx, long dy, long dz) {
        /** What the move adds to the origin, units (to subtract from every position in the game). */
        public Vector3d units() {
            return new Vector3d(dx * UPos.CELL, dy * UPos.CELL, dz * UPos.CELL);
        }
    }

    /** Moves remembered to convert positions of older epochs (a move every few seconds at most). */
    static final int HISTORY = 16;

    private long cx, cy, cz;
    private int epoch;
    private final Deque<Shift> history = new ArrayDeque<>();

    public Origin() {}

    public Origin(long cx, long cy, long cz) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
    }

    public int epoch() {
        return epoch;
    }

    public UPos cell() {
        return new UPos(cx, cy, cz, 0, 0, 0);
    }

    /** Where p is in the game now, units. */
    public Vector3d local(UPos p) {
        return p.minus(cell());
    }

    /** Where a game position (units, this epoch) is in the universe. */
    public UPos universe(Vector3d local) {
        return UPos.of(cx, cy, cz, local.x, local.y, local.z);
    }

    /**
     * A position the game reported in epoch from, as it is in this epoch; null if from is older
     * than the moves remembered (the caller waits for a newer report).
     */
    public Vector3d local(Vector3d at, int from) {
        Vector3d v = new Vector3d(at);
        if (from == epoch) return v;
        int seen = 0;
        for (Shift s : history) {
            if (s.epoch() <= from) break;
            v.sub(s.units());
            seen = s.epoch();
        }
        return seen == from + 1 ? v : null;
    }

    /**
     * Moves the origin to the cell corner nearest to at, so at ends within half a cell (410 blocks)
     * of (0, 0, 0) on each axis. Null if it is there already.
     */
    public Shift moveTo(UPos at) {
        long nx = at.cx() + Math.round(at.x() / UPos.CELL), ny = at.cy() + Math.round(at.y() / UPos.CELL),
                nz = at.cz() + Math.round(at.z() / UPos.CELL);
        if (nx == cx && ny == cy && nz == cz) return null;
        Shift s = new Shift(++epoch, nx - cx, ny - cy, nz - cz);
        cx = nx;
        cy = ny;
        cz = nz;
        history.addFirst(s);
        if (history.size() > HISTORY) history.removeLast();
        return s;
    }
}
