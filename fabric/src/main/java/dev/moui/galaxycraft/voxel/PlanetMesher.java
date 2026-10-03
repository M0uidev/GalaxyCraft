package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.geom.Tri;
import dev.moui.galaxycraft.kcl.KclWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/**
 * Turns a chunk into what SMG2 needs: a GX display list (quads in vertex format 7: position s16
 * with 3 fraction bits, relative to the chunk's bounding sphere center; color RGB565; texture
 * coordinate u16 with 10 fraction bits) and a KCL relative to the planet's center. Only sides
 * that can be seen are kept: a block's facing air or a fluid, a fluid's facing air (or its own
 * fluid lower down). Fluids are drawn as high as their level and never collide. Galaxy units.
 */
public final class PlanetMesher {
    /** GX_QUADS | GX_VTXFMT7. */
    public static final int GX_QUADS_FMT7 = 0x80 | 7;
    public static final int VERTEX_BYTES = 6 + 2 + 4;
    /** Position fraction bits: 1/8 unit steps, ±4095 units around the chunk's center. */
    public static final int POS_FRAC = 3;
    /** Texture coordinates: 1.0 = 1024; a tile is 256, a texel of the 64×64 atlas 16. */
    private static final int TILE_ST = 256, HALF_TEXEL = 8;
    /**
     * The light: a sun from this direction (planet axes = galaxy axes) plus some ambient, so a
     * planet has a day and a night side; and Minecraft's ambient occlusion at each corner.
     */
    public static final Vector3d SUN = new Vector3d(0.35, 0.85, 0.4).normalize();
    public static final double AMBIENT = 0.5;
    private static final double[] AO = {0.45, 0.62, 0.8, 1.0};
    private static final int ATLAS_TILES = 4;
    /**
     * Walls collide this far inside their block (blocks): with walls closer than ~35 units on
     * several sides (a 1x1 hole) Mario bounced sideways every third frame, something in his own
     * movement that no radius patch reaches (tools/gxfit.sh measures it). -Dgalaxycraft.wallInset.
     */
    public static final double WALL_INSET = Double.parseDouble(System.getProperty("galaxycraft.wallInset", "0.1"));
    /**
     * Floors and ceilings collide this much farther under the walls around them (blocks), so the
     * inset walls leave no slot at their foot. -Dgalaxycraft.floorGrow.
     */
    public static final double FLOOR_GROW = Double.parseDouble(System.getProperty("galaxycraft.floorGrow", "0.12"));

    /** light: brightness 0..1 at each corner (sun and ambient occlusion); solid: it collides. */
    public record Quad(Vector3d[] corners, int tile, int side, double[] light, boolean solid, Vector3d[] collision) {
        public Quad(Vector3d[] corners, int tile, int side, double[] light, boolean solid) {
            this(corners, tile, side, light, solid, corners);
        }
    }

    private static final int[] LATERAL = {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS};

    /** sphere: the chunk's bounding sphere (center from the planet's center, radius), galaxy units. */
    public record ChunkMesh(byte[] displayList, byte[] kcl, float[] sphere) {
        public boolean empty() {
            return displayList.length == 0;
        }
    }

    private PlanetMesher() {}

    /** The visible sides of a chunk, corners in blocks (see {@link CubeSphere#side}). */
    public static List<Quad> quads(VoxelPlanet p, int chunk) {
        List<Quad> out = new ArrayList<>();
        for (int c : p.cellsOf(chunk)) {
            Material m = p.get(c);
            if (m == Material.AIR) continue;
            double top = m.fluid() ? Fluids.height(p, c) : 1;
            for (int s = 0; s < 6; s++) {
                int nb = p.grid.neighbor(c, s);
                if (s == CubeSphere.BOTTOM && nb < 0) continue; // faces the sealed center
                Material o = p.get(nb);
                if (o.solid()) continue;
                double bottom = 0; // of a side: a fluid shows only above its own fluid next to it
                if (o == m) {
                    if (s == CubeSphere.TOP || s == CubeSphere.BOTTOM) continue;
                    bottom = Fluids.height(p, nb);
                    if (bottom >= top) continue;
                }
                int tile = s == CubeSphere.TOP ? m.top : s == CubeSphere.BOTTOM ? m.bottom : m.side;
                Vector3d[] q = p.grid.side(c, s);
                if (top < 1 || bottom > 0) lower(q, p.grid.radius(p.grid.k(c)), bottom, top);
                out.add(new Quad(q, tile, s, light(p, c, s, nb, q), m.solid(), m.solid() ? collision(p, c, s, nb, q) : q));
            }
        }
        return out;
    }

