package dev.moui.galaxycraft.voxel;

/**
 * How big a station is and may get, in station coordinates (x, y, z; the core at 0, 0, 0): its
 * bounds (the blocks it has), the limits (256 across, 48 below the core to 79 above: the shadow
 * dimension is 128 high), and the grid that holds it (the bounds and some slack, so a regrow is
 * needed only every so often).
 */
public final class StationShape {
    public static final int MAX_SPAN = 256, MIN_Y = -48, MAX_Y = 79;
    /** Empty room the grid keeps around the bounds, blocks. */
    public static final int SLACK = 16;
    /** A block this close to the grid's side (where it can still grow) asks for a regrow. */
    public static final int EDGE = 8;
    /** Half the starter slab: -START..START on x and z. */
    public static final int START = 4;

    private StationShape() {}

    /** The smallest box holding every block, inclusive. */
    public record Bounds(int x0, int y0, int z0, int x1, int y1, int z1) {
        public Bounds with(int x, int y, int z) {
            return new Bounds(Math.min(x0, x), Math.min(y0, y), Math.min(z0, z), Math.max(x1, x), Math.max(y1, y), Math.max(z1, z));
        }

        public boolean contains(int x, int y, int z) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
        }

        public int spanX() {
            return x1 - x0 + 1;
        }

        public int spanY() {
            return y1 - y0 + 1;
        }

        public int spanZ() {
            return z1 - z0 + 1;
        }
    }

    /** A grid's size: n × n columns of `layers`, cell (0, 0, 0) at station coordinate (ox, oy, oz). */
    public record Size(int n, int layers, int ox, int oy, int oz) {}

    public static Bounds starter() {
        return new Bounds(-START, 0, -START, START, 0, START);
    }

    /** Whether a block may go at (x, y, z): within the height limits, and the station no wider than MAX_SPAN. */
    public static boolean allowed(Bounds b, int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) return false;
        Bounds w = b.with(x, y, z);
        return w.spanX() <= MAX_SPAN && w.spanZ() <= MAX_SPAN;
    }

    /** The grid for these bounds: SLACK around them (within the height limits), a square footprint of whole chunks. */
    public static Size sizeFor(Bounds b) {
        int xlo = b.x0 - SLACK, xhi = b.x1 + SLACK, zlo = b.z0 - SLACK, zhi = b.z1 + SLACK;
        int span = Math.max(xhi - xlo + 1, zhi - zlo + 1);
        int n = (span + VoxelPlanet.CHUNK - 1) / VoxelPlanet.CHUNK * VoxelPlanet.CHUNK;
        int ox = Math.floorDiv(xlo + xhi - n + 1, 2), oz = Math.floorDiv(zlo + zhi - n + 1, 2);
        int oy = Math.max(MIN_Y, b.y0 - SLACK), top = Math.min(MAX_Y, b.y1 + SLACK);
        return new Size(n, top - oy + 1, ox, oy, oz);
    }

    /** Whether a block at (x, y, z) is near enough the grid's side (or past it) that the grid should regrow. */
    public static boolean nearEdge(FlatGrid g, int x, int y, int z) {
        int c = g.cellOf(x, y, z);
        if (c < 0) return true;
        int i = g.i(c), j = g.j(c), k = g.k(c);
        if (i < EDGE || j < EDGE || i >= g.n - EDGE || j >= g.n - EDGE) return true;
        if (k >= g.layers - EDGE && g.oy + g.layers - 1 < MAX_Y) return true;
        return k < EDGE && g.oy > MIN_Y;
    }
}
