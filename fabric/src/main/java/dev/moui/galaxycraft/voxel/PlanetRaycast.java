package dev.moui.galaxycraft.voxel;

import org.joml.Vector3d;

/**
 * What the crosshair points at on a planet: the block hit and the cell before it. As in Minecraft
 * it goes through fluids, unless it is an empty bucket's, which stops at their sources; it stops
 * on anything with an outline, flowers included, where the ray meets that outline's boxes (it
 * passes over a slab's empty half and beside a torch).
 */
public final class PlanetRaycast {
    public static final double STEP = 0.05;
    /** Slack in model space: a ray along a box's side (a cell edge) still meets it. */
    private static final double EPS = 1e-6;
    /** By model axis (x, y, z) and end (min, max): the cell side the ray enters through. */
    private static final int[][] SIDE = {{CubeSphere.J_MINUS, CubeSphere.J_PLUS}, {CubeSphere.BOTTOM, CubeSphere.TOP},
            {CubeSphere.I_MINUS, CubeSphere.I_PLUS}};

    /**
     * hit: first targetable cell; before: the last cell crossed that is not (where a block goes),
     * or -1; point: where the ray entered hit's shape, planet blocks; face: the cell side of the
     * shape it entered through (as the block's model faces, a slab's top is TOP).
     */
    public record Hit(int hit, int before, Vector3d point, int face) {}

    private PlanetRaycast() {}

    /** origin and unit dir in planet blocks (origin = center). Null if nothing within reach. */
    public static Hit cast(VoxelPlanet planet, Vector3d origin, Vector3d dir, double reach) {
        return cast(planet, origin, dir, reach, false);
    }

    /** sources: fluid sources count as hit too (an empty bucket's ray). */
    public static Hit cast(VoxelPlanet planet, Vector3d origin, Vector3d dir, double reach, boolean sources) {
        int before = -1;
        Vector3d p = new Vector3d();
        for (double t = 0; t <= reach; t += STEP) {
            p.set(dir).mul(t).add(origin);
            int c = planet.grid.cellAt(p);
            if (c < 0) continue;
            BlockInfo b = planet.info(c);
            if (sources && b.isFluid() && b.level() == Fluids.SOURCE)
                return new Hit(c, before, new Vector3d(dir).mul(Math.max(0, t - STEP / 2)).add(origin), CubeSphere.TOP);
            if (b.targetable()) {
                // The ray through the cell, from the sample before it to the first after: nearly
                // straight in its model space, where the shape's boxes are plain boxes.
                double t0 = Math.max(0, t - STEP), t1 = t;
                while (t1 <= reach + STEP && planet.grid.cellAt(new Vector3d(dir).mul(t1 + STEP).add(origin)) == c) t1 += STEP;
                t1 += STEP;
                Vector3d a = CellSpace.local(planet.grid, c, new Vector3d(dir).mul(t0).add(origin));
                Vector3d e = CellSpace.local(planet.grid, c, new Vector3d(dir).mul(t1).add(origin));
                double[] in = clip(b.shape(), a, e);
                if (in != null) {
                    double th = t0 + in[0] * (t1 - t0);
                    if (th <= reach) return new Hit(c, before, new Vector3d(dir).mul(th).add(origin), (int) in[1]);
                    return null;
                }
                t = t1 - STEP; // missed its shape: on past the cell
            }
            before = c;
        }
        return null;
    }

    /**
     * Where the segment a to e (model space) first enters one of boxes: {fraction along it, cell
     * side entered}, or null. A segment starting inside a box enters it at 0 through the nearest side.
     */
    static double[] clip(java.util.List<double[]> boxes, Vector3d a, Vector3d e) {
        double[] best = null;
        double[] from = {a.x, a.y, a.z}, d = {e.x - a.x, e.y - a.y, e.z - a.z};
        for (double[] box : boxes) {
            double lo = 0, hi = 1;
            int side = -1;
            boolean miss = false;
            for (int ax = 0; ax < 3 && !miss; ax++) {
                double min = box[ax] - EPS, max = box[ax + 3] + EPS;
                if (Math.abs(d[ax]) < 1e-12) {
                    if (from[ax] < min || from[ax] > max) miss = true;
                    continue;
                }
                double ta = (min - from[ax]) / d[ax], tb = (max - from[ax]) / d[ax];
                int enter = d[ax] > 0 ? SIDE[ax][0] : SIDE[ax][1];
                if (ta > tb) {
                    double s = ta;
                    ta = tb;
                    tb = s;
                }
                if (ta > lo) {
                    lo = ta;
                    side = enter;
                }
                hi = Math.min(hi, tb);
                if (lo > hi) miss = true;
            }
            if (miss) continue;
            if (side < 0) side = nearestSide(box, from);
            if (best == null || lo < best[0]) best = new double[] {lo, side};
        }
        return best;
    }

    /** The side of box nearest to model point m (inside it). */
    private static int nearestSide(double[] box, double[] m) {
        int side = CubeSphere.TOP;
        double near = Double.MAX_VALUE;
        for (int ax = 0; ax < 3; ax++)
            for (int end = 0; end < 2; end++) {
                double dist = Math.abs(m[ax] - box[ax + 3 * end]);
                if (dist < near) {
                    near = dist;
                    side = SIDE[ax][end];
                }
            }
        return side;
    }
}
