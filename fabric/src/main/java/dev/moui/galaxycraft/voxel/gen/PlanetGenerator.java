package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import dev.moui.galaxycraft.voxel.gen.TerrainNoise.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.joml.Vector3d;

/**
 * Builds a generated planet: per column of the cube-sphere, Minecraft's noises sampled in 3D at
 * where that column points give a climate, the climate a biome and a height, the biome the
 * blocks. Neighboring columns sample neighboring points whatever face they are on, so the terrain
 * has no seams on the cube's edges or corners.
 */
public final class PlanetGenerator {
    /** Noise space per block for the terrain: eight times Minecraft's (blocks / 4), planets being small. */
    static final double TERRAIN_SCALE = 2;
    /** Rise from a neighbor that makes a column a cliff, its top bare stone. */
    static final int STEEP = 3;
    /** Below this a planet's relief is scaled down with its radius. */
    static final double FULL_RELIEF_RADIUS = 64;
    /** Continentalness from the coast inland: what several-biome planets without water use. */
    static final Climate.Span INLAND = new Climate.Span(new Climate(-0.11, -1, -1, -1, -1), new Climate(1, 1, 1, 1, 1));
    static final Climate.Span EVERYWHERE = new Climate.Span(new Climate(-1, -1, -1, -1, -1), new Climate(1, 1, 1, 1, 1));
    /** Deepest water, blocks: Mario walks its floor (no swimming yet) with his head out. */
    static final int MAX_WATER_DEPTH = 2;
    /** An ocean or river planet's continentalness reaches this far inland: it gets a few islands. */
    static final double ISLANDS = 0.2;

    private PlanetGenerator() {}

    /** The biome a one-biome blueprint gets: its own, or for "random" one of the land biomes by its seed. */
    public static String biome(PlanetBlueprint bp, BiomeTable table) {
        if (!PlanetBlueprint.RANDOM.equals(bp.biome())) return bp.biome();
        List<String> land = table.land();
        return land.get(new Random(bp.seed()).nextInt(land.size()));
    }

    /** A generated planet's cells, before they are a VoxelPlanet (that is made on the game's thread). */
    public record Cells(CubeSphere grid, int depth, char[] cells) {
        public VoxelPlanet planet(Blocks blocks) {
            return VoxelPlanet.of(grid, depth, cells, blocks);
        }
    }

    /** ids gives the planet's id for a block's text (Blocks.parse in the game). */
    public static VoxelPlanet build(PlanetBlueprint bp, TerrainNoise noise, BiomeTable table, Vegetation.Library plants,
            Blocks blocks, ToIntFunction<String> ids) {
        return cells(bp, noise, table, plants, ids).planet(blocks);
    }

    /** Every block a generated planet can be made of: what ids must know. */
    public static java.util.Set<String> blocks() {
        java.util.Set<String> all = new java.util.TreeSet<>(BiomeSurface.blocks());
        all.addAll(Underground.blocks());
        return all;
    }

    /** The cells alone: safe off the game's thread when ids only reads (see {@link #blocks()}). */
    public static Cells cells(PlanetBlueprint bp, TerrainNoise noise, BiomeTable table, Vegetation.Library plants,
            ToIntFunction<String> ids) {
        int radius = bp.radius(), air = bp.air(), depth = VoxelPlanet.groundDepth(radius);
        int n = VoxelPlanet.gridSize(radius);
        CubeSphere grid = new CubeSphere(n, radius - depth, depth + air);
        String fixed = bp.biomeSize() == 0 ? biome(bp, table) : null;
        boolean water = bp.water();
        Climate.Span span = fixed == null ? (water ? EVERYWHERE : INLAND) : table.span(fixed);
        if (span == null) throw new IllegalArgumentException("no biome " + fixed);
        if (fixed != null && water && table.watery(fixed)) {
            Climate m = span.max();
            span = new Climate.Span(span.min(), new Climate(Math.max(m.continentalness(), ISLANDS), m.erosion(), m.ridges(),
                    m.temperature(), m.humidity()));
        }
        int lowest = water ? Math.min(depth - 2, MAX_WATER_DEPTH) : depth - 2;
        double climateScale = bp.biomeSize() == 0 ? 0 : 256.0 / bp.biomeSize();
        double relief = Math.min(1, radius / FULL_RELIEF_RADIUS);

        int columns = 6 * n * n;
        int[] height = new int[columns];
        String[] biome = new String[columns];
        float[] dirs = new float[3 * columns];
        Climate.Span fspan = span;
        java.util.stream.IntStream.range(0, 6).parallel().forEach(f -> {
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) {
                    int col = (f * n + i) * n + j;
                    Vector3d p = grid.dir(f, i, j).add(grid.dir(f, i + 1, j)).add(grid.dir(f, i, j + 1))
                            .add(grid.dir(f, i + 1, j + 1)).normalize();
                    dirs[3 * col] = (float) p.x;
                    dirs[3 * col + 1] = (float) p.y;
                    dirs[3 * col + 2] = (float) p.z;
                    p.mul(radius);
                    double tx = p.x * TERRAIN_SCALE, ty = p.y * TERRAIN_SCALE, tz = p.z * TERRAIN_SCALE;
                    double cx = p.x * climateScale, cy = p.y * climateScale, cz = p.z * climateScale;
                    Climate c = fspan.map(new Climate(noise.value(Field.CONTINENTALNESS, tx, ty, tz),
                            noise.value(Field.EROSION, tx, ty, tz), noise.value(Field.RIDGES, tx, ty, tz),
                            noise.value(Field.TEMPERATURE, cx, cy, cz), noise.value(Field.HUMIDITY, cx, cy, cz)));
                    biome[col] = fixed != null ? fixed : table.find(c, water);
                    double h = TerrainShaper.height(c) * relief;
                    height[col] = (int) Math.round(h > 0 ? soft(h, air - 4) : -soft(-h, lowest));
                }
        });

