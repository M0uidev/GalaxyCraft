package dev.moui.galaxycraft.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3d;

/**
 * A planet as it is drawn from afar: each face of the cube a grid of at most PATCHES × PATCHES
 * patches of columns, each patch one quad as high as its columns' ground is on average, with the
 * top texture of the block most of them have there; walls join patches of different heights, and
 * skirts down along the face's edges hide the seams with the next face. Thousands of quads for a
 * whole planet instead of a million faces. One display list per face, its positions in whole
 * units from the planet's center (GX format {@link #GX_QUADS_FMT5}: s16, no fraction bits, then
 * color and texture coordinates as {@link PlanetMesher}'s).
 */
public final class PlanetLod {
    /** GX_QUADS | GX_VTXFMT5. */
    public static final int GX_QUADS_FMT5 = 0x80 | 5;
    public static final int PATCHES = 24;
    /** A face's display list and its bounding sphere (center from the planet's, radius; units). */
    public record Part(byte[] displayList, float[] sphere) {}

    private record Patch(int height, int block) {}

    private PlanetLod() {}

    /** The six faces' parts. */
    public static Part[] parts(VoxelPlanet p, double unitsPerBlock) {
        Part[] out = new Part[6];
        for (int f = 0; f < 6; f++) out[f] = face(p, f, unitsPerBlock);
        return out;
    }

    /** Columns along a patch's edge, for a planet with n columns along a face's. */
    static int patchColumns(int n) {
        return (n + PATCHES - 1) / PATCHES;
    }

    public static Part face(VoxelPlanet p, int face, double unitsPerBlock) {
        CubeSphere g = p.grid;
        int s = patchColumns(g.n), m = (g.n + s - 1) / s;
        Patch[][] patch = new Patch[m][m];
        for (int a = 0; a < m; a++)
            for (int b = 0; b < m; b++) patch[a][b] = patch(p, face, a * s, Math.min(g.n, a * s + s), b * s, Math.min(g.n, b * s + s));
        List<PlanetMesher.Quad> quads = new ArrayList<>();
        int skirt = 2 * s + 2;
        for (int a = 0; a < m; a++)
            for (int b = 0; b < m; b++) {
                Patch t = patch[a][b];
                int i0 = a * s, i1 = Math.min(g.n, i0 + s), j0 = b * s, j1 = Math.min(g.n, j0 + s);
                double r = g.radius(t.height);
                Vector3d mid = g.dir(face, i0, j0).add(g.dir(face, i1, j1)).normalize();
                quads.add(top(p, t.block, new Vector3d[] {g.dir(face, i0, j0).mul(r), g.dir(face, i1, j0).mul(r),
                        g.dir(face, i1, j1).mul(r), g.dir(face, i0, j1).mul(r)}, mid));
                // Walls toward lower neighbors (+i and +j here, -i and -j by those), skirts at the face's edges.
                wall(p, quads, t, a + 1 < m ? patch[a + 1][b] : null, g.dir(face, i1, j0), g.dir(face, i1, j1), mid, skirt);
                wall(p, quads, t, a > 0 ? patch[a - 1][b] : null, g.dir(face, i0, j0), g.dir(face, i0, j1), mid, skirt);
                wall(p, quads, t, b + 1 < m ? patch[a][b + 1] : null, g.dir(face, i0, j1), g.dir(face, i1, j1), mid, skirt);
                wall(p, quads, t, b > 0 ? patch[a][b - 1] : null, g.dir(face, i0, j0), g.dir(face, i1, j0), mid, skirt);
            }
        return new Part(displayList(p, quads, unitsPerBlock), sphere(quads, unitsPerBlock));
    }

    /**
     * The ground of a patch: the mean of its columns' tops (the top of the highest opaque block,
     * fluid or leaves; plants and air above them do not count) and the block most of them have there.
     */
    private static Patch patch(VoxelPlanet p, int face, int i0, int i1, int j0, int j1) {
        CubeSphere g = p.grid;
        Map<Integer, Integer> count = new HashMap<>();
        long sum = 0;
        int cols = 0, best = Blocks.AIR, bestCount = 0;
        for (int i = i0; i < i1; i++)
            for (int j = j0; j < j1; j++) {
                int top = 0, block = Blocks.AIR;
                for (int k = g.layers - 1; k >= 0; k--) {
                    int id = p.get(g.index(face, i, j, k));
                    BlockInfo b = p.blocks.info(id);
                    if (b.occludes() || b.isFluid() || p.blocks.leaves(id)) {
                        top = k + 1;
                        block = id;
                        break;
                    }
                }
                sum += top;
                cols++;
                int c = count.merge(block, 1, Integer::sum);
                if (c > bestCount) {
                    bestCount = c;
                    best = block;
                }
            }
        return new Patch((int) Math.round((double) sum / cols), best);
    }

