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
    private GravityFrame frame;

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
    }

    public synchronized void removePart(int id) {
        index.remove(id);
    }

    public synchronized void clear() {
        index.clear();
    }

    /** Stores a copy, so later changes to the caller's frame need another setFrame. */
    public synchronized void setFrame(GravityFrame f) {
        frame = f == null ? null : f.copy();
    }

    public synchronized boolean hasFrame() {
        return frame != null;
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
        List<Tri> mc = new ArrayList<>();
        for (Tri t : index.query(min, max)) mc.add(Tri.of(frame.toMc(t.a()), frame.toMc(t.b()), frame.toMc(t.c())));
        return Voxelizer.voxelize(mc, q);
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
