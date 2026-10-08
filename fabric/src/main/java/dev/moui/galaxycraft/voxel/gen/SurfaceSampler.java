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
    private final Lattice lattice;
    /** Lattice columns worked out so far, by face and place: next patches share their corners. */
    private final java.util.Map<Integer, double[]> nodes = new java.util.concurrent.ConcurrentHashMap<>();

    public SurfaceSampler(PlanetBlueprint bp) {
        density = new Density(bp);
        radius = bp.radius();
        water = bp.water();
        lattice = new Lattice(density, grid());
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

    /**
     * The ground of column (i, j) of face f, as the planet will be built (caves aside): its highest
     * ground and its biome. Safe from several threads.
     */
    public Column at(int f, int i, int j) {
        double[] at = lattice.place(i, j);
        int a = (int) at[0], b = (int) at[1];
        double[] c00 = node(f, a, b), c10 = node(f, a + 1, b), c01 = node(f, a, b + 1), c11 = node(f, a + 1, b + 1);
        CubeSphere g = lattice.grid;
        int k = g.layers - 1;
        while (k > 0 && !lattice.solid(c00, c10, c01, c11, at[2], at[3], k)) k--;
        return new Column(k - (lattice.depth - 1), density.layout().at(columnDir(g, f, i, j)).id());
    }

    private double[] node(int f, int a, int b) {
        return nodes.computeIfAbsent((f * (lattice.m + 1) + a) * (lattice.m + 1) + b, key -> lattice.column(f, a, b));
    }
}
