package dev.moui.galaxycraft.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
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
        int n = p.grid.n;
        return region(LodSource.of(p), face, 0, n, 0, n, patchColumns(n), unitsPerBlock);
    }

    /**
     * Tiles: the far view in pieces that stand in for chunks, so that the ground around Mario can
     * be his chunks and the rest of the planet its far view (PlanetSession's render distance). A
     * tile is TILE_CHUNKS × TILE_CHUNKS chunk columns of a face, patches of whole chunks inside it
     * and skirts all around (a tile's neighbor may be chunks, or another tile).
     */
    public static final int TILE_CHUNKS = 4;
    /** At most this many tiles along a face's edge (slots of the game's far view: 6 × 16 × 16). */
    public static final int MAX_TILES_PER_EDGE = 16;

    /** Tiles along a face's edge. */
    public static int tilesPerEdge(VoxelPlanet p) {
        int chunks = (p.grid.n + VoxelPlanet.CHUNK - 1) / VoxelPlanet.CHUNK;
        return (chunks + TILE_CHUNKS - 1) / TILE_CHUNKS;
    }

    public static int tileCount(VoxelPlanet p) {
        int t = tilesPerEdge(p);
        return 6 * t * t;
    }

    /** The tile a chunk lies in. */
    public static int tileOfChunk(VoxelPlanet p, int chunk) {
        int t = tilesPerEdge(p), per = p.chunksPerEdge(), layers = p.chunkLayers();
        int cj = chunk / layers % per, ci = chunk / (layers * per) % per, f = chunk / (layers * per * per);
        return (f * t + ci / TILE_CHUNKS) * t + cj / TILE_CHUNKS;
    }

    /** The chunks of a tile, every layer. */
    public static int[] chunksOfTile(VoxelPlanet p, int tile) {
        int t = tilesPerEdge(p), per = p.chunksPerEdge(), layers = p.chunkLayers();
        int f = tile / (t * t), ti = tile / t % t, tj = tile % t;
        int[] out = new int[TILE_CHUNKS * TILE_CHUNKS * layers];
        int n = 0;
        for (int ci = ti * TILE_CHUNKS; ci < Math.min(per, ti * TILE_CHUNKS + TILE_CHUNKS); ci++)
            for (int cj = tj * TILE_CHUNKS; cj < Math.min(per, tj * TILE_CHUNKS + TILE_CHUNKS); cj++)
                for (int ck = 0; ck < layers; ck++) out[n++] = ((f * per + ci) * per + cj) * layers + ck;
        return java.util.Arrays.copyOf(out, n);
    }

    /** Columns along a tile's patch: whole chunks, wider on big planets (about PATCHES of them along a face). */
    static int tilePatchColumns(int n) {
        int s = VoxelPlanet.CHUNK;
        while (s < TILE_CHUNKS * VoxelPlanet.CHUNK && (n + s - 1) / s > 2 * PATCHES) s *= 2;
        return s;
    }

    /** A tile's part of the far view (its display list empty if it has no ground at all). */
    public static Part tile(VoxelPlanet p, int tile, double unitsPerBlock) {
        int t = tilesPerEdge(p), n = p.grid.n, cols = TILE_CHUNKS * VoxelPlanet.CHUNK;
        int f = tile / (t * t), ti = tile / t % t, tj = tile % t;
        return region(LodSource.of(p), f, ti * cols, Math.min(n, ti * cols + cols), tj * cols, Math.min(n, tj * cols + cols),
                tilePatchColumns(n), unitsPerBlock);
    }

    /**
     * The six whole faces at patches × patches each (or fewer, a patch being one column at least):
     * the far view of a planet seen from far off.
     */
    public static Part[] coarse(LodSource src, int patches, double unitsPerBlock) {
        int n = src.grid().n, s = Math.max(1, (n + patches - 1) / patches);
        Part[] out = new Part[6];
        for (int f = 0; f < 6; f++) out[f] = region(src, f, 0, n, 0, n, s, unitsPerBlock);
        return out;
    }

    /** Columns [i0, i1) × [j0, j1) of a face, in patches of s × s: walls between them, skirts around. */
    private static Part region(LodSource p, int face, int i0r, int i1r, int j0r, int j1r, int s, double unitsPerBlock) {
        CubeSphere g = p.grid();
        int ma = (i1r - i0r + s - 1) / s, mb = (j1r - j0r + s - 1) / s;
        LodSource.Patch[][] patch = new LodSource.Patch[ma][mb];
        for (int a = 0; a < ma; a++)
            for (int b = 0; b < mb; b++)
                patch[a][b] = p.patch(face, i0r + a * s, Math.min(i1r, i0r + a * s + s), j0r + b * s, Math.min(j1r, j0r + b * s + s));
        List<PlanetMesher.Quad> quads = new ArrayList<>();
        int skirt = 2 * s + 2;
        for (int a = 0; a < ma; a++)
            for (int b = 0; b < mb; b++) {
                LodSource.Patch t = patch[a][b];
                int i0 = i0r + a * s, i1 = Math.min(i1r, i0 + s), j0 = j0r + b * s, j1 = Math.min(j1r, j0 + s);
                double r = g.radius(t.height());
                Vector3d mid = g.dir(face, i0, j0).add(g.dir(face, i1, j1)).normalize();
                quads.add(top(p, t, new Vector3d[] {g.dir(face, i0, j0).mul(r), g.dir(face, i1, j0).mul(r),
                        g.dir(face, i1, j1).mul(r), g.dir(face, i0, j1).mul(r)}, mid));
                // Walls toward lower neighbors (+i and +j here, -i and -j by those), skirts at the region's edges.
                wall(p, quads, t, a + 1 < ma ? patch[a + 1][b] : null, g.dir(face, i1, j0), g.dir(face, i1, j1), mid, skirt);
                wall(p, quads, t, a > 0 ? patch[a - 1][b] : null, g.dir(face, i0, j0), g.dir(face, i0, j1), mid, skirt);
                wall(p, quads, t, b + 1 < mb ? patch[a][b + 1] : null, g.dir(face, i0, j1), g.dir(face, i1, j1), mid, skirt);
                wall(p, quads, t, b > 0 ? patch[a][b - 1] : null, g.dir(face, i0, j0), g.dir(face, i1, j0), mid, skirt);
            }
        return new Part(displayList(p, quads, unitsPerBlock), sphere(quads, unitsPerBlock));
    }

    /** A patch's top quad, counter-clockwise seen from outside. */
    private static PlanetMesher.Quad top(LodSource p, LodSource.Patch t, Vector3d[] q, Vector3d outward) {
        Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0]));
        if (n.dot(outward) < 0) {
            Vector3d swap = q[1];
            q[1] = q[3];
            q[3] = swap;
        }
        return quad(p, t, q, CubeSphere.TOP, new double[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}});
    }

    /**
     * The wall on the edge e0-e1 of patch t down to its neighbor nb if that is lower, or down skirt
     * layers if there is none (the face's edge); facing away from t's middle.
     */
    private static void wall(LodSource p, List<PlanetMesher.Quad> out, LodSource.Patch t, LodSource.Patch nb, Vector3d e0, Vector3d e1,
            Vector3d mid, int skirt) {
        int low = nb == null ? Math.max(0, t.height() - skirt) : nb.height();
        if (low >= t.height()) return;
        CubeSphere g = p.grid();
        double r0 = g.radius(low), r1 = g.radius(t.height());
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
        out.add(quad(p, t, q, CubeSphere.I_MINUS, new double[][] {{0, 1}, {1, 1}, {1, 0}, {0, 0}}));
    }

    /** A quad with the block's texture for that side (its top, or a side), lit by the sun. */
    private static PlanetMesher.Quad quad(LodSource p, LodSource.Patch t, Vector3d[] q, int side, double[][] uv) {
        BlockInfo b = p.blocks().info(t.block());
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
        return new PlanetMesher.Quad(q, tile, side, new double[] {sun, sun, sun, sun}, uv, p.tint(t, tint));
    }

    private static byte[] displayList(LodSource p, List<PlanetMesher.Quad> quads, double unitsPerBlock) {
        if (quads.isEmpty()) return new byte[0];
        int cols = p.blocks().atlasColumns(), rows = p.blocks().atlasRows();
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