    /** A patch's top quad, counter-clockwise seen from outside. */
    private static PlanetMesher.Quad top(VoxelPlanet p, int block, Vector3d[] q, Vector3d outward) {
        Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0]));
        if (n.dot(outward) < 0) {
            Vector3d t = q[1];
            q[1] = q[3];
            q[3] = t;
        }
        return quad(p, block, q, CubeSphere.TOP, new double[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}});
    }

    /**
     * The wall on the edge e0-e1 of patch t down to its neighbor nb if that is lower, or down skirt
     * layers if there is none (the face's edge); facing away from t's middle.
     */
    private static void wall(VoxelPlanet p, List<PlanetMesher.Quad> out, Patch t, Patch nb, Vector3d e0, Vector3d e1,
            Vector3d mid, int skirt) {
        int low = nb == null ? Math.max(0, t.height - skirt) : nb.height;
        if (low >= t.height) return;
        CubeSphere g = p.grid;
        double r0 = g.radius(low), r1 = g.radius(t.height);
        Vector3d[] q = {new Vector3d(e0).mul(r0), new Vector3d(e1).mul(r0), new Vector3d(e1).mul(r1), new Vector3d(e0).mul(r1)};
        Vector3d edge = new Vector3d(e0).add(e1).normalize();
        Vector3d away = edge.sub(mid);
        Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0]));
        if (n.dot(away) < 0) {
            Vector3d tmp = q[0];
            q[0] = q[1];
            q[1] = tmp;
            tmp = q[2];
            q[2] = q[3];
            q[3] = tmp;
        }
        // Inner corners first, so the texture's top edge (v 0) runs along the outer ones.
        out.add(quad(p, t.block, q, CubeSphere.I_MINUS, new double[][] {{0, 1}, {1, 1}, {1, 0}, {0, 0}}));
    }

    /** A quad with the block's texture for that side (its top, or a side), lit by the sun. */
    private static PlanetMesher.Quad quad(VoxelPlanet p, int block, Vector3d[] q, int side, double[][] uv) {
        BlockInfo b = p.blocks.info(block);
        int tile = b.tile(), tint = b.tint();
        ModelQuad pick = null;
        for (ModelQuad mq : b.quads()) {
            boolean wanted = side == CubeSphere.TOP ? mq.cull() == CubeSphere.TOP : mq.cull() >= CubeSphere.I_MINUS;
            if (wanted) {
                pick = mq;
                break;
            }
        }
        if (pick == null && !b.quads().isEmpty()) pick = b.quads().getFirst();
        if (pick != null) {
            tile = pick.tile();
            tint = pick.tint();
        }
        double sun = PlanetMesher.sun(q);
        return new PlanetMesher.Quad(q, tile, side, new double[] {sun, sun, sun, sun}, uv, tint);
    }

    private static byte[] displayList(VoxelPlanet p, List<PlanetMesher.Quad> quads, double unitsPerBlock) {
        if (quads.isEmpty()) return new byte[0];
        int cols = p.blocks.atlasColumns(), rows = p.blocks.atlasRows();
        int perDraw = 0xFFFF / 4, draws = (quads.size() + perDraw - 1) / perDraw;
        int size = 3 * draws + quads.size() * 4 * PlanetMesher.VERTEX_BYTES;
        ByteBuffer dl = ByteBuffer.allocate((size + 31) & ~31).order(ByteOrder.BIG_ENDIAN);
        for (int start = 0; start < quads.size(); start += perDraw) {
            int n = Math.min(perDraw, quads.size() - start);
            dl.put((byte) GX_QUADS_FMT5).putShort((short) (n * 4));
            for (PlanetMesher.Quad q : quads.subList(start, start + n)) {
                int tx = q.tile() % cols, ty = q.tile() / cols;
                for (int k = 0; k < 4; k++) {
                    Vector3d v = new Vector3d(q.corners()[k]).mul(unitsPerBlock);
                    dl.putShort(whole(v.x)).putShort(whole(v.y)).putShort(whole(v.z));
                    dl.putShort(PlanetMesher.rgb565(q.light()[k], q.tint()));
                    dl.putShort(PlanetMesher.st(tx, q.uv()[k][0], cols)).putShort(PlanetMesher.st(ty, q.uv()[k][1], rows));
                }
            }
        }
        return dl.array();
    }

    private static float[] sphere(List<PlanetMesher.Quad> quads, double unitsPerBlock) {
        Vector3d c = new Vector3d();
        int n = 0;
        for (PlanetMesher.Quad q : quads)
            for (Vector3d v : q.corners()) {
                c.add(v);
                n++;
            }
        if (n == 0) return new float[4];
        c.div(n);
        double r = 0;
        for (PlanetMesher.Quad q : quads)
            for (Vector3d v : q.corners()) r = Math.max(r, c.distance(v));
        return new float[] {(float) (c.x * unitsPerBlock), (float) (c.y * unitsPerBlock), (float) (c.z * unitsPerBlock),
                (float) (r * unitsPerBlock + 1)};
    }

    private static short whole(double units) {
        long v = Math.round(units);
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) throw new IllegalStateException("planet too big for its far view: " + units);
        return (short) v;
    }
}
