package dev.moui.galaxycraft.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The edges Minecraft draws as a block's outline (VoxelShape.forAllEdges): those of the union of
 * its shape's boxes, not of each box. The boxes cut space into a grid at their coordinates; along
 * each axis, a grid line is an edge where the four cells around it are not all alike and do not
 * make a flat face (one or three of them inside, or two across a diagonal), and touching pieces of
 * one line join into one edge. So stairs show their L and their step, a fence its post and bars,
 * and no line runs across a face.
 */
public final class OutlineEdges {
    private OutlineEdges() {}

    /** Boxes (minX, minY, minZ, maxX, maxY, maxZ in block space) to edges (x0, y0, z0, x1, y1, z1). */
    public static List<double[]> of(List<double[]> boxes) {
        double[][] at = new double[3][];
        for (int a = 0; a < 3; a++) {
            TreeSet<Double> cuts = new TreeSet<>();
            for (double[] b : boxes)
                if (b[a + 3] > b[a]) {
                    cuts.add(b[a]);
                    cuts.add(b[a + 3]);
                }
            at[a] = cuts.stream().mapToDouble(Double::doubleValue).toArray();
        }
        List<double[]> out = new ArrayList<>();
        if (at[0].length < 2 || at[1].length < 2 || at[2].length < 2) return out;
        int[] n = {at[0].length - 1, at[1].length - 1, at[2].length - 1};
        boolean[][][] full = new boolean[n[0]][n[1]][n[2]];
        for (int i = 0; i < n[0]; i++)
            for (int j = 0; j < n[1]; j++)
                for (int k = 0; k < n[2]; k++) {
                    double x = (at[0][i] + at[0][i + 1]) / 2, y = (at[1][j] + at[1][j + 1]) / 2, z = (at[2][k] + at[2][k + 1]) / 2;
                    for (double[] b : boxes)
                        if (x > b[0] && x < b[3] && y > b[1] && y < b[4] && z > b[2] && z < b[5]) {
                            full[i][j][k] = true;
                            break;
                        }
                }
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3; // the two axes across the line
            for (int gu = 0; gu <= n[u]; gu++)
                for (int gv = 0; gv <= n[v]; gv++) {
                    int start = -1;
                    for (int s = 0; s <= n[axis]; s++) {
                        boolean edge = s < n[axis] && edge(full, n, axis, u, v, s, gu, gv);
                        if (edge && start < 0) start = s;
                        if (!edge && start >= 0) {
                            double[] e = new double[6];
                            e[axis] = at[axis][start];
                            e[axis + 3] = at[axis][s];
                            e[u] = e[u + 3] = at[u][gu];
                            e[v] = e[v + 3] = at[v][gv];
                            out.add(e);
                            start = -1;
                        }
                    }
                }
        }
        return out;
    }

    /** Whether the grid line along axis at (gu, gv), in its piece s, is an edge. */
    private static boolean edge(boolean[][][] full, int[] n, int axis, int u, int v, int s, int gu, int gv) {
        // The four cells around the line: (du, dv) in {-1, 0}^2 off the grid point.
        boolean a = cell(full, n, axis, u, v, s, gu - 1, gv - 1), b = cell(full, n, axis, u, v, s, gu, gv - 1);
        boolean c = cell(full, n, axis, u, v, s, gu - 1, gv), d = cell(full, n, axis, u, v, s, gu, gv);
        int count = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0);
        return count == 1 || count == 3 || count == 2 && a == d;
    }

    private static boolean cell(boolean[][][] full, int[] n, int axis, int u, int v, int s, int cu, int cv) {
        if (cu < 0 || cv < 0 || cu >= n[u] || cv >= n[v]) return false;
        int[] idx = new int[3];
        idx[axis] = s;
        idx[u] = cu;
        idx[v] = cv;
        return full[idx[0]][idx[1]][idx[2]];
    }
}
