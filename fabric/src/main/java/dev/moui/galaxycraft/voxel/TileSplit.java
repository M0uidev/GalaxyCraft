package dev.moui.galaxycraft.voxel;

import java.util.ArrayList;
import java.util.List;

/**
 * A face textured from a big texture (a chest's 64×64), cut where the texture's 16×16 cells meet:
 * the planet atlas holds 16×16 tiles, so each piece samples one cell. The face is a rectangle in
 * its own corners' terms (corner 0 to 1 is one side, 0 to 3 the other), and its texture runs along
 * those sides, as every cube face of Minecraft's models does (u along one side, v along the other,
 * either way round, mirrored or not).
 */
public final class TileSplit {
    private static final int CELL = 16;

    private TileSplit() {}

    /**
     * A piece of the face: its four corners (x, y, z, in the face's order), their texture
     * coordinates within the cell (0 to 1) and the cell (column, row) of the texture it samples.
     */
    public record Piece(float[] pos, float[] uv, int cellX, int cellY) {}

    /**
     * pos: four corners (12 floats); uv: their texture coordinates in the texture's pixels (8
     * floats). The pieces cover the face; none if the face samples no area of the texture.
     */
    public static List<Piece> split(float[] pos, float[] uv) {
        // The face's sides: s from corner 0 to 1, t from corner 0 to 3; u and v change along them.
        float us = uv[2] - uv[0], ut = uv[6] - uv[0], vs = uv[3] - uv[1], vt = uv[7] - uv[1];
        boolean uAlongS = Math.abs(us) + Math.abs(vt) >= Math.abs(ut) + Math.abs(vs);
        float du = uAlongS ? us : ut, dv = uAlongS ? vt : vs;
        if (du == 0 || dv == 0) return List.of();
        float[] cutsU = cuts(uv[0], du), cutsV = cuts(uv[1], dv);
        List<Piece> out = new ArrayList<>();
        for (int i = 0; i + 1 < cutsU.length; i++)
            for (int j = 0; j + 1 < cutsV.length; j++) {
                // The piece's own sides, as fractions of the face's: a along u's side, b along v's.
                float a0 = cutsU[i], a1 = cutsU[i + 1], b0 = cutsV[j], b1 = cutsV[j + 1];
                float uMid = uv[0] + du * (a0 + a1) / 2, vMid = uv[1] + dv * (b0 + b1) / 2;
                int cx = (int) Math.floor(uMid / CELL), cy = (int) Math.floor(vMid / CELL);
                float[] p = new float[12], t = new float[8];
                // Corners in the face's order: (s, t) = (0,0), (1,0), (1,1), (0,1).
                float[][] st = uAlongS
                        ? new float[][] {{a0, b0}, {a1, b0}, {a1, b1}, {a0, b1}}
                        : new float[][] {{b0, a0}, {b1, a0}, {b1, a1}, {b0, a1}};
                for (int k = 0; k < 4; k++) {
                    float s = st[k][0], q = st[k][1];
                    for (int c = 0; c < 3; c++)
                        p[3 * k + c] = lerp(lerp(pos[c], pos[3 + c], s), lerp(pos[9 + c], pos[6 + c], s), q);
                    float u = uv[0] + du * (uAlongS ? s : q), v = uv[1] + dv * (uAlongS ? q : s);
                    t[2 * k] = clamp01((u - cx * CELL) / CELL);
                    t[2 * k + 1] = clamp01((v - cy * CELL) / CELL);
                }
                out.add(new Piece(p, t, cx, cy));
            }
        return out;
    }

    /** Fractions 0..1 along a side where the texture coordinate, from start changing by d, crosses a cell's edge. */
    private static float[] cuts(float start, float d) {
        float end = start + d, lo = Math.min(start, end), hi = Math.max(start, end);
        List<Float> f = new ArrayList<>();
        f.add(0f);
        for (int k = (int) Math.floor(lo / CELL) + 1; k * CELL < hi; k++)
            if (k * CELL > lo) f.add((k * CELL - start) / d);
        f.add(1f);
        f.sort(null);
        float[] out = new float[f.size()];
        for (int i = 0; i < out.length; i++) out[i] = f.get(i);
        return out;
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static float clamp01(float x) {
        return Math.max(0, Math.min(1, x));
    }
}
