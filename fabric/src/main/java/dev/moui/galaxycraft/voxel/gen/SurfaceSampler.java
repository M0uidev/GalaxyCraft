package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import dev.moui.galaxycraft.voxel.gen.TerrainNoise.Field;
import org.joml.Vector3d;

/**
 * A generated planet's ground in any direction from its center: the climate its noises give
 * there, the biome and the height. What PlanetGenerator builds every column with, and what a far
 * view of a planet never built is sampled from.
 */
public final class SurfaceSampler {
    /** Ground in a direction: blocks above the base surface (negative below), and its biome. */
    public record Column(int height, String biome) {}

    private final TerrainNoise noise;
    private final BiomeTable table;
    private final String fixed;
    private final Climate.Span span;
    private final boolean water;
    private final int radius, air, depth, lowest;
    private final double climateScale, relief;

    public SurfaceSampler(PlanetBlueprint bp, TerrainNoise noise, BiomeTable table) {
        this.noise = noise;
        this.table = table;
        radius = bp.radius();
        air = bp.air();
        depth = VoxelPlanet.groundDepth(radius);
        fixed = bp.biomeSize() == 0 ? PlanetGenerator.biome(bp, table) : null;
        water = bp.water();
        Climate.Span s = fixed == null ? (water ? PlanetGenerator.EVERYWHERE : PlanetGenerator.INLAND) : table.span(fixed);
        if (s == null) throw new IllegalArgumentException("no biome " + fixed);
        if (fixed != null && water && table.watery(fixed)) {
            Climate m = s.max();
            s = new Climate.Span(s.min(), new Climate(Math.max(m.continentalness(), PlanetGenerator.ISLANDS), m.erosion(),
                    m.ridges(), m.temperature(), m.humidity()));
        }
        span = s;
        lowest = water ? Math.min(depth - 2, PlanetGenerator.MAX_WATER_DEPTH) : depth - 2;
        climateScale = bp.biomeSize() == 0 ? 0 : 256.0 / bp.biomeSize();
        relief = Math.min(1, radius / PlanetGenerator.FULL_RELIEF_RADIUS);
    }

    /** Blocks of ground under the base surface (VoxelPlanet.groundDepth). */
    public int depth() {
        return depth;
    }

    /** The grid a planet of this blueprint has (as PlanetGenerator builds it). */
    public CubeSphere grid() {
        return new CubeSphere(VoxelPlanet.gridSize(radius), radius - depth, depth + air);
    }

    public boolean water() {
        return water;
    }

    /** The direction through the middle of a column of the grid (unit length). */
    public static Vector3d columnDir(CubeSphere grid, int f, int i, int j) {
        return grid.dir(f, i, j).add(grid.dir(f, i + 1, j)).add(grid.dir(f, i, j + 1)).add(grid.dir(f, i + 1, j + 1)).normalize();
    }

    /** The ground in that direction (unit length). Safe from several threads. */
    public Column at(Vector3d dir) {
        Vector3d p = new Vector3d(dir).mul(radius);
        double tx = p.x * PlanetGenerator.TERRAIN_SCALE, ty = p.y * PlanetGenerator.TERRAIN_SCALE, tz = p.z * PlanetGenerator.TERRAIN_SCALE;
        double cx = p.x * climateScale, cy = p.y * climateScale, cz = p.z * climateScale;
        Climate c = span.map(new Climate(noise.value(Field.CONTINENTALNESS, tx, ty, tz), noise.value(Field.EROSION, tx, ty, tz),
                noise.value(Field.RIDGES, tx, ty, tz), noise.value(Field.TEMPERATURE, cx, cy, cz), noise.value(Field.HUMIDITY, cx, cy, cz)));
        String biome = fixed != null ? fixed : table.find(c, water);
        double h = TerrainShaper.height(c) * relief;
        return new Column((int) Math.round(h > 0 ? soft(h, air - 4) : -soft(-h, lowest)), biome);
    }

    /** lim·tanh(h/lim): h itself near 0, never past lim. */
    private static double soft(double h, int lim) {
        return lim <= 0 ? 0 : lim * Math.tanh(h / lim);
    }
}
