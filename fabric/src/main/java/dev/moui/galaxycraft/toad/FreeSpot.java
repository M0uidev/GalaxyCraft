package dev.moui.galaxycraft.toad;

/**
 * Where a two-block-high walker can stand: the pure search the Toads use to come out of blocks
 * (on spawn, and each second if one finds itself inside one). Cells are integer block coordinates
 * of whatever space the caller has (the shadow dimension's); the caller says which are solid.
 */
public final class FreeSpot {
    /** The space being searched. */
    public interface Space {
        boolean solid(int x, int y, int z);
    }

    private FreeSpot() {}

    /** Whether a walker whose feet are in this cell has its feet or head inside a block. */
    public static boolean inside(Space s, int x, int y, int z) {
        return s.solid(x, y, z) || s.solid(x, y + 1, z);
    }

    /** The closest cell to (x, y, z) within radius where a walker can stand, or null. */
    public static int[] nearest(Space s, int x, int y, int z, int radius) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++) {
                    int cx = x + dx, cy = y + dy, cz = z + dz;
                    if (s.solid(cx, cy, cz) || s.solid(cx, cy + 1, cz) || !s.solid(cx, cy - 1, cz)) continue;
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d < bestD || d == bestD && cy < best[1]) {
                        bestD = d;
                        best = new int[] {cx, cy, cz};
                    }
                }
        return best;
    }
}
