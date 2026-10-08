package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import org.joml.Vector3d;

/**
 * A generated planet's ground in any direction from its center: how high its highest ground is
 * and its biome, from the same density PlanetGenerator builds with. What a far view of a planet
 * never built is sampled from.
 */
public final class SurfaceSampler {
    /** Ground in a direction: blocks above the base surface (negative below), and its biome. */
    public record Column(int height, String biome) {}

    private final Density density;
    private final int radius;
    private final boolean water;

    public SurfaceSampler(PlanetBlueprint bp) {
        density = new Density(bp);
        radius = bp.radius();
        water = bp.water();
    }

    /** Blocks of ground under the base surface. */
    public int depth() {
        return density.scale().depth();
    }

    /** The grid a planet of this blueprint has (as PlanetGenerator builds it). */
    public CubeSphere grid() {
        Density.Scale s = density.scale();
        return new CubeSphere(VoxelPlanet.gridSize(radius), radius - s.depth(), s.depth() + s.air());
    }

    public boolean water() {
        return water;
    }

    /** The direction through the middle of a column of the grid (unit length). */
    public static Vector3d columnDir(CubeSphere grid, int f, int i, int j) {
        return PlanetGenerator.dirAt(grid, f, i + 0.5, j + 0.5);
    }

    /** The ground in that direction (unit length). Safe from several threads. */
    public Column at(Vector3d dir) {
        return new Column(density.top(dir), density.layout().at(dir).id());
    }
}
