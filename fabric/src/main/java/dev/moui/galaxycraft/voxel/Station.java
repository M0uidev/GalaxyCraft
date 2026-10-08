package dev.moui.galaxycraft.voxel;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A flat platform in space: its cells (a planet on a {@link FlatGrid}), where it is and how it is
 * turned, its name, and the stage it is placed in (null while it is packed in an item). It grows
 * as blocks go on it: its bounds follow, and once a block nears the grid's side the grid is made
 * anew around them ({@link #regrow()}), every cell keeping its station coordinate.
 */
public final class Station {
    public final String id;
    public String name;
    /** The stage it is placed in; null while packed. */
    public String stage;
    /** Its core's center: universe units in GalaxyCraftSpace, galaxy units elsewhere (as PlanetSession's center). */
    public Vector3d center = new Vector3d();
    public Quaterniond rotation;
    public VoxelPlanet planet;
    public StationShape.Bounds bounds;

    public Station(String id, String name, Quaterniond rotation, VoxelPlanet planet, StationShape.Bounds bounds) {
        this.id = id;
        this.name = name;
        this.rotation = new Quaterniond(rotation);
        this.planet = planet;
        this.bounds = bounds;
    }

    /** A new station: the starter slab of slab blocks, the core block in its middle at (0, 0, 0). */
    public static Station create(String id, String name, Quaterniond rotation, Blocks blocks, char slab, char core) {
        StationShape.Bounds b = StationShape.starter();
        FlatGrid g = grid(StationShape.sizeFor(b), rotation);
        char[] cells = new char[g.cellCount()];
        for (int x = b.x0(); x <= b.x1(); x++)
            for (int z = b.z0(); z <= b.z1(); z++) cells[g.cellOf(x, 0, z)] = slab;
        cells[g.cellOf(0, 0, 0)] = core;
        return new Station(id, name, rotation, VoxelPlanet.flat(g, cells, blocks), b);
    }

    /** No station goes nearer than this to another body's gravity, blocks. */
    public static final double CLEAR = 16;
    /** Stations in the game at once (the module's box gravities). */
    public static final int MAX_ACTIVE = 8;

    /** Why no station may go up at p (blocks), or null if one may: open space only, and a box gravity free. */
    public static String refusal(Vector3d p, java.util.List<? extends dev.moui.galaxycraft.gravity.GravityBody> bodies, int active, int max) {
        for (var b : bodies) if (b.outside(p) <= CLEAR) return "open_space";
        return active >= max ? "too_many" : null;
    }

    /** A random id: 8 hex digits. */
    public static String newId(java.util.Random random) {
        return String.format("%08x", random.nextInt());
    }

    static FlatGrid grid(StationShape.Size s, Quaterniond rotation) {
        return new FlatGrid(s.n(), s.layers(), s.ox(), s.oy(), s.oz(), rotation);
    }

    public FlatGrid grid() {
        return (FlatGrid) planet.grid;
    }

    /** Whether a block may go at station coordinate (x, y, z). */
    public boolean allowed(int x, int y, int z) {
        return StationShape.allowed(bounds, x, y, z);
    }

    /** A cell's block changed: a block there grows the bounds. True if the grid should regrow now. */
    public boolean changed(int cell) {
        if (planet.get(cell) == Blocks.AIR) return false;
        FlatGrid g = grid();
        int x = g.stationX(cell), y = g.stationY(cell), z = g.stationZ(cell);
        bounds = bounds.with(x, y, z);
        return StationShape.nearEdge(g, x, y, z);
    }

    /** The cells on a new grid for today's bounds: the same blocks at the same station coordinates. */
    public void regrow() {
        FlatGrid from = grid(), to = grid(StationShape.sizeFor(bounds), rotation);
        char[] old = planet.cells(), cells = new char[to.cellCount()];
        for (int c = 0; c < old.length; c++) {
            if (old[c] == Blocks.AIR) continue;
            int nc = to.cellOf(from.stationX(c), from.stationY(c), from.stationZ(c));
            if (nc >= 0) cells[nc] = old[c];
        }
        planet = VoxelPlanet.flat(to, cells, planet.blocks);
    }

    public int blockCount() {
        int n = 0;
        for (char c : planet.cells()) if (c != Blocks.AIR) n++;
        return n;
    }
}
