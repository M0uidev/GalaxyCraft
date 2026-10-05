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
 */
public final class PlanetCollision {
    /** Cells of a planet are a little under a block in places: look this many more around. */
    private static final double MIN_CELL = 0.6;
    private static final int MAX_REACH = 8;

    private PlanetCollision() {}

    /**
     * Adds the boxes of p's blocks overlapping query (Minecraft space, {minX, minY, minZ, maxX,
     * maxY, maxZ}); galOf turns planet space (blocks) into the galaxy's.
     */
    public static void boxes(VoxelPlanet p, Function<Vector3d, Vector3d> galOf, Function<Vector3d, Vector3d> localOf,
            GravityFrame frame, double[] query, List<double[]> out) {
        CubeSphere g = p.grid;
        Vector3d mid = new Vector3d((query[0] + query[3]) / 2, (query[1] + query[4]) / 2, (query[2] + query[5]) / 2);
        int ref = g.cellAt(localOf.apply(frame.toGal(mid)));
        if (ref < 0) return;
        double half = Math.max(query[3] - query[0], Math.max(query[4] - query[1], query[5] - query[2])) / 2;
        int reach = Math.min(MAX_REACH, (int) Math.ceil(half / MIN_CELL) + 1);
        int face = g.face(ref), i0 = g.i(ref), j0 = g.j(ref), k0 = g.k(ref);
        for (int di = -reach; di <= reach; di++)
            for (int dj = -reach; dj <= reach; dj++)
                for (int dk = -reach; dk <= reach; dk++) {
                    int c = g.cellBeyond(face, i0 + di, j0 + dj, k0 + dk);
                    if (c < 0) continue;
                    BlockInfo b = p.info(c);
                    if (!b.collides()) continue;
                    int across = g.cellBeyond(face, i0 + di, j0 + dj, k0);
                    if (across < 0) across = c;
                    if (b.fullCollision()) add(g, c, across, new double[] {0, 0, 0, 1, 1, 1}, galOf, frame, query, out);
                    else for (double[] box : b.boxes()) add(g, c, across, box, galOf, frame, query, out);
                }
    }

    /** c's box: across from cell across (c's column at the player's layer), up from c itself. */
    private static void add(CubeSphere g, int c, int across, double[] box, Function<Vector3d, Vector3d> galOf, GravityFrame frame,
            double[] q, List<double[]> out) {
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
        for (double y : new double[] {box[1], box[4]}) {
            Vector3d mc = frame.toMc(galOf.apply(CellSpace.point(g, c, mx, y, mz)));
            b[1] = Math.min(b[1], mc.y);
            b[4] = Math.max(b[4], mc.y);
        }
        if (b[0] < q[3] && b[3] > q[0] && b[1] < q[4] && b[4] > q[1] && b[2] < q[5] && b[5] > q[2]) out.add(b);
    }
}
