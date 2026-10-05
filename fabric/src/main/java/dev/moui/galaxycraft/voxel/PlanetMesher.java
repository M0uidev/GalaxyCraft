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
 * coordinate u16 with 15 fraction bits) and a KCL relative to the planet's center. Blocks are
 * drawn with their Minecraft model ({@link ModelQuad}s) bent onto their cell ({@link CellSpace});
 * a face that faces a neighbor is left out where Minecraft would cull it. Fluids are drawn as high
 * as their level and never collide. Blocks collide with their collision boxes; those that fill the
 * cell collide as the planet's cubes always did. Galaxy units.
 */
public final class PlanetMesher {
    /** GX_QUADS | GX_VTXFMT7. */
    public static final int GX_QUADS_FMT7 = 0x80 | 7;
    /** A far view's vertex (PlanetLod, format 5): position, color, texture coordinate. */
    public static final int VERTEX_BYTES = 6 + 2 + 4;
    /** A chunk's (format 7): position, color, light (block light's color, sky light in alpha), texture coordinate. */
    public static final int LIT_VERTEX_BYTES = 6 + 2 + 4 + 4;
    /** Position fraction bits: 1/8 unit steps, ±4095 units around the chunk's center. */
    public static final int POS_FRAC = 3;
    /** Texture coordinates: 1.0 = 32768 (a texel of a 1024-wide atlas is 32). */
    public static final int ST_ONE = 1 << 15;
    /** Texels a tile of the atlas is wide; texture coordinates stay half a texel inside it. */
    public static final int TILE_TEXELS = 16;
    private static final double HALF_TEXEL = 0.5;
    /**
     * The light: a sun from this direction (planet axes = galaxy axes) plus some ambient, so a
     * planet has a day and a night side; and Minecraft's ambient occlusion at each corner.
     */
    public static final Vector3d SUN = new Vector3d(0.35, 0.85, 0.4).normalize();
    public static final double AMBIENT = 0.5;
    private static final double[] AO = {0.45, 0.62, 0.8, 1.0};
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

    /**
     * A face as drawn, corners in blocks: its atlas tile, the cell side it lies on (-1 inside the
     * cell), the brightness at each corner (sun and ambient occlusion), each corner's texture
     * coordinate within the tile (u, v from 0 to 1) and its tint (0xRRGGBB).
     */
    /**
     * levels: Minecraft's sky and block light at each corner (0 to 15, smoothed as Minecraft
     * smooths them), sky first; null: full daylight.
     */
    public record Quad(Vector3d[] corners, int tile, int side, double[] light, double[][] uv, int tint, boolean translucent,
            double[] levels) {
        public Quad(Vector3d[] corners, int tile, int side, double[] light, double[][] uv, int tint) {
            this(corners, tile, side, light, uv, tint, false, null);
        }

        public Quad(Vector3d[] corners, int tile, int side, double[] light, double[][] uv, int tint, boolean translucent) {
            this(corners, tile, side, light, uv, tint, translucent, null);
        }
    }

