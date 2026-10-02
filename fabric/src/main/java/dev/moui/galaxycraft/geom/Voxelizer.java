package dev.moui.galaxycraft.geom;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/**
 * Turns triangles (Minecraft space, gravity = -Y) into axis-aligned boxes on a 1/8-block grid,
 * the shape Minecraft's collision code understands. Slopes become micro-steps below the 0.6
 * step height; walls steeper than 55° are raised one block so step-up refuses them.
 */
public final class Voxelizer {
    public static final double CELL = 0.125;
    private static final double STEEP_COS = Math.cos(Math.toRadians(55));
    private static final double CEILING_NY = -0.2;
    private static final double EPS = 1e-9;

    private Voxelizer() {}

    /** box = {minX, minY, minZ, maxX, maxY, maxZ}; returns boxes in the same layout. */
    public static List<double[]> voxelize(List<Tri> tris, double[] box) {
        int x0 = (int) Math.floor(box[0] / CELL), y0 = (int) Math.floor(box[1] / CELL), z0 = (int) Math.floor(box[2] / CELL);
        int x1 = (int) Math.ceil(box[3] / CELL), y1 = (int) Math.ceil(box[4] / CELL), z1 = (int) Math.ceil(box[5] / CELL);
        int nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        if (nx <= 0 || ny <= 0 || nz <= 0) return List.of();
        byte[] grid = new byte[nx * ny * nz]; // 0 empty, 1 solid, 2 solid + raised wall

        for (Tri t : tris) {
            int cx0 = Math.max(x0, (int) Math.floor(min(t.a().x, t.b().x, t.c().x) / CELL));
            int cy0 = Math.max(y0, (int) Math.floor(min(t.a().y, t.b().y, t.c().y) / CELL));
            int cz0 = Math.max(z0, (int) Math.floor(min(t.a().z, t.b().z, t.c().z) / CELL));
            int cx1 = Math.min(x1 - 1, (int) Math.floor(max(t.a().x, t.b().x, t.c().x) / CELL));
            int cy1 = Math.min(y1 - 1, (int) Math.floor(max(t.a().y, t.b().y, t.c().y) / CELL));
            int cz1 = Math.min(z1 - 1, (int) Math.floor(max(t.a().z, t.b().z, t.c().z) / CELL));
            byte mark = (byte) (t.n().y < STEEP_COS && t.n().y > CEILING_NY ? 2 : 1);
            for (int ix = cx0; ix <= cx1; ix++)
                for (int iy = cy0; iy <= cy1; iy++)
                    for (int iz = cz0; iz <= cz1; iz++) {
                        int i = ((ix - x0) * ny + (iy - y0)) * nz + (iz - z0);
                        if (grid[i] >= mark) continue;
                        if (overlaps(t, (ix + 0.5) * CELL, (iy + 0.5) * CELL, (iz + 0.5) * CELL)) grid[i] = mark;
                    }
        }

        List<double[]> out = new ArrayList<>();
        for (int iy = 0; iy < ny; iy++)
            for (int iz = 0; iz < nz; iz++) {
                int ix = 0;
                while (ix < nx) {
                    byte m = grid[(ix * ny + iy) * nz + iz];
                    if (m == 0) { ix++; continue; }
                    int start = ix;
                    while (ix < nx && grid[(ix * ny + iy) * nz + iz] == m) ix++;
                    double minY = (y0 + iy) * CELL;
                    out.add(new double[] {(x0 + start) * CELL, minY, (z0 + iz) * CELL,
                            (x0 + ix) * CELL, minY + (m == 2 ? 1.0 : CELL), (z0 + iz + 1) * CELL});
                }
            }
        return out;
    }

    /** Separating-axis triangle/box test (Akenine-Möller) for a cell centered at (cx, cy, cz). */
    static boolean overlaps(Tri t, double cx, double cy, double cz) {
        double h = CELL / 2 + EPS;
        Vector3d v0 = new Vector3d(t.a()).sub(cx, cy, cz);
        Vector3d v1 = new Vector3d(t.b()).sub(cx, cy, cz);
        Vector3d v2 = new Vector3d(t.c()).sub(cx, cy, cz);
        Vector3d[] e = {new Vector3d(v1).sub(v0), new Vector3d(v2).sub(v1), new Vector3d(v0).sub(v2)};
        // 9 edge cross-product axes.
        for (Vector3d ed : e) {
            for (int axis = 0; axis < 3; axis++) {
                Vector3d a = switch (axis) {
                    case 0 -> new Vector3d(0, -ed.z, ed.y);
                    case 1 -> new Vector3d(ed.z, 0, -ed.x);
                    default -> new Vector3d(-ed.y, ed.x, 0);
                };
                double p0 = a.dot(v0), p1 = a.dot(v1), p2 = a.dot(v2);
                double r = h * (Math.abs(a.x) + Math.abs(a.y) + Math.abs(a.z));
                if (Math.min(p0, Math.min(p1, p2)) > r || Math.max(p0, Math.max(p1, p2)) < -r) return false;
            }
        }
        // Box face axes.
        if (min(v0.x, v1.x, v2.x) > h || max(v0.x, v1.x, v2.x) < -h) return false;
        if (min(v0.y, v1.y, v2.y) > h || max(v0.y, v1.y, v2.y) < -h) return false;
        if (min(v0.z, v1.z, v2.z) > h || max(v0.z, v1.z, v2.z) < -h) return false;
        // Triangle plane.
        Vector3d n = t.n();
        double d = n.dot(v0);
        double r = h * (Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z));
        return Math.abs(d) <= r;
    }

    private static double min(double a, double b, double c) {
        return Math.min(a, Math.min(b, c));
    }

    private static double max(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }
}
