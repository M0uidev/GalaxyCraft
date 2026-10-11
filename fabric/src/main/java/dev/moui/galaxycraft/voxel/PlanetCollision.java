package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.gravity.GravityFrame;
import java.util.List;
import java.util.function.Function;
import org.joml.Vector3d;

/**
 * A voxel planet's blocks as Minecraft collides with them, as straight columns the way
 * Minecraft's are: each solid block (or each box of a partial one) spans, across, the middles of
 * the side faces of its column's block at the player's layer, and, up, the middles of its own
 * top and bottom (Minecraft space). A planet's columns narrow toward its core, so a hole's walls
 * lean in going down; boxes of the blocks as they are would step inward block by block, a rim
 * under a player pressed against the wall that holds it over a hole just dug. The middle of a
 * face is its neighbor's too, so boxes meet flush. With the frame lined up with
 * the blocks (GravityFrame.alignGrid) those are the blocks as drawn, flush, nothing rounded out
 * into the open: one-wide holes and two-high tunnels fit the player as in Minecraft.
 *
 * <p>Columns of a planet also turn from one to the next (up to a few hundredths of a radian per
 * block), so a straight wall of blocks, each put through the frame on its own, comes out as steps
 * of millimeters or centimeters in Minecraft's axes, and a player pressed against the wall and
 * walking along it catches on each. Within a face of the cube, then, the sideways extent of every
 * box is laid on the lattice of the block the player stands in (its width, along the axes the frame
 * lines up): a straight wall is flat, as in Minecraft. Across an edge of the cube, the blocks' own.
 */
public final class PlanetCollision {
    /** Cells of a planet are a little under a block in places: look this many more around. */
    private static final double MIN_CELL = 0.6;
    private static final int MAX_REACH = 8;
    /** A box side this near (blocks) to where the player's block's lattice puts it is put there: flat walls, no steps. */
    private static final double FLAT = 0.1;
    private static final double[] FULL = {0, 0, 0, 1, 1, 1};

    private PlanetCollision() {}

    /**
     * Adds the boxes of p's blocks overlapping query (Minecraft space, {minX, minY, minZ, maxX,
     * maxY, maxZ}); galOf turns planet space (blocks) into the galaxy's.
     */
    public static void boxes(VoxelPlanet p, Function<Vector3d, Vector3d> galOf, Function<Vector3d, Vector3d> localOf,
            GravityFrame frame, double[] query, List<double[]> out) {
        CellGrid g = p.grid;
        Vector3d mid = new Vector3d((query[0] + query[3]) / 2, (query[1] + query[4]) / 2, (query[2] + query[5]) / 2);
        int ref = g.cellAt(localOf.apply(frame.toGal(mid)));
        if (ref < 0) return;
        double half = Math.max(query[3] - query[0], Math.max(query[4] - query[1], query[5] - query[2])) / 2;
        int reach = Math.min(MAX_REACH, (int) Math.ceil(half / MIN_CELL) + 1);
        int face = g.face(ref), i0 = g.i(ref), j0 = g.j(ref), k0 = g.k(ref);
        double[] lattice = lattice(g, ref, galOf, frame);
        for (int di = -reach; di <= reach; di++)
            for (int dj = -reach; dj <= reach; dj++)
                for (int dk = -reach; dk <= reach; dk++) {
                    int c = g.cellBeyond(face, i0 + di, j0 + dj, k0 + dk);
                    if (c < 0) continue;
                    BlockInfo b = p.info(c);
                    if (!b.collides()) continue;
                    int across = g.cellBeyond(face, i0 + di, j0 + dj, k0);
                    if (across < 0) across = c;
                    double[] flat = across >= 0 && lattice != null && g.face(across) == face && g.i(across) == i0 + di
                            && g.j(across) == j0 + dj ? lattice : null;
                    int[] off = {di, dj};
                    if (b.fullCollision()) add(g, c, across, FULL, galOf, frame, flat, off, query, out);
                    else for (double[] box : b.boxes()) add(g, c, across, box, galOf, frame, flat, off, query, out);
                }
    }