    /**
     * Where a solid face collides, blocks: a wall WALL_INSET inside its block; a floor or ceiling
     * FLOOR_GROW wider along each edge a wall rises from (the cell in front of it, nb, has a solid
     * neighbor there), so that it reaches under that wall. Toward open space it stays as it looks.
     */
    private static Vector3d[] collision(VoxelPlanet p, int c, int s, int nb, Vector3d[] q) {
        Vector3d[] k = new Vector3d[4];
        for (int i = 0; i < 4; i++) k[i] = new Vector3d(q[i]);
        if (s != CubeSphere.TOP && s != CubeSphere.BOTTOM) {
            if (WALL_INSET <= 0) return k;
            Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0])).normalize();
            for (Vector3d v : k) v.sub(new Vector3d(n).mul(WALL_INSET));
            return k;
        }
        if (FLOOR_GROW <= 0) return k;
        for (int side : LATERAL) {
            if (!p.get(p.grid.neighbor(nb, side)).solid()) continue;
            // The edge on that side, and the outward direction across it within the face.
            Vector3d[] wall = p.grid.side(c, side);
            Vector3d n = new Vector3d(wall[1]).sub(wall[0]).cross(new Vector3d(wall[2]).sub(wall[0])).normalize();
            for (int i = 0; i < 4; i++)
                if (Math.abs(new Vector3d(q[i]).sub(wall[0]).dot(n)) < 1e-4) k[i].add(new Vector3d(n).mul(FLOOR_GROW));
        }
        return k;
    }

    /** Corners on the inner radius r0 go to r0 + bottom, those on the outer one to r0 + top. */
    private static void lower(Vector3d[] q, double r0, double bottom, double top) {
        for (Vector3d v : q) {
            double r = v.length();
            v.mul((r < r0 + 0.5 ? r0 + bottom : r0 + top) / r);
        }
    }

    public static ChunkMesh mesh(VoxelPlanet p, int chunk, double unitsPerBlock) {
        return mesh(p, chunk, unitsPerBlock, true);
    }

    /**
     * Sun on the face (its normal against SUN) times Minecraft's ambient occlusion at each corner:
     * of the cell in front of the face, the two neighbors toward that corner and the one between
     * them; both sides solid is the darkest. Corners in q's order.
     */
    static double[] light(VoxelPlanet p, int cell, int side, int front, Vector3d[] q) {
        CubeSphere g = p.grid;
        Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0])).normalize();
        double sun = AMBIENT + (1 - AMBIENT) * Math.max(0, n.dot(SUN));
        double[] out = {sun, sun, sun, sun};
        if (front < 0) return out;
        // The two axes along the face: (minus side, plus side) each, and which corner index they vary.
        int[] axisA, axisB;
        switch (side) {
            case CubeSphere.TOP, CubeSphere.BOTTOM -> {
                axisA = new int[] {CubeSphere.I_MINUS, CubeSphere.I_PLUS};
                axisB = new int[] {CubeSphere.J_MINUS, CubeSphere.J_PLUS};
            }
            case CubeSphere.I_MINUS, CubeSphere.I_PLUS -> {
                axisA = new int[] {CubeSphere.J_MINUS, CubeSphere.J_PLUS};
                axisB = new int[] {CubeSphere.BOTTOM, CubeSphere.TOP};
            }
            default -> {
                axisA = new int[] {CubeSphere.I_MINUS, CubeSphere.I_PLUS};
                axisB = new int[] {CubeSphere.BOTTOM, CubeSphere.TOP};
            }
        }
        for (int a = 0; a < 2; a++)
            for (int b = 0; b < 2; b++) {
                int s1 = g.neighbor(front, axisA[a]), s2 = g.neighbor(front, axisB[b]);
                int corner = s1 >= 0 ? g.neighbor(s1, axisB[b]) : -1;
                boolean o1 = p.get(s1).solid(), o2 = p.get(s2).solid(), oc = p.get(corner).solid();
                int level = o1 && o2 ? 0 : 3 - ((o1 ? 1 : 0) + (o2 ? 1 : 0) + (oc ? 1 : 0));
                if (level == 3) continue;
                // Which corner of the quad this is: the cell's corner on this face at (a, b).
                int di, dj, dk;
                switch (side) {
                    case CubeSphere.TOP, CubeSphere.BOTTOM -> { di = a; dj = b; dk = side == CubeSphere.TOP ? 1 : 0; }
                    case CubeSphere.I_MINUS, CubeSphere.I_PLUS -> { di = side == CubeSphere.I_PLUS ? 1 : 0; dj = a; dk = b; }
                    default -> { di = a; dj = side == CubeSphere.J_PLUS ? 1 : 0; dk = b; }
                }
                Vector3d v = g.corner(cell, di, dj, dk);
                for (int k = 0; k < 4; k++) if (q[k].distanceSquared(v) < 1e-12) out[k] *= AO[level];
            }
        return out;
    }

    /** withKcl false: drawn only (far from Mario, no collision part). */
    public static ChunkMesh mesh(VoxelPlanet p, int chunk, double unitsPerBlock, boolean withKcl) {
        Vector3d origin = new Vector3d();
        double[] radius = new double[1];
        p.sphere(chunk, origin, radius);
        origin.mul(unitsPerBlock);
        float[] sphere = {(float) origin.x, (float) origin.y, (float) origin.z, (float) (radius[0] * unitsPerBlock)};
        List<Quad> quads = quads(p, chunk);
        if (quads.isEmpty()) return new ChunkMesh(new byte[0], new byte[0], sphere);
        if (quads.size() * 4 > 0xFFFF) throw new IllegalStateException("chunk too detailed for one draw");
        int size = 3 + quads.size() * 4 * VERTEX_BYTES;
        ByteBuffer dl = ByteBuffer.allocate((size + 31) & ~31).order(ByteOrder.BIG_ENDIAN);
        dl.put((byte) GX_QUADS_FMT7).putShort((short) (quads.size() * 4));
        List<Tri> tris = new ArrayList<>();
        for (Quad q : quads) {
            Vector3d[] v = new Vector3d[4];
            for (int k = 0; k < 4; k++) v[k] = new Vector3d(q.corners()[k]).mul(unitsPerBlock);
            int tx = q.tile() % ATLAS_TILES, ty = q.tile() / ATLAS_TILES;
            // q[0], q[1] bottom edge (v = 1), q[2], q[3] top edge (v = 0), left to right then back.
            int s0 = tx * TILE_ST + HALF_TEXEL, s1 = (tx + 1) * TILE_ST - HALF_TEXEL;
            int t0 = ty * TILE_ST + HALF_TEXEL, t1 = (ty + 1) * TILE_ST - HALF_TEXEL;
            int[][] st = {{s0, t1}, {s1, t1}, {s1, t0}, {s0, t0}};
            for (int k = 0; k < 4; k++) {
                int shade = (int) Math.round(255 * q.light()[k]);
                short rgb565 = (short) ((shade >> 3) << 11 | (shade >> 2) << 5 | shade >> 3);
                dl.putShort(fixed(v[k].x - origin.x)).putShort(fixed(v[k].y - origin.y)).putShort(fixed(v[k].z - origin.z));
                dl.putShort(rgb565);
                dl.putShort((short) st[k][0]).putShort((short) st[k][1]);
            }
            if (withKcl && q.solid()) {
                Vector3d[] k = new Vector3d[4];
                for (int c = 0; c < 4; c++) k[c] = new Vector3d(q.collision()[c]).mul(unitsPerBlock);
                tris.add(Tri.of(k[0], k[1], k[2]));
                tris.add(Tri.of(k[0], k[2], k[3]));
            }
        }
        return new ChunkMesh(dl.array(), tris.isEmpty() ? new byte[0] : KclWriter.write(tris), sphere);
    }

    private static short fixed(double units) {
        long v = Math.round(units * (1 << POS_FRAC));
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) throw new IllegalStateException("chunk too wide: " + units);
        return (short) v;
    }
}
