package dev.moui.galaxycraft.voxel;

import java.util.ArrayList;
import java.util.List;

/** Models made of boxes: the cubes of the tests, and blocks whose model Minecraft draws apart (chests). */
public final class BoxModel {
    // Per cell side (TOP, BOTTOM, I_MINUS = north, I_PLUS = south, J_MINUS = west, J_PLUS = east):
    // the corners of the unit cube's face as 0/1 picks of (x, y, z), counter-clockwise from outside,
    // bottom-left first.
    private static final int[][][] FACE = {
            {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
            {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}}};

    private BoxModel() {}

    /** A box's six faces (bounds as in {@link BlockInfo#boxes}), tiles by top, sides and bottom. */
    public static List<ModelQuad> box(double[] b, int top, int side, int bottom, int tint) {
        List<ModelQuad> out = new ArrayList<>();
        for (int s = 0; s < 6; s++) {
            float[] pos = new float[12], uv = new float[8];
            for (int v = 0; v < 4; v++) {
                double x = FACE[s][v][0] == 0 ? b[0] : b[3];
                double y = FACE[s][v][1] == 0 ? b[1] : b[4];
                double z = FACE[s][v][2] == 0 ? b[2] : b[5];
                pos[3 * v] = (float) x;
                pos[3 * v + 1] = (float) y;
                pos[3 * v + 2] = (float) z;
                // Minecraft's default face UVs: what of the texture lies over that part of the cell.
                double[] st = switch (s) {
                    case CubeSphere.TOP -> new double[] {x, z};
                    case CubeSphere.BOTTOM -> new double[] {x, 1 - z};
                    case CubeSphere.I_MINUS -> new double[] {1 - x, 1 - y};
                    case CubeSphere.I_PLUS -> new double[] {x, 1 - y};
                    case CubeSphere.J_MINUS -> new double[] {z, 1 - y};
                    default -> new double[] {1 - z, 1 - y};
                };
                uv[2 * v] = (float) st[0];
                uv[2 * v + 1] = (float) st[1];
            }
            boolean onBoundary = switch (s) {
                case CubeSphere.TOP -> b[4] >= 1;
                case CubeSphere.BOTTOM -> b[1] <= 0;
                case CubeSphere.I_MINUS -> b[2] <= 0;
                case CubeSphere.I_PLUS -> b[5] >= 1;
                case CubeSphere.J_MINUS -> b[0] <= 0;
                default -> b[3] >= 1;
            };
            int tile = s == CubeSphere.TOP ? top : s == CubeSphere.BOTTOM ? bottom : side;
            out.add(new ModelQuad(pos, uv, tile, tint, onBoundary ? s : -1));
        }
        return out;
    }
}