    /**
     * The lattice of the block the player stands in: {centerX, centerZ, aX, aZ, cX, cZ}, Minecraft
     * space: its middle, and its model-x and model-z edges as the frame lines them up (along one axis each, as
     * long as the block is wide). Null if the block does not line up.
     */
    private static double[] lattice(CellGrid g, int ref, Function<Vector3d, Vector3d> galOf, GravityFrame frame) {
        Vector3d i0 = frame.toMc(galOf.apply(CellSpace.point(g, ref, 0, 0.5, 0.5))), i1 = frame.toMc(galOf.apply(CellSpace.point(g, ref, 1, 0.5, 0.5)));
        Vector3d j0 = frame.toMc(galOf.apply(CellSpace.point(g, ref, 0.5, 0.5, 0))), j1 = frame.toMc(galOf.apply(CellSpace.point(g, ref, 0.5, 0.5, 1)));
        double[] iv = onAxis(i1.x - i0.x, i1.z - i0.z), jv = onAxis(j1.x - j0.x, j1.z - j0.z);
        if (iv == null || jv == null || iv[0] * jv[0] + iv[1] * jv[1] != 0) return null;
        return new double[] {(i0.x + i1.x + j0.x + j1.x) / 4, (i0.z + i1.z + j0.z + j1.z) / 4, iv[0], iv[1], jv[0], jv[1]};
    }

    /** (dx, dz) as a vector along the nearer axis, as long as its length; null if neither axis is near. */
    private static double[] onAxis(double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.1) return null;
        if (Math.abs(dx) >= Math.abs(dz)) return new double[] {Math.signum(dx) * len, 0};
        return new double[] {0, Math.signum(dz) * len};
    }

    /** c's box: across from cell across (c's column at the player's layer), up from c itself. */
    private static void add(CellGrid g, int c, int across, double[] box, Function<Vector3d, Vector3d> galOf, GravityFrame frame,
            double[] lattice, int[] off, double[] q, List<double[]> out) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        double mx = (box[0] + box[3]) / 2, my = (box[1] + box[4]) / 2, mz = (box[2] + box[5]) / 2;
        double[][] side = {{box[0], my, mz}, {box[3], my, mz}, {mx, my, box[2]}, {mx, my, box[5]}};
        for (double[] m : side) {
            Vector3d mc = frame.toMc(galOf.apply(CellSpace.point(g, across, m[0], m[1], m[2])));
            b[0] = Math.min(b[0], mc.x);
            b[2] = Math.min(b[2], mc.z);
            b[3] = Math.max(b[3], mc.x);
            b[5] = Math.max(b[5], mc.z);
        }
        if (lattice != null) {
            // The box's corners across, on the lattice: the cell (off) from the player's block, then the box's own fractions.
            double[] l = {Double.MAX_VALUE, 0, Double.MAX_VALUE, -Double.MAX_VALUE, 0, -Double.MAX_VALUE};
            for (double fi : new double[] {box[0], box[3]})
                for (double fj : new double[] {box[2], box[5]}) {
                    double ui = off[1] + fi - 0.5, uj = off[0] + fj - 0.5; // a cell's model x runs along its j, z along its i
                    double x = lattice[0] + ui * lattice[2] + uj * lattice[4], z = lattice[1] + ui * lattice[3] + uj * lattice[5];
                    l[0] = Math.min(l[0], x);
                    l[2] = Math.min(l[2], z);
                    l[3] = Math.max(l[3], x);
                    l[5] = Math.max(l[5], z);
                }
            // Where the block is drawn within FLAT of the lattice, the lattice's (a flat wall); farther off, the block's own.
            for (int e : new int[] {0, 2, 3, 5})
                if (Math.abs(l[e] - b[e]) < FLAT) b[e] = l[e];
        }
        for (double y : new double[] {box[1], box[4]}) {
            Vector3d mc = frame.toMc(galOf.apply(CellSpace.point(g, c, mx, y, mz)));
            b[1] = Math.min(b[1], mc.y);
            b[4] = Math.max(b[4], mc.y);
        }
        if (b[0] < q[3] && b[3] > q[0] && b[1] < q[4] && b[4] > q[1] && b[2] < q[5] && b[5] > q[2]) out.add(b);
    }
}