    private static final int[] LATERAL = {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS};
    /** A side's texture coordinates, corners as {@link CubeSphere#side} orders them. */
    private static final double[][] SIDE_UV = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};

    /** sphere: the chunk's bounding sphere (center from the planet's center, radius), galaxy units. */
    /** darkCut: faces of a dark cave were left out (see {@link #mesh}). */
    /** translucentAt: where in displayList the translucent faces' own list starts (its length: none). */
    public record ChunkMesh(byte[] displayList, byte[] kcl, float[] sphere, boolean darkCut, int translucentAt) {
        public ChunkMesh(byte[] displayList, byte[] kcl, float[] sphere, boolean darkCut) {
            this(displayList, kcl, sphere, darkCut, displayList.length);
        }

        public boolean empty() {
            return displayList.length == 0;
        }
    }

    private PlanetMesher() {}

    /** The visible faces of a chunk, corners in blocks. */
    public static List<Quad> quads(VoxelPlanet p, int chunk) {
        return quads(p, chunk, null);
    }

    /**
     * The visible faces of a chunk; with dark (a chunk far from Mario), not those that only show
     * from inside a cave the sky does not light (see {@link Dark}).
     */
    static List<Quad> quads(VoxelPlanet p, int chunk, Dark dark) {
        List<Quad> out = new ArrayList<>();
        for (int c : p.cellsOf(chunk)) {
            int id = p.get(c);
            if (id == Blocks.AIR) continue;
            BlockInfo b = p.blocks.info(id);
            if (b.isFluid()) {
                fluidQuads(p, c, b, out, dark);
                continue;
            }
            for (ModelQuad mq : b.quads()) {
                int nb = -1;
                if (mq.cull() >= 0) {
                    nb = p.grid.neighbor(c, mq.cull());
                    if (mq.cull() == CubeSphere.BOTTOM && nb < 0) continue; // faces the sealed center
                    if (!p.blocks.faceVisible(id, p.get(nb), mq.cull())) continue;
                    if (hiddenInCrown(p, id, nb, mq.cull())) continue;
                    if (dark != null && dark.in(nb)) continue;
                } else if (dark != null && dark.in(c)) continue;
                Vector3d[] q = new Vector3d[4];
                float[] pos = mq.pos();
                for (int v = 0; v < 4; v++) q[v] = CellSpace.point(p.grid, c, pos[3 * v], pos[3 * v + 1], pos[3 * v + 2]);
                double[] light = new double[4];
                double sun = sun(q);
                boolean faced = mq.cull() >= 0 && nb >= 0;
                double[] ao = faced ? ambientOcclusion(p, c, mq.cull(), nb) : null;
                double[] corners = faced ? lightCorners(p, mq.cull(), nb) : null;
                double[] levels = new double[8];
                for (int v = 0; v < 4; v++) {
                    double fi = pos[3 * v + 2], fj = pos[3 * v], fk = pos[3 * v + 1];
                    light[v] = sun * (ao == null ? 1 : trilinear(ao, fi, fj, fk));
                    if (corners != null) {
                        levels[v] = trilinear(corners, fi, fj, fk, 0);
                        levels[4 + v] = trilinear(corners, fi, fj, fk, 8);
                    } else { // inside its cell (a torch, a flower): the cell's own light
                        levels[v] = p.light().sky(c);
                        levels[4 + v] = p.light().block(c);
                    }
                }
                double[][] uv = new double[4][];
                for (int v = 0; v < 4; v++) uv[v] = new double[] {mq.uv()[2 * v], mq.uv()[2 * v + 1]};
                out.add(new Quad(q, mq.tile(), mq.cull(), light, uv, p.tint(c, mq.tint()), false, levels));
            }
        }
        return out;
    }

    /**
     * Something solid (not leaves: a tree is no roof) somewhere straight above cell: a viewer there
     * is in a cave, a tunnel or a house, where a dark cave's faces can show. False for -1.
     */
    public static boolean covered(VoxelPlanet p, int cell) {
        if (cell < 0) return false;
        for (int c = p.grid.neighbor(cell, CubeSphere.TOP); c >= 0; c = p.grid.neighbor(c, CubeSphere.TOP))
            if (p.occludes(c) && !p.blocks.leaves(p.get(c))) return true;
        return false;
    }

    /**
     * A leaf's face toward leaves that have leaves behind them too: deep in a crown, where the
     * holes of the leaves in front hardly show it. Minecraft draws every face between leaves, and
     * a forest planet would then draw several times more faces than all its ground.
     */
    static boolean hiddenInCrown(VoxelPlanet p, int id, int nb, int side) {
        if (!p.blocks.leaves(id) || !p.blocks.leaves(p.get(nb))) return false;
        int behind = p.grid.neighbor(nb, side);
        return behind >= 0 && (p.blocks.leaves(p.get(behind)) || p.info(behind).occludes());
    }

    /**
     * Cells deep in caves: farther than LIT steps through open cells (not opaque cubes) from any
     * open to the sky (nothing opaque straight above). Seen from afar, what faces them is hidden
     * by the ground over them; the mouth of a cave, near the sky, still shows. Answers are kept for
     * one chunk's meshing.
     */
    static final class Dark {
        static final int LIT = 6;
        private final VoxelPlanet p;
        private final java.util.Map<Integer, Boolean> dark = new java.util.HashMap<>(), sky = new java.util.HashMap<>();

        Dark(VoxelPlanet p) {
            this.p = p;
        }

        /** Some face was left out because of in. */
        boolean cut;

        boolean in(int cell) {
            if (cell < 0) return false;
            Boolean d = dark.get(cell);
            if (d == null) dark.put(cell, d = !lit(cell));
            cut |= d;
            return d;
        }

        private boolean lit(int from) {
            java.util.Map<Integer, Integer> steps = new java.util.HashMap<>();
            java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
            steps.put(from, 0);
            todo.add(from);
            while (!todo.isEmpty()) {
                int c = todo.poll(), n = steps.get(c);
                if (open(c)) return true;
                if (n == LIT) continue;
                for (int s = 0; s < 6; s++) {
                    int nb = p.grid.neighbor(c, s);
                    if (nb < 0) {
                        if (s == CubeSphere.TOP) return true; // above the planet's layers
                        continue;
                    }
                    if (steps.containsKey(nb) || p.occludes(nb)) continue;
                    steps.put(nb, n + 1);
                    todo.add(nb);
                }
            }
            return false;
        }

        /** Nothing opaque straight above. */
        private boolean open(int cell) {
            Boolean o = sky.get(cell);
            if (o != null) return o;
            int up = p.grid.neighbor(cell, CubeSphere.TOP);
            o = up < 0 || !p.occludes(up) && open(up);
            sky.put(cell, o);
            return o;
        }
    }

    /**
     * A fluid's faces as Minecraft's FluidRenderer makes them: the top sloped, each corner as high
     * as the fluid around it (sources weigh more), so a lake is one flat sheet and a stream runs
     * down smoothly; flowing water's top drawn with the flowing texture turned along the flow. No
     * face between the same fluid, none against an opaque cube. Water is translucent.
     */
    private static void fluidQuads(VoxelPlanet p, int c, BlockInfo b, List<Quad> out, Dark dark) {
        CubeSphere g = p.grid;
        int f = b.fluid();
        boolean translucent = f == Blocks.WATER;
        int tint = p.tint(c, b.tint());
        int still = b.tile(), flowing = p.blocks.flowTile(f);
        if (flowing < 0) flowing = still;
        double self = cornerSource(p, f, c);
        double[][] h = new double[2][2]; // corner heights by [di][dj]
        if (self >= 1) {
            for (double[] row : h) java.util.Arrays.fill(row, 1);
        } else {
            for (int di = 0; di < 2; di++)
                for (int dj = 0; dj < 2; dj++) {
                    int si = di == 0 ? CubeSphere.I_MINUS : CubeSphere.I_PLUS, sj = dj == 0 ? CubeSphere.J_MINUS : CubeSphere.J_PLUS;
                    int ni = g.neighbor(c, si), nj = g.neighbor(c, sj);
                    int diag = ni >= 0 ? g.neighbor(ni, sj) : nj >= 0 ? g.neighbor(nj, si) : -1;
                    h[di][dj] = averageHeight(p, f, self, cornerSource(p, f, nj), cornerSource(p, f, ni), diag);
                }
        }
        double r0 = g.radius(g.k(c));
        Vector3d center = g.center(c);
        // Lit as Minecraft lights fluids: the brighter of the cell and the one above it.
        int above = g.neighbor(c, CubeSphere.TOP);
        double sky = Math.max(p.light().sky(c), p.light().sky(above)), blk = Math.max(p.light().block(c), p.light().block(above));
        double[] levels = {sky, sky, sky, sky, blk, blk, blk, blk};

        int up = g.neighbor(c, CubeSphere.TOP);
        double lowest = Math.min(Math.min(h[0][0], h[0][1]), Math.min(h[1][0], h[1][1]));
        if (p.fluid(up) != f && !(lowest >= 1 && p.occludes(up)) && !(dark != null && up >= 0 && dark.in(up))) {
            int[][] k = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            Vector3d[] q = new Vector3d[4];
            double[][] uv = new double[4][];
            double[] flow = flow(p, f, c);
            boolean moving = Math.abs(flow[0]) > 1e-6 || Math.abs(flow[1]) > 1e-6;
            // Minecraft's model x is j, z is i (CellSpace): its flow (x, z) is (along j, along i).
            double angle = Math.atan2(flow[0], flow[1]) - Math.PI / 2, sn = Math.sin(angle) * 0.25, cs = Math.cos(angle) * 0.25;
            for (int v = 0; v < 4; v++) {
                int di = k[v][0], dj = k[v][1];
                q[v] = point(g, c, di, dj, r0 + h[di][dj]);
                if (!moving) uv[v] = new double[] {dj, di};
                else if (di == 0 && dj == 0) uv[v] = new double[] {0.5 - cs - sn, 0.5 - cs + sn};
                else if (di == 1 && dj == 0) uv[v] = new double[] {0.5 - cs + sn, 0.5 + cs + sn};
                else if (di == 1) uv[v] = new double[] {0.5 + cs + sn, 0.5 + cs - sn};
                else uv[v] = new double[] {0.5 + cs - sn, 0.5 - cs - sn};
            }
            addFluid(out, q, uv, center, moving ? flowing : still, CubeSphere.TOP, tint, translucent, levels);
        }
        int down = g.neighbor(c, CubeSphere.BOTTOM);
        if (down >= 0 && p.fluid(down) != f && !p.occludes(down) && !(dark != null && dark.in(down))) {
            Vector3d[] q = {point(g, c, 0, 0, r0), point(g, c, 1, 0, r0), point(g, c, 1, 1, r0), point(g, c, 0, 1, r0)};
            addFluid(out, q, new double[][] {{0, 0}, {0, 1}, {1, 1}, {1, 0}}, center, still, CubeSphere.BOTTOM, tint, translucent, levels);
        }
        for (int s : LATERAL) {
            int nb = g.neighbor(c, s);
            if (nb < 0 || p.fluid(nb) == f || p.occludes(nb) || dark != null && dark.in(nb)) continue;
            // The side's two corners (di, dj), bottom then top at each.
            int[] a = switch (s) {
                case CubeSphere.I_MINUS -> new int[] {0, 0, 0, 1};
                case CubeSphere.I_PLUS -> new int[] {1, 0, 1, 1};
                case CubeSphere.J_MINUS -> new int[] {0, 0, 1, 0};
                default -> new int[] {0, 1, 1, 1};
            };
            double h0 = h[a[0]][a[1]], h1 = h[a[2]][a[3]];
            Vector3d[] q = {point(g, c, a[0], a[1], r0), point(g, c, a[2], a[3], r0), point(g, c, a[2], a[3], r0 + h1),
                    point(g, c, a[0], a[1], r0 + h0)};
            double[][] uv = {{0, 0.5}, {0.5, 0.5}, {0.5, (1 - h1) * 0.5}, {0, (1 - h0) * 0.5}};
            addFluid(out, q, uv, center, flowing, s, tint, translucent, levels);
        }
    }

    /** Corner (di, dj) of cell's column at radius r. */
    private static Vector3d point(CubeSphere g, int cell, int di, int dj, double r) {
        return g.dir(g.face(cell), g.i(cell) + di, g.j(cell) + dj).mul(r);
    }

    /** A fluid face, turned to face away from the cell's center, lit by the sun alone (no ambient occlusion on fluids). */
    private static void addFluid(List<Quad> out, Vector3d[] q, double[][] uv, Vector3d center, int tile, int side, int tint,
            boolean translucent, double[] levels) {
        Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0]));
        Vector3d mid = new Vector3d(q[0]).add(q[1]).add(q[2]).add(q[3]).mul(0.25);
        if (n.dot(mid.sub(center)) < 0) {
            Vector3d t = q[1];
            q[1] = q[3];
            q[3] = t;
            double[] u = uv[1];
            uv[1] = uv[3];
            uv[3] = u;
        }
        double sun = sun(q);
        out.add(new Quad(q, tile, side, new double[] {sun, sun, sun, sun}, uv, tint, translucent, levels));
    }

    /**
     * FluidRenderer.getHeight: the fluid's height in cell (1 with the same fluid above it), 0 for
     * anything it could flow into, -1 for something solid (left out of the averages).
     */
    static double cornerSource(VoxelPlanet p, int f, int cell) {
        if (cell < 0) return 0;
        if (p.fluid(cell) == f) return Fluids.height(p, cell);
        return p.info(cell).collides() ? -1 : 0;
    }

    /** FluidRenderer.calculateAverageHeight: a corner's height from the three cells around it and this one. */
    static double averageHeight(VoxelPlanet p, int f, double self, double h2, double h1, int corner) {
        if (h1 >= 1 || h2 >= 1) return 1;
        double[] w = new double[2];
        if (h1 > 0 || h2 > 0) {
            double hc = cornerSource(p, f, corner);
            if (hc >= 1) return 1;
            weigh(w, hc);
        }
        weigh(w, self);
        weigh(w, h1);
        weigh(w, h2);
        return w[1] == 0 ? self : w[0] / w[1];
    }

    private static void weigh(double[] w, double h) {
        if (h >= 0.8) {
            w[0] += h * 10;
            w[1] += 10;
        } else if (h >= 0) {
            w[0] += h;
            w[1] += 1;
        }
    }

    /** A fluid's own height (FlowingFluid.getOwnHeight): its amount / 9, falling counting full. */
    private static double ownHeight(VoxelPlanet p, int f, int cell) {
        if (cell < 0 || p.fluid(cell) != f) return 0;
        int l = p.level(cell);
        return (l == 0 || l >= 8 ? 8 : 8 - l) / 9.0;
    }

    /** FlowingFluid.getFlow, across the cell: (along i, along j), not normalized; 0 for still fluid. */
    static double[] flow(VoxelPlanet p, int f, int c) {
        double own = ownHeight(p, f, c), fi = 0, fj = 0;
        for (int s : LATERAL) {
            int nb = p.grid.neighbor(c, s);
            if (nb < 0) continue;
            int nf = p.fluid(nb);
            if (nf != f && nf != Blocks.NO_FLUID) continue;
            double nh = ownHeight(p, f, nb), d = 0;
            if (nh == 0) {
                if (!p.info(nb).collides()) {
                    int below = p.grid.neighbor(nb, CubeSphere.BOTTOM);
                    double bh = ownHeight(p, f, below);
                    if (bh > 0) d = own - (bh - 8 / 9.0);
                }
            } else d = own - nh;
            if (d == 0) continue;
            switch (s) {
                case CubeSphere.I_MINUS -> fi -= d;
                case CubeSphere.I_PLUS -> fi += d;
                case CubeSphere.J_MINUS -> fj -= d;
                default -> fj += d;
            }
        }
        return new double[] {fi, fj};
    }

    /**
     * Where a chunk collides, quads of corners in blocks: each face of a full block that faces
     * something that is not one (see fullFace), and each box of other blocks but where a full
     * block covers it.
     */
    public static List<Vector3d[]> collision(VoxelPlanet p, int chunk) {
        List<Vector3d[]> out = new ArrayList<>();
        for (int c : p.cellsOf(chunk)) {
            BlockInfo b = p.info(c);
            if (!b.collides()) continue;
            if (b.fullCollision()) {
                for (int s = 0; s < 6; s++) {
                    int nb = p.grid.neighbor(c, s);
                    if (s == CubeSphere.BOTTOM && nb < 0) continue;
                    if (p.fullCollision(nb)) continue;
                    out.add(fullFace(p, c, s, nb, p.grid.side(c, s)));
                }
                continue;
            }
            for (double[] box : b.boxes())
                for (ModelQuad f : BoxModel.box(box, 0, 0, 0, 0)) {
                    if (f.cull() >= 0 && p.fullCollision(p.grid.neighbor(c, f.cull()))) continue;
                    Vector3d[] q = new Vector3d[4];
                    for (int v = 0; v < 4; v++)
                        q[v] = CellSpace.point(p.grid, c, f.pos()[3 * v], f.pos()[3 * v + 1], f.pos()[3 * v + 2]);
                    out.add(q);
                }
        }
        return out;
    }

    /**
     * Where a full block's face collides, blocks: a wall WALL_INSET inside its block; a floor or
     * ceiling FLOOR_GROW wider along each edge a wall rises from (the cell in front of it, nb, has a
     * full neighbor there), so that it reaches under that wall. Toward open space it stays as it looks.
     */
    private static Vector3d[] fullFace(VoxelPlanet p, int c, int s, int nb, Vector3d[] q) {
        Vector3d[] k = new Vector3d[4];
        for (int i = 0; i < 4; i++) k[i] = new Vector3d(q[i]);
        if (s != CubeSphere.TOP && s != CubeSphere.BOTTOM) {
            if (WALL_INSET <= 0) return k;
            Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0])).normalize();
            for (Vector3d v : k) v.sub(new Vector3d(n).mul(WALL_INSET));
            return k;
        }
        if (FLOOR_GROW <= 0 || nb < 0) return k;
        for (int side : LATERAL) {
            if (!p.fullCollision(p.grid.neighbor(nb, side))) continue;
            // The edge on that side, and the outward direction across it within the face.
            Vector3d[] wall = p.grid.side(c, side);
            Vector3d n = new Vector3d(wall[1]).sub(wall[0]).cross(new Vector3d(wall[2]).sub(wall[0])).normalize();
            for (int i = 0; i < 4; i++)
                if (Math.abs(new Vector3d(q[i]).sub(wall[0]).dot(n)) < 1e-4) k[i].add(new Vector3d(n).mul(FLOOR_GROW));
        }
        return k;
    }

    /** The sun on a face: its normal against SUN, over the ambient light. */
    static double sun(Vector3d[] q) {
        Vector3d n = new Vector3d(q[2]).sub(q[0]).cross(new Vector3d(q[3]).sub(q[1]));
        if (n.lengthSquared() < 1e-18) return AMBIENT;
        n.normalize();
        return AMBIENT + (1 - AMBIENT) * Math.max(0, n.dot(SUN));
    }

    private static double trilinear(double[] v, double fi, double fj, double fk) {
        return trilinear(v, fi, fj, fk, 0);
    }

    /** Value at model point (x, y, z) of eight values at the cell's corners, indexed di | dj << 1 | dk << 2, from v[at]. */
    private static double trilinear(double[] v, double fi, double fj, double fk, int at) {
        fi = Math.max(0, Math.min(1, fi));
        fj = Math.max(0, Math.min(1, fj));
        fk = Math.max(0, Math.min(1, fk));
        double out = 0;
        for (int m = 0; m < 8; m++)
            out += v[at + m] * ((m & 1) == 1 ? fi : 1 - fi) * ((m >> 1 & 1) == 1 ? fj : 1 - fj) * ((m >> 2) == 1 ? fk : 1 - fk);
        return out;
    }

    public static ChunkMesh mesh(VoxelPlanet p, int chunk, double unitsPerBlock) {
        return mesh(p, chunk, unitsPerBlock, true);
    }

    /**
     * Minecraft's ambient occlusion on side of cell at each of the cell's corners (indexed
     * di | dj << 1 | dk << 2; the corners off that side copy those across from them): of the cell
     * in front of the face, the two neighbors toward that corner and the one between them; both
     * sides opaque is the darkest.
     */
    static double[] ambientOcclusion(VoxelPlanet p, int cell, int side, int front) {
        CubeSphere g = p.grid;
        double[] out = {1, 1, 1, 1, 1, 1, 1, 1};
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
                boolean o1 = p.occludes(s1), o2 = p.occludes(s2), oc = p.occludes(corner);
                int level = o1 && o2 ? 0 : 3 - ((o1 ? 1 : 0) + (o2 ? 1 : 0) + (oc ? 1 : 0));
                // Which corner of the cell this is: on this face at (a, b), and the one across it.
                int di, dj, dk, across;
                switch (side) {
                    case CubeSphere.TOP, CubeSphere.BOTTOM -> { di = a; dj = b; dk = side == CubeSphere.TOP ? 1 : 0; across = 4; }
                    case CubeSphere.I_MINUS, CubeSphere.I_PLUS -> { di = side == CubeSphere.I_PLUS ? 1 : 0; dj = a; dk = b; across = 1; }
                    default -> { di = a; dj = side == CubeSphere.J_PLUS ? 1 : 0; dk = b; across = 2; }
                }
                int m = di | dj << 1 | dk << 2;
                out[m] = out[m ^ across] = AO[level];
            }
        return out;
    }

    /**
     * Minecraft's smooth light on side of a cell at each of the cell's corners (indexed as in
     * {@link #ambientOcclusion}; sky light in [0, 8), block light in [8, 16)): the light of the cell
     * in front of the face averaged with the two neighbors toward that corner and the one between
     * them; one that is opaque counts as the front cell (it has no light of its own).
     */
    static double[] lightCorners(VoxelPlanet p, int side, int front) {
        CubeSphere g = p.grid;
        PlanetLight l = p.light();
        double[] out = new double[16];
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
        int fs = l.sky(front), fb = l.block(front);
        for (int a = 0; a < 2; a++)
            for (int b = 0; b < 2; b++) {
                int s1 = g.neighbor(front, axisA[a]), s2 = g.neighbor(front, axisB[b]);
                int corner = s1 >= 0 ? g.neighbor(s1, axisB[b]) : -1;
                boolean o1 = p.occludes(s1), o2 = p.occludes(s2), oc = o1 && o2 || p.occludes(corner);
                double sky = fs + (o1 ? fs : l.sky(s1)) + (o2 ? fs : l.sky(s2)) + (oc ? fs : l.sky(corner));
                double blk = fb + (o1 ? fb : l.block(s1)) + (o2 ? fb : l.block(s2)) + (oc ? fb : l.block(corner));
                int di, dj, dk, across;
                switch (side) {
                    case CubeSphere.TOP, CubeSphere.BOTTOM -> { di = a; dj = b; dk = side == CubeSphere.TOP ? 1 : 0; across = 4; }
                    case CubeSphere.I_MINUS, CubeSphere.I_PLUS -> { di = side == CubeSphere.I_PLUS ? 1 : 0; dj = a; dk = b; across = 1; }
                    default -> { di = a; dj = side == CubeSphere.J_PLUS ? 1 : 0; dk = b; across = 2; }
                }
                int m = di | dj << 1 | dk << 2;
                out[m] = out[m ^ across] = sky / 4;
                out[8 + m] = out[8 + (m ^ across)] = blk / 4;
            }
        return out;
    }

    /** Sun and ambient occlusion at the corners of a side q of cell, in q's order. */
    static double[] light(VoxelPlanet p, int cell, int side, int front, Vector3d[] q) {
        double sun = sun(q);
        double[] out = {sun, sun, sun, sun};
        if (front < 0) return out;
        double[] ao = ambientOcclusion(p, cell, side, front);
        for (int m = 0; m < 8; m++) {
            Vector3d v = p.grid.corner(cell, m & 1, m >> 1 & 1, m >> 2);
            for (int k = 0; k < 4; k++) if (q[k].distanceSquared(v) < 1e-12) out[k] *= ao[m];
        }
        return out;
    }

    /**
     * near false: far from Mario, drawn only (no collision part) and without what shows only from
     * inside a dark cave, as the ground over it hides it from afar.
     */
    public static ChunkMesh mesh(VoxelPlanet p, int chunk, double unitsPerBlock, boolean near) {
        return mesh(p, chunk, unitsPerBlock, near, !near);
    }

    /**
     * With collision if near; cullDark leaves out what shows only from inside a dark cave, hidden
     * from a viewer outside by the ground over it (not from one in a cave).
     */
    public static ChunkMesh mesh(VoxelPlanet p, int chunk, double unitsPerBlock, boolean near, boolean cullDark) {
        boolean withKcl = near;
        Vector3d origin = new Vector3d();
        double[] radius = new double[1];
        p.sphere(chunk, origin, radius);
        // Whole units: every chunk's 1/8-unit vertex grid is then the same one, so a corner two
        // chunks share is the same point in both (rounded from each one's own center, they parted
        // by up to 1/8 unit and showed the background through the seam). The radius covers the shift.
        origin.mul(unitsPerBlock).round();
        float[] sphere = {(float) origin.x, (float) origin.y, (float) origin.z, (float) (radius[0] * unitsPerBlock + 1)};
        Dark dark = cullDark ? new Dark(p) : null;
        List<Quad> quads = quads(p, chunk, dark);
        boolean darkCut = dark != null && dark.cut;
        if (quads.isEmpty()) return new ChunkMesh(new byte[0], new byte[0], sphere, darkCut);
        List<Quad> solid = new ArrayList<>(), clear = new ArrayList<>();
        for (Quad q : quads) (q.translucent() ? clear : solid).add(q);
        byte[] first = displayList(p, solid, unitsPerBlock, origin), second = displayList(p, clear, unitsPerBlock, origin);
        byte[] dl = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, dl, first.length, second.length);
        List<Tri> tris = new ArrayList<>();
        if (withKcl)
            for (Vector3d[] c : collision(p, chunk)) {
                Vector3d[] k = new Vector3d[4];
                for (int i = 0; i < 4; i++) k[i] = new Vector3d(c[i]).mul(unitsPerBlock);
                tris.add(Tri.of(k[0], k[1], k[2]));
                tris.add(Tri.of(k[0], k[2], k[3]));
            }
        return new ChunkMesh(dl, tris.isEmpty() ? new byte[0] : KclWriter.write(tris), sphere, darkCut, first.length);
    }

    /** One GX draw of quads, padded to 32 bytes; empty for none. */
    private static byte[] displayList(VoxelPlanet p, List<Quad> quads, double unitsPerBlock, Vector3d origin) {
        if (quads.isEmpty()) return new byte[0];
        if (quads.size() * 4 > 0xFFFF) throw new IllegalStateException("chunk too detailed for one draw");
        int size = 3 + quads.size() * 4 * LIT_VERTEX_BYTES;
        ByteBuffer dl = ByteBuffer.allocate((size + 31) & ~31).order(ByteOrder.BIG_ENDIAN);
        dl.put((byte) GX_QUADS_FMT7).putShort((short) (quads.size() * 4));
        int cols = p.blocks.atlasColumns(), rows = p.blocks.atlasRows();
        for (Quad q : quads) {
            int tx = q.tile() % cols, ty = q.tile() / cols;
            for (int k = 0; k < 4; k++) {
                Vector3d v = new Vector3d(q.corners()[k]).mul(unitsPerBlock);
                dl.putShort(fixed(v.x - origin.x)).putShort(fixed(v.y - origin.y)).putShort(fixed(v.z - origin.z));
                dl.putShort(rgb565(q.light()[k], q.tint()));
                dl.putInt(q.levels() == null ? DAYLIGHT : lightRGBA(q.levels()[k], q.levels()[4 + k]));
                dl.putShort(st(tx, q.uv()[k][0], cols)).putShort(st(ty, q.uv()[k][1], rows));
            }
        }
        return dl.array();
    }

    /** A texture coordinate: f (0..1) across tile t of n tiles, kept half a texel inside it. */
    static short st(int t, double f, int n) {
        double texel = Math.max(HALF_TEXEL, Math.min(TILE_TEXELS - HALF_TEXEL, f * TILE_TEXELS));
        return (short) Math.round((t * TILE_TEXELS + texel) / (n * TILE_TEXELS) * ST_ONE);
    }

    /** Minecraft's warm block light (its overworld BLOCK_LIGHT_TINT, FFD88C) and how much more it counts (BlockFactor). */
    private static final double[] BLOCK_TINT = {1, 0xD8 / 255.0, 0x8C / 255.0};
    private static final double BLOCK_FACTOR = 1.4, DARKEST = 0.02;
    /** Full sky light, no block light. */
    static final int DAYLIGHT = lightRGBA(15, 0);

    /**
     * A corner's light as the game combines it (VoxelPlanet.cpp): block light's color in RGB, sky
     * light's brightness in A, each through Minecraft's lightmap (lightmap.fsh: the level's curve,
     * block light's tint, the brightness setting's lift at its default). The game adds A times the
     * sky's color of the hour (GXC_MSG_SKY) to RGB.
     */
    static int lightRGBA(double sky, double block) {
        double lb = Math.clamp(block / 15, 0, 1), ls = Math.clamp(sky / 15, 0, 1);
        double bb = curve(lb) * BLOCK_FACTOR, parabolic = (2 * lb - 1) * (2 * lb - 1);
        double[] c = new double[3];
        double max = 0;
        for (int i = 0; i < 3; i++) {
            c[i] = Math.min(1, (BLOCK_TINT[i] + (1 - BLOCK_TINT[i]) * 0.9 * parabolic) * bb + DARKEST);
            max = Math.max(max, c[i]);
        }
        double lift = lift(max) / max;
        int rgba = 0;
        for (int i = 0; i < 3; i++) rgba = rgba << 8 | (int) Math.round(255 * Math.min(1, (c[i] + c[i] * lift) / 2));
        double a = curve(ls);
        a = (a + lift(a)) / 2;
        return rgba << 8 | (int) Math.round(255 * a);
    }

    /** Minecraft's brightness of a light level (0 to 1): v / (4 - 3v). */
    private static double curve(double v) {
        return v / (4 - 3 * v);
    }

    /** lightmap.fsh's notGamma of a brightness. */
    private static double lift(double x) {
        double inv = 1 - x;
        return 1 - inv * inv * inv * inv;
    }

    /** Brightness times tint, as RGB565. */
    static short rgb565(double light, int tint) {
        int r = (int) Math.round(light * (tint >> 16 & 0xFF)), g = (int) Math.round(light * (tint >> 8 & 0xFF));
        int b = (int) Math.round(light * (tint & 0xFF));
        r = Math.min(255, r);
        g = Math.min(255, g);
        b = Math.min(255, b);
        return (short) ((r >> 3) << 11 | (g >> 2) << 5 | b >> 3);
    }

    private static short fixed(double units) {
        long v = Math.round(units * (1 << POS_FRAC));
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) throw new IllegalStateException("chunk too wide: " + units);
        return (short) v;
    }
}
