package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.CubeSphere;
import org.joml.Vector3d;

/**
 * Where a planet's density is worked out, as 1.7 does: on a coarse lattice (m × m columns a face,
 * a level every ls layers), the cells in between interpolated. The builder fills a face's lattice
 * at once; the far view only the corners of the columns it looks at. Both read the same numbers,
 * so a far view stands exactly where the blocks will. A face's edge points are the same directions
 * as the next face's: faces meet without seams.
 */
final class Lattice {
    final Density density;
    final CubeSphere grid;
    final int m, ls, levels, depth;

    Lattice(Density density, CubeSphere grid) {
        this.density = density;
        this.grid = grid;
        Density.Scale s = density.scale();
        depth = s.depth();
        m = Math.max(2, (int) Math.ceil(grid.n / Math.max(2.0, 4 * s.hs())));
        ls = Math.max(1, (int) Math.round(8 * s.v()));
        levels = (grid.layers + ls - 1) / ls + 1;
    }

    /** The density at lattice column (a, b) of face f, at each level. */
    double[] column(int f, int a, int b) {
        Vector3d d = PlanetGenerator.dirAt(grid, f, (double) a * grid.n / m, (double) b * grid.n / m);
        Density.Column c = density.column(d);
        double[] out = new double[levels];
        for (int l = 0; l < levels; l++) out[l] = density.at(d, c, l * ls + 0.5 - depth);
        return out;
    }

    /** The lattice column a grid column (i, j) lies in: {a, b}, and how far across it, fa and fb. */
    double[] place(int i, int j) {
        double x = (i + 0.5) * m / grid.n, y = (j + 0.5) * m / grid.n;
        int a = Math.min((int) x, m - 1), b = Math.min((int) y, m - 1);
        return new double[] {a, b, x - a, y - b};
    }

    /** Whether cell k of a column is ground, from its lattice column's four corners (00, 10, 01, 11). */
    boolean solid(double[] c00, double[] c10, double[] c01, double[] c11, double fa, double fb, int k) {
        if (k < 2) return true; // the bedrock and the block over it
        int l = Math.min(k / ls, levels - 2);
        double fl = (k - l * ls) / (double) ls;
        return lerp(fl, at(c00, c10, c01, c11, fa, fb, l), at(c00, c10, c01, c11, fa, fb, l + 1)) > 0;
    }

    private static double at(double[] c00, double[] c10, double[] c01, double[] c11, double fa, double fb, int l) {
        return lerp(fb, lerp(fa, c00[l], c10[l]), lerp(fa, c01[l], c11[l]));
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
