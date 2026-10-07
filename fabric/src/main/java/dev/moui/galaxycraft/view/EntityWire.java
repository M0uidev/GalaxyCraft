package dev.moui.galaxycraft.view;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Entities as the game draws them (EntityDraw, GXC_ENT_* in the protocol): textures (skins),
 * rigid pieces of models (display lists of quads in model pixels) and, each frame, where each
 * piece is. All big-endian: the host passes them on untouched. Also the models of Minecraft's
 * items and blocks lying around: a flat sprite one texel thick, or a cube. No Minecraft types,
 * so it is unit tested.
 */
public final class EntityWire {
    /** GX_QUADS | GXC_ENT_VTXFMT. */
    static final int GX_QUADS = 0x80 | Layout.ENT_VTXFMT;
    static final int VERTEX_BYTES = 6 + 4 + 4;
    static final int POS_FRAC = 16, ST_FRAC = 4096;

    /** A quad: 4 corners (x, y, z in model pixels), their texture coordinates (0..1), RGBA. */
    public record Quad(float[][] pos, float[][] uv, int rgba) {}

    /** One piece this frame: model and skin ids, overlay and tint RGBA, 3x4 row-major model pixels -> galaxy. */
    public record Piece(int model, int skin, int overlay, int tint, double[] mtx) {}

    private EntityWire() {}

