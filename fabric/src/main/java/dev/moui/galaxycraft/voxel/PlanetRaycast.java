package dev.moui.galaxycraft.voxel;

import org.joml.Vector3d;

/**
 * What the crosshair points at on a planet: the block hit and the cell before it. As in Minecraft
 * it goes through fluids, unless it is an empty bucket's, which stops at their sources; it stops
 * on anything with an outline, flowers included.
 */
public final class PlanetRaycast {
    public static final double STEP = 0.05;

    /**
     * hit: first targetable cell; before: the last cell crossed that is not (where a block goes),
     * or -1; point: where the ray entered hit, planet blocks.
     */
    public record Hit(int hit, int before, Vector3d point) {}

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
            if (b.targetable() || (sources && b.isFluid() && b.level() == Fluids.SOURCE))
                return new Hit(c, before, new Vector3d(dir).mul(Math.max(0, t - STEP / 2)).add(origin));
            before = c;
        }
        return null;
    }
}
