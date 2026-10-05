package dev.moui.galaxycraft.collision;

import dev.moui.galaxycraft.geom.TriangleIndex;
import dev.moui.galaxycraft.geom.Tri;
import dev.moui.galaxycraft.geom.Voxelizer;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.kcl.KclParser;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4x3d;
import org.joml.Vector3d;

/**
 * Galaxy collision as Minecraft sees it: KCL parts placed by their matrices, queried through
 * the current {@link GravityFrame} and voxelized on demand. Thread-safe: the client tick updates
 * it while client and integrated-server threads query it.
 */
public final class CollisionField {
    private static final double MAX_QUERY = 8.0; // blocks per side

    private final TriangleIndex index = new TriangleIndex(200);
    /** The parts that are a voxel planet's blocks (also in index): collided as exact block faces. */
    private final TriangleIndex blocks = new TriangleIndex(200);
    private java.util.function.Predicate<double[]> isBlocks = m -> false;
    private GravityFrame frame;

    /** Boxes of blocks overlapping a query (Minecraft space), through the frame: a voxel planet's. */
    public interface BlockSource {
        void boxes(GravityFrame frame, double[] query, List<double[]> out);
    }

    private BlockSource blockSource = (f, q, out) -> {};

    /** Where a voxel planet's blocks collide from (its parts' triangles are then left out). */
    public synchronized void setBlockSource(BlockSource source) {
        blockSource = source;
    }

    /** Tells a voxel planet's parts by their matrix (3x4 row-major, as upsertPart takes it). */
    public synchronized void setBlockParts(java.util.function.Predicate<double[]> test) {
        isBlocks = test;
    }

    /** mtx is 3x4 row-major, part local -> galaxy. A bad KCL clears the part and is reported. */
    public synchronized void upsertPart(int id, double[] mtx, byte[] kcl) {
        Matrix4x3d m = new Matrix4x3d(
                mtx[0], mtx[4], mtx[8],
                mtx[1], mtx[5], mtx[9],
                mtx[2], mtx[6], mtx[10],
                mtx[3], mtx[7], mtx[11]);
        List<Tri> tris = new ArrayList<>();
        for (Tri t : KclParser.parse(kcl)) tris.add(t.transform(m));
        index.put(id, tris);
        if (isBlocks.test(mtx)) blocks.put(id, tris);
        else blocks.remove(id);
    }

    public synchronized void removePart(int id) {
        index.remove(id);
        blocks.remove(id);
    }

    public synchronized void clear() {
        index.clear();
        blocks.clear();
    }

    /** Stores a copy, so later changes to the caller's frame need another setFrame. */
    public synchronized void setFrame(GravityFrame f) {
        frame = f == null ? null : f.copy();
    }

    public synchronized boolean hasFrame() {
        return frame != null;
    }

    /**
     * Whether loaded collision lies under the player's feet (Minecraft space), within depth
     * blocks: after linking, the parts around the player can take a few ticks to arrive.
     */
    public boolean hasGroundBelow(double[] feet, double depth) {
        return !boxesFor(new double[] {feet[0] - 0.3, feet[1] - depth, feet[2] - 0.3,
                feet[0] + 0.3, feet[1] + 0.1, feet[2] + 0.3}).isEmpty();
    }

    /** query = {minX, minY, minZ, maxX, maxY, maxZ} in Minecraft space. */
    public synchronized List<double[]> boxesFor(double[] query) {
        if (frame == null) return List.of();
        double[] q = clip(query);
        Vector3d min = new Vector3d(Double.MAX_VALUE), max = new Vector3d(-Double.MAX_VALUE);
        for (int i = 0; i < 8; i++) {
            Vector3d c = frame.toGal(new Vector3d((i & 1) == 0 ? q[0] : q[3], (i & 2) == 0 ? q[1] : q[4], (i & 4) == 0 ? q[2] : q[5]));
            min.min(c);
            max.max(c);
        }
        java.util.Set<Tri> block = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        block.addAll(blocks.query(min, max));
        List<Tri> mc = new ArrayList<>();
        for (Tri t : index.query(min, max))
            if (!block.contains(t)) mc.add(Tri.of(frame.toMc(t.a()), frame.toMc(t.b()), frame.toMc(t.c())));
        List<double[]> out = new ArrayList<>(Voxelizer.voxelize(mc, q));
        blockSource.boxes(frame, q, out);
        return out;
    }