    /** GXC_MSG_SKIN: an ARGB image (row 0 at the top) as GX RGB5A3, padded to multiples of 4. */
    public static byte[] skin(int id, int width, int height, int[] argb) {
        int w = (width + 3) & ~3, h = (height + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(12 + w * h * 2).order(ByteOrder.BIG_ENDIAN);
        b.putInt(id).putInt(w).putInt(h);
        for (int by = 0; by < h; by += 4)
            for (int bx = 0; bx < w; bx += 4)
                for (int y = by; y < by + 4; y++)
                    for (int x = bx; x < bx + 4; x++)
                        b.putShort(x < width && y < height ? HeldItem.rgb5a3(argb[y * width + x]) : 0);
        return b.array();
    }

    /** GXC_MSG_MODEL: the quads as a display list; null if there are too many for one. */
    public static byte[] model(int id, List<Quad> quads) {
        int size = (3 + quads.size() * 4 * VERTEX_BYTES + 31) & ~31;
        if (quads.isEmpty() || size > Layout.ENT_DL_MAX) return null;
        ByteBuffer b = ByteBuffer.allocate(8 + size).order(ByteOrder.BIG_ENDIAN);
        b.putInt(id).putInt(size);
        b.put((byte) GX_QUADS).putShort((short) (quads.size() * 4));
        for (Quad q : quads)
            for (int k = 0; k < 4; k++) {
                for (int c = 0; c < 3; c++) b.putShort(fixed(q.pos[k][c], POS_FRAC));
                b.putInt(q.rgba);
                b.putShort(fixed(q.uv[k][0], ST_FRAC)).putShort(fixed(q.uv[k][1], ST_FRAC));
            }
        return b.array(); // the rest stays 0: GX_NOP
    }

    /** GXC_MSG_ENTITIES: the first ENT_MAX pieces. */
    public static byte[] frame(List<Piece> pieces) {
        int n = Math.min(pieces.size(), Layout.ENT_MAX);
        // Galaxy pieces are in universe units: the game has them from its floating origin. One held in
        // Steve's hand is from his hand.
        org.joml.Vector3d o = dev.moui.galaxycraft.universe.GameOrigin.offset();
        ByteBuffer b = ByteBuffer.allocate(4 + n * Layout.ENT_BYTES).order(ByteOrder.BIG_ENDIAN);
        b.putInt(n);
        for (int i = 0; i < n; i++) {
            Piece p = pieces.get(i);
            b.putShort((short) p.model).putShort((short) p.skin).putInt(p.overlay).putInt(p.tint);
            boolean held = (p.model & HELD) != 0;
            for (int k = 0; k < 12; k++)
                b.putFloat((float) (held || k % 4 != 3 ? p.mtx[k] : p.mtx[k] - (k == 3 ? o.x : k == 7 ? o.y : o.z)));
        }
        return b.array();
    }

    private static short fixed(double v, int scale) {
        return (short) Math.clamp(Math.round(v * scale), Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /** Minecraft's face shades by the direction a face looks (model y points down): top, bottom, z, x. */
    public static int shade(double nx, double ny, double nz) {
        double s = ny < -0.5 ? 1.0 : ny > 0.5 ? 0.5 : Math.abs(nz) > 0.5 ? 0.8 : 0.6;
        int v = (int) Math.round(255 * s);
        return v << 24 | v << 16 | v << 8 | 0xFF;
    }

    /** Model ids with this bit are drawn facing the camera (particles): only their scale is kept. */
    public static final int BILLBOARD = 0x8000;
    /** Model bit: held in Steve's hand, its matrix from Minecraft's hand frame (not the galaxy). */
    public static final int HELD = 0x4000;

    /** A square 16 pixels a side around the origin (y down), showing (u0, v0) to (u1, v1), unshaded. */
    public static List<Quad> square(float u0, float v0, float u1, float v1) {
        return List.of(new Quad(new float[][] {{-8, -8, 0}, {8, -8, 0}, {8, 8, 0}, {-8, 8, 0}},
                new float[][] {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}}, 0xFFFFFFFF));
    }

    /**
     * A cube 16 pixels a side around the origin (model y down, as Minecraft's models) textured by
     * a 16-wide skin of bands, each 16 tall: top, sides and bottom are bands 0, 1 and 2 of bands.
     */
    public static List<Quad> cube(int bands) {
        List<Quad> out = new ArrayList<>();
        float t = 1f / bands;
        float[] top = {0, t}, side = {t, 2 * t}, bottom = {2 * t, 3 * t};
        // Up is -y. Each face: corners counterclockwise seen from outside; uv (u, v) per corner.
        out.add(face(new float[][] {{-8, -8, -8}, {-8, -8, 8}, {8, -8, 8}, {8, -8, -8}}, top, 0, -1, 0));
        out.add(face(new float[][] {{-8, 8, 8}, {-8, 8, -8}, {8, 8, -8}, {8, 8, 8}}, bottom, 0, 1, 0));
        out.add(face(new float[][] {{8, -8, -8}, {-8, -8, -8}, {-8, 8, -8}, {8, 8, -8}}, side, 0, 0, -1));
        out.add(face(new float[][] {{-8, -8, 8}, {8, -8, 8}, {8, 8, 8}, {-8, 8, 8}}, side, 0, 0, 1));
        out.add(face(new float[][] {{-8, -8, -8}, {-8, -8, 8}, {-8, 8, 8}, {-8, 8, -8}}, side, -1, 0, 0));
        out.add(face(new float[][] {{8, -8, 8}, {8, -8, -8}, {8, 8, -8}, {8, 8, 8}}, side, 1, 0, 0));
        return out;
    }

    private static Quad face(float[][] p, float[] band, double nx, double ny, double nz) {
        float[][] uv = {{0, band[0]}, {1, band[0]}, {1, band[1]}, {0, band[1]}};
        return new Quad(p, uv, shade(nx, ny, nz));
    }

    /**
     * Minecraft's flat item (item/generated): the 16x16 sprite (skin band 0 of bands) on both
     * sides of a slab one pixel thick around the origin, x right and y down, and its opaque
     * texels' edges as thin walls, one run of them per row or column and side.
     */
    public static List<Quad> flatItem(int[] argb, int bands) {
        List<Quad> out = new ArrayList<>();
        float t = 1f / bands, h = 0.5f;
        out.add(new Quad(new float[][] {{-8, -8, h}, {8, -8, h}, {8, 8, h}, {-8, 8, h}},
                new float[][] {{0, 0}, {1, 0}, {1, t}, {0, t}}, shade(0, 0, 1)));
        out.add(new Quad(new float[][] {{8, -8, -h}, {-8, -8, -h}, {-8, 8, -h}, {8, 8, -h}},
                new float[][] {{1, 0}, {0, 0}, {0, t}, {1, t}}, shade(0, 0, -1)));
        // Walls: dir 0 above a texel, 1 below, 2 left of it, 3 right of it.
        int[][] step = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int dir = 0; dir < 4; dir++) {
            boolean rows = dir < 2;
            for (int line = 0; line < 16; line++) {
                int run = -1;
                for (int s = 0; s <= 16; s++) {
                    int x = rows ? s : line, y = rows ? line : s;
                    boolean edge = s < 16 && opaque(argb, x, y) && !opaque(argb, x + step[dir][0], y + step[dir][1]);
                    if (edge && run < 0) run = s;
                    if (!edge && run >= 0) {
                        out.add(wall(dir, line, run, s, t));
                        run = -1;
                    }
                }
            }
        }
        return out;
    }

    private static boolean opaque(int[] argb, int x, int y) {
        return x >= 0 && y >= 0 && x < 16 && y < 16 && argb[y * 16 + x] >>> 24 != 0;
    }

    /** The wall on side dir of texels from to to (exclusive) of a row or column. */
    private static Quad wall(int dir, int line, int from, int to, float t) {
        float h = 0.5f;
        float a = from - 8, b = to - 8;
        float[][] p;
        float[][] uv;
        float v0 = (line + 0.5f) / 16 * t, u0 = from / 16f, u1 = to / 16f;
        if (dir < 2) {
            float y = line - 8 + (dir == 0 ? 0 : 1);
            p = new float[][] {{a, y, -h}, {b, y, -h}, {b, y, h}, {a, y, h}};
            uv = new float[][] {{u0, v0}, {u1, v0}, {u1, v0}, {u0, v0}};
            return new Quad(p, uv, shade(0, dir == 0 ? -1 : 1, 0));
        }
        float x = line - 8 + (dir == 2 ? 0 : 1);
        float uc = (line + 0.5f) / 16, w0 = from / 16f * t, w1 = to / 16f * t;
        p = new float[][] {{x, a, -h}, {x, b, -h}, {x, b, h}, {x, a, h}};
        uv = new float[][] {{uc, w0}, {uc, w1}, {uc, w1}, {uc, w0}};
        return new Quad(p, uv, shade(dir == 2 ? -1 : 1, 0, 0));
    }
}
