package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.view.HeldItem;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * The block atlas the game draws planets with: 16×16 tiles in a power-of-two texture (at least
 * 4×4 tiles, at most 64 across), as GX RGB5A3 with LEVELS mipmaps, each level averaging every tile
 * within itself (no tile bleeds into its neighbor; holes do not darken the colors around them).
 */
public final class Atlas {
    public static final int TILE = PlanetMesher.TILE_TEXELS, LEVELS = 4, MAX_COLUMNS = 64;

    public final int columns, rows;
    /** All levels, largest first, each GX RGB5A3: 4×4 texel blocks, big-endian. */
    public final byte[] data;

    private Atlas(int columns, int rows, byte[] data) {
        this.columns = columns;
        this.rows = rows;
        this.data = data;
    }

    public int width() {
        return columns * TILE;
    }

    public int height() {
        return rows * TILE;
    }

    /** The smallest atlas for this many tiles: columns and rows, powers of two, columns first to grow. */
    public static int[] size(int tiles) {
        int w = 4, h = 4;
        while (w * h < tiles) {
            if (w <= h && w < MAX_COLUMNS) w *= 2;
            else h *= 2;
        }
        if (h > MAX_COLUMNS) throw new IllegalArgumentException(tiles + " tiles do not fit a 1024x1024 atlas");
        return new int[] {w, h};
    }

    /** Tiles (16×16 ARGB, row 0 at the top) in order, left to right and top to bottom. */
    public static Atlas of(List<int[]> tiles) {
        int[] wh = size(tiles.size());
        int w = wh[0] * TILE, h = wh[1] * TILE;
        int[] level = new int[w * h];
        for (int t = 0; t < tiles.size(); t++) {
            int[] tile = tiles.get(t);
            int x0 = t % wh[0] * TILE, y0 = t / wh[0] * TILE;
            for (int y = 0; y < TILE; y++) System.arraycopy(tile, y * TILE, level, (y0 + y) * w + x0, TILE);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int l = 0; l < LEVELS; l++) {
            out.writeBytes(rgb5a3(level, w >> l, h >> l));
            if (l + 1 < LEVELS) level = half(level, w >> l, h >> l);
        }
        return new Atlas(wh[0], wh[1], out.toByteArray());
    }

    /**
     * Half the size, each texel the average of four (tiles are a power of two wide, so the four
     * never straddle two tiles): alpha averaged, colors weighted by it.
     */
    static int[] half(int[] src, int w, int h) {
        int hw = w / 2, hh = h / 2;
        int[] out = new int[hw * hh];
        for (int y = 0; y < hh; y++)
            for (int x = 0; x < hw; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                for (int k = 0; k < 4; k++) {
                    int p = src[(2 * y + (k >> 1)) * w + 2 * x + (k & 1)];
                    int pa = p >>> 24;
                    a += pa;
                    r += (long) pa * (p >> 16 & 0xFF);
                    g += (long) pa * (p >> 8 & 0xFF);
                    b += (long) pa * (p & 0xFF);
                }
                out[y * hw + x] = a == 0 ? 0
                        : (int) (a / 4) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        return out;
    }

    static byte[] rgb5a3(int[] argb, int w, int h) {
        byte[] out = new byte[w * h * 2];
        int i = 0;
        for (int by = 0; by < h; by += 4)
            for (int bx = 0; bx < w; bx += 4)
                for (int y = by; y < by + 4; y++)
                    for (int x = bx; x < bx + 4; x++) {
                        short v = HeldItem.rgb5a3(argb[y * w + x]);
                        out[i++] = (byte) (v >> 8);
                        out[i++] = (byte) v;
                    }
        return out;
    }
}