    /**
     * The least moves that take a player's box (Minecraft space, {minX, minY, minZ, maxX, maxY,
     * maxZ}) out of the galaxy's boxes it overlaps, one box at a time, the smallest first: up onto
     * it (at most maxUp) or sideways out of it (at most maxSide). Zero if it overlaps nothing, or
     * no such moves free it. The boxes turn and snap with the frame every tick, and one can come
     * to overlap the player a little; Minecraft lets a box the player starts in pass, so it would
     * fall through the floor or walk through the wall.
     */
    public Vector3d pushOut(double[] player, double maxUp, double maxSide) {
        double[] p = player.clone();
        Vector3d total = new Vector3d();
        for (int round = 0; round < 8; round++) {
            List<double[]> in = overlapping(p);
            if (in.isEmpty()) return total;
            double[] best = null;
            double bestLen = Double.MAX_VALUE;
            for (double[] b : in) {
                double[][] moves = {{0, b[4] - p[1], 0}, {b[3] - p[0], 0, 0}, {b[0] - p[3], 0, 0},
                        {0, 0, b[5] - p[2]}, {0, 0, b[2] - p[5]}};
                for (double[] m : moves) {
                    double len = Math.abs(m[0]) + Math.abs(m[1]) + Math.abs(m[2]);
                    if (m[1] > maxUp || Math.abs(m[0]) > maxSide || Math.abs(m[2]) > maxSide || len >= bestLen) continue;
                    bestLen = len;
                    best = m;
                }
            }
            if (best == null) return new Vector3d();
            p = moved(p, best);
            total.add(best[0], best[1], best[2]);
        }
        return overlapping(p).isEmpty() ? total : new Vector3d();
    }

    private static final double TOUCH = 1e-7;

    private List<double[]> overlapping(double[] p) {
        List<double[]> out = new ArrayList<>();
        for (double[] b : boxesFor(p))
            if (b[0] < p[3] - TOUCH && b[3] > p[0] + TOUCH && b[1] < p[4] - TOUCH && b[4] > p[1] + TOUCH
                    && b[2] < p[5] - TOUCH && b[5] > p[2] + TOUCH)
                out.add(b);
        return out;
    }

    private static double[] moved(double[] p, double[] m) {
        return new double[] {p[0] + m[0], p[1] + m[1], p[2] + m[2], p[3] + m[0], p[4] + m[1], p[5] + m[2]};
    }

    /**
     * How far (blocks, up to max) a ray from fromMc along the unit dirMc travels before the galaxy's
     * collision: keeps the third-person camera out of planets and walls.
     */
    public synchronized double clipDistance(Vector3d fromMc, Vector3d dirMc, double max) {
        if (frame == null) return max;
        Vector3d o = frame.toGal(fromMc);
        Vector3d d = frame.dirToGal(dirMc).normalize();
        Vector3d end = new Vector3d(d).mul(max / GravityFrame.SCALE).add(o);
        Vector3d min = new Vector3d(o).min(end), maxCorner = new Vector3d(o).max(end);
        double best = max / GravityFrame.SCALE;
        for (Tri t : index.query(min, maxCorner)) best = Math.min(best, t.rayHit(o, d));
        return best * GravityFrame.SCALE;
    }

    private static double[] clip(double[] q) {
        double[] out = q.clone();
        for (int a = 0; a < 3; a++) {
            if (q[a + 3] - q[a] > MAX_QUERY) {
                double c = (q[a] + q[a + 3]) / 2;
                out[a] = c - MAX_QUERY / 2;
                out[a + 3] = c + MAX_QUERY / 2;
            }
        }
        return out;
    }
}
