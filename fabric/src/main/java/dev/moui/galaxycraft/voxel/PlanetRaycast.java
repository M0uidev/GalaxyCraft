package dev.moui.galaxycraft.voxel;

import org.joml.Vector3d;

/** What the crosshair points at on a planet: the solid cell hit and the empty cell before it. */
public final class PlanetRaycast {
    public static final double STEP = 0.05;

    /** hit: first solid cell; before: the last empty cell crossed (where a block goes), or -1. */
    public record Hit(int hit, int before) {}

    private PlanetRaycast() {}

    /** origin and unit dir in planet blocks (origin = center). Null if nothing within reach. */
    public static Hit cast(VoxelPlanet planet, Vector3d origin, Vector3d dir, double reach) {
        int before = -1;
        Vector3d p = new Vector3d();
        for (double t = 0; t <= reach; t += STEP) {
            p.set(dir).mul(t).add(origin);
            int c = planet.grid.cellAt(p);
            if (c < 0) continue;
            if (planet.get(c).solid()) return new Hit(c, before);
            before = c;
        }
        return null;
    }
}