        Map<String, int[]> palettes = new HashMap<>(); // cover, top, filler, stone ids by biome
        char[] cells = new char[grid.cellCount()];
        int bedrock = ids.applyAsInt("minecraft:bedrock"), waterId = ids.applyAsInt(BiomeSurface.WATER),
                ice = ids.applyAsInt(BiomeSurface.ICE), sea = depth - 1, stone = ids.applyAsInt(Underground.STONE),
                deepslate = ids.applyAsInt(Underground.DEEPSLATE), deepslateTop = (depth - 1) / 3;
        for (int col = 0; col < columns; col++) {
            int[] pal = palettes.computeIfAbsent(biome[col], b -> {
                BiomeSurface.Palette s = BiomeSurface.of(b);
                return new int[] {s.cover() == null ? Blocks.AIR : ids.applyAsInt(s.cover()), ids.applyAsInt(s.top()),
                        ids.applyAsInt(s.filler()), ids.applyAsInt(s.stone())};
            });
            int base = col * grid.layers, top = depth - 1 + height[col];
            boolean steep = steep(grid, n, height, col), wet = water && top < sea;
            cells[base] = (char) bedrock;
            for (int k = 1; k <= top; k++) {
                int below = top - k;
                int id = below == 0 ? (steep ? pal[3] : wet ? pal[2] : pal[1]) : below <= BiomeSurface.FILLER_DEPTH ? pal[2] : pal[3];
                cells[base + k] = (char) (id == stone && k <= deepslateTop ? deepslate : id);
            }
            if (wet) {
                for (int k = top + 1; k <= sea; k++) cells[base + k] = (char) waterId;
                if (BiomeSurface.frozen(biome[col])) cells[base + sea] = (char) ice;
            } else if (!steep && top + 1 < grid.layers) cells[base + top + 1] = (char) pal[0];
        }
        boolean[] keepRoof = new boolean[columns]; // wet, or beside water: caves there would drain it
        for (int col = 0; col < columns; col++) {
            if (!water || height[col] >= 0) continue;
            keepRoof[col] = true;
            for (int side = CubeSphere.I_MINUS; side <= CubeSphere.J_PLUS; side++) {
                int nb = grid.neighbor(col * grid.layers, side);
                if (nb >= 0) keepRoof[nb / grid.layers] = true;
            }
        }
        Underground.carve(grid, depth, cells, height, dirs, keepRoof, noise, bp.caves(), bp.entrances(), radius);
        // Ground a cave opened to the sky gets its biome's top, as Minecraft's carvers leave it:
        // dirt left bare there would turn to grass later, a block at a time, each meshed and sent.
        for (int col = 0; col < columns; col++) {
            int[] pal = palettes.get(biome[col]);
            if (pal[1] == pal[2]) continue;
            int base = col * grid.layers;
            for (int k = grid.layers - 2; k > 0; k--) {
                int id = cells[base + k];
                if (id == Blocks.AIR || id == pal[0]) continue;
                if (id == pal[2] && cells[base + k + 1] == Blocks.AIR) cells[base + k] = (char) pal[1];
                break; // the first ground from the sky down
            }
        }
        Underground.ores(grid, depth, cells, height, bp.seed(), bp.ores(), ids);
        if (bp.plants() > 0) {
            // Bare ground: its biome's top block, not under water, not a cliff, open above (or snow).
            boolean[] bare = new boolean[columns];
            int snow = ids.applyAsInt("minecraft:snow");
            for (int col = 0; col < columns; col++) {
                int base = col * grid.layers, top = depth - 1 + height[col];
                if (top + 1 >= grid.layers || keepRoof[col] && height[col] < 0) continue;
                int above = cells[base + top + 1];
                bare[col] = cells[base + top] == palettes.get(biome[col])[1] && (above == Blocks.AIR || above == snow);
            }
            Vegetation.plant(grid, depth, cells, height, biome, bare, plants, bp.seed(), bp.plants(), ids);
        }
        return new Cells(grid, depth, cells);
    }

    /** lim·tanh(h/lim): h itself near 0, never past lim. */
    private static double soft(double h, int lim) {
        return lim <= 0 ? 0 : lim * Math.tanh(h / lim);
    }

    /** Whether a column stands STEEP or more above any of its four neighbors (on its face or past an edge). */
    private static boolean steep(CubeSphere grid, int n, int[] height, int col) {
        int cell = col * grid.layers;
        for (int side = CubeSphere.I_MINUS; side <= CubeSphere.J_PLUS; side++) {
            int nb = grid.neighbor(cell, side);
            if (nb >= 0 && height[nb / grid.layers] <= height[col] - STEEP) return true;
        }
        return false;
    }
}
