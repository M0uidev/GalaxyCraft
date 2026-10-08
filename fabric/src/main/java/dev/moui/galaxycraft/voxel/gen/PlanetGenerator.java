package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import org.joml.Vector3d;

/**
 * Builds a generated planet with Minecraft 1.7's terrain ({@link Density}): its density sampled on
 * a coarse lattice of each face and interpolated between, as 1.7 does, gives ground and sky; what
 * lies under the sea fills with water; each biome's top and filler cover the ground
 * (replaceBlocksForBiome); then caves ({@link Caves}), ores and plants. The lattice's points on a
 * face's edge are the same directions as the next face's, so the faces meet without seams.
 */
public final class PlanetGenerator {
    static final String STONE = "minecraft:stone", WATER = BiomeSurface.WATER, ICE = BiomeSurface.ICE, LAVA = "minecraft:lava",
            GRAVEL = "minecraft:gravel", SANDSTONE = "minecraft:sandstone", SAND = "minecraft:sand", RED_SAND = "minecraft:red_sand";
    /** Badlands' terracotta bands, bottom to top, repeating. */
    static final List<String> BANDS = List.of("minecraft:terracotta", "minecraft:orange_terracotta", "minecraft:terracotta",
            "minecraft:yellow_terracotta", "minecraft:terracotta", "minecraft:terracotta", "minecraft:brown_terracotta",
            "minecraft:terracotta", "minecraft:red_terracotta", "minecraft:white_terracotta", "minecraft:terracotta",
            "minecraft:light_gray_terracotta", "minecraft:orange_terracotta", "minecraft:terracotta");

    private PlanetGenerator() {}

    /** The biome a one-biome blueprint gets: its own, or for "random" one of 1.7's land biomes by its seed. */
    public static String biome(PlanetBlueprint bp) {
        if (!PlanetBlueprint.RANDOM.equals(bp.biome())) return bp.biome();
        List<String> land = LegacyBiome.land();
        return land.get(new Random(bp.seed()).nextInt(land.size()));
    }

    /** A generated planet's cells, before they are a VoxelPlanet (that is made on the game's thread). */
    public record Cells(CubeSphere grid, int depth, char[] cells, dev.moui.galaxycraft.voxel.PlanetBiomes biomes) {
        public VoxelPlanet planet(Blocks blocks) {
            VoxelPlanet p = VoxelPlanet.of(grid, depth, cells, blocks);
            p.setBiomes(biomes);
            return p;
        }
    }

    /** ids gives the planet's id for a block's text (Blocks.parse in the game). */
    public static VoxelPlanet build(PlanetBlueprint bp, Vegetation.Library plants, Blocks blocks, ToIntFunction<String> ids) {
        return cells(bp, plants, ids).planet(blocks);
    }

    /** Every block a generated planet can be made of, before plants: what ids must know. */
    public static Set<String> blocks() {
        Set<String> all = new TreeSet<>(BiomeSurface.blocks());
        all.addAll(Underground.blocks());
        all.addAll(BANDS);
        all.addAll(List.of(LAVA, GRAVEL, SANDSTONE, SAND, RED_SAND, "minecraft:snow"));
        return all;
    }

    /** The direction through a point of a face's grid at fractional (u, v) (vertices at whole numbers). */
    static Vector3d dirAt(CubeSphere g, int f, double u, double v) {
        int i = Math.min((int) Math.floor(u), g.n - 1), j = Math.min((int) Math.floor(v), g.n - 1);
        double a = u - i, b = v - j;
        Vector3d d = g.dir(f, i, j).mul((1 - a) * (1 - b));
        d.fma(a * (1 - b), g.dir(f, i + 1, j)).fma((1 - a) * b, g.dir(f, i, j + 1)).fma(a * b, g.dir(f, i + 1, j + 1));
        return d.normalize();
    }

    /** The cells alone: safe off the game's thread when ids only reads (see {@link #blocks()}). */
    public static Cells cells(PlanetBlueprint bp, Vegetation.Library plants, ToIntFunction<String> ids) {
        Density density = new Density(bp);
        Density.Scale scale = density.scale();
        int radius = bp.radius(), depth = scale.depth();
        CubeSphere grid = new CubeSphere(VoxelPlanet.gridSize(radius), radius - depth, depth + scale.air());
        int n = grid.n, layers = grid.layers, columns = 6 * n * n;
        char[] cells = new char[grid.cellCount()];
        LegacyBiome[] biome = new LegacyBiome[columns];
        boolean water = bp.water();
        char stone = id(ids, STONE), deepslate = id(ids, Underground.DEEPSLATE), waterId = id(ids, WATER), bedrock = id(ids, "minecraft:bedrock");
        int deepTop = (depth - 1) / 3;

        // Ground and sky: the density on a lattice (m × m columns a face, every `ls` layers), between its points interpolated.
        int m = Math.max(2, (int) Math.ceil(n / Math.max(2.0, 4 * scale.hs())));
        int ls = Math.max(1, (int) Math.round(8 * scale.v()));
        int levels = (layers + ls - 1) / ls + 1;
        IntStream.range(0, 6).parallel().forEach(f -> {
            double[] lattice = new double[(m + 1) * (m + 1) * levels];
            for (int a = 0; a <= m; a++)
                for (int b = 0; b <= m; b++) {
                    Vector3d d = dirAt(grid, f, (double) a * n / m, (double) b * n / m);
                    Density.Column c = density.column(d);
                    for (int l = 0; l < levels; l++)
                        lattice[(a * (m + 1) + b) * levels + l] = density.at(d, c, l * ls + 0.5 - depth);
                }
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) {
                    int col = (f * n + i) * n + j, base = col * layers;
                    biome[col] = density.layout().at(dirAt(grid, f, i + 0.5, j + 0.5));
                    double x = (i + 0.5) * m / n, y = (j + 0.5) * m / n;
                    int a = Math.min((int) x, m - 1), b = Math.min((int) y, m - 1);
                    double fa = x - a, fb = y - b;
                    cells[base] = bedrock;
                    for (int k = 1; k < layers; k++) {
                        int l = Math.min(k / ls, levels - 2);
                        double fl = (k - l * ls) / (double) ls;
                        double v = lerp(fl, bilerp(lattice, levels, m, a, b, l, fa, fb), bilerp(lattice, levels, m, a, b, l + 1, fa, fb));
                        if (k < 2 || v > 0) cells[base + k] = k <= deepTop ? deepslate : stone;
                        else if (water && k < depth) cells[base + k] = waterId;
                    }
                }
        });

        // Each biome's blocks over its ground, 1.7's way.
        Map<LegacyBiome, char[]> palettes = new HashMap<>(); // cover, top, filler
        for (LegacyBiome b : biome)
            palettes.computeIfAbsent(b, x -> {
                BiomeSurface.Palette p = BiomeSurface.of(x.id());
                return new char[] {p.cover() == null ? (char) Blocks.AIR : id(ids, p.cover()), id(ids, p.top()), id(ids, p.filler())};
            });
        char redSand = id(ids, RED_SAND), gravel = id(ids, GRAVEL), sand = id(ids, SAND), sandstone = id(ids, SANDSTONE), ice = id(ids, ICE), lava = id(ids, LAVA);
        char[] bands = new char[BANDS.size()];
        for (int i = 0; i < bands.length; i++) bands[i] = id(ids, BANDS.get(i));
        Perlin.Octaves surface = new Perlin.Octaves(new Random(bp.seed() * 7 + 3), 4);
        int[] top = new int[columns];
        IntStream.range(0, 6).parallel().forEach(f -> {
            Random rnd = new Random(bp.seed() * 31 + f);
            for (int col = f * n * n; col < (f + 1) * n * n; col++) {
                int base = col * layers;
                LegacyBiome b = biome[col];
                char[] pal = palettes.get(b);
                boolean badlands = b == LegacyBiome.BADLANDS || b == LegacyBiome.WOODED_BADLANDS;
                Vector3d d = dirAt(grid, f, grid.i(base) + 0.5, grid.j(base) + 0.5);
                double s = radius / scale.hs() * 0.0625;
                int run = (int) (surface.sample(d.x * s, d.y * s, d.z * s) / 3 + 3 + rnd.nextDouble() * 0.25);
                int left = -1;
                char filler = pal[2];
                top[col] = 0;
                for (int k = layers - 1; k > 0; k--) {
                    char id = cells[base + k];
                    if (id == Blocks.AIR || id == waterId) {
                        left = -1;
                        continue;
                    }
                    if (top[col] == 0) top[col] = k;
                    if (left == -1) {
                        int below = k - (depth - 1); // 0: the base surface's top block
                        char head = pal[1];
                        filler = pal[2];
                        if (run <= 0) head = filler = stone;
                        else if (below < 0 && k + 1 < layers && cells[base + k + 1] == waterId)
                            head = below < (-7 - run) * scale.v() ? gravel : filler; // the sea's floor
                        if (badlands && head != stone && below > 1) {
                            head = b == LegacyBiome.WOODED_BADLANDS && below > 0.5 * scale.air() ? pal[1] : redSand;
                            filler = (char) 0;
                        }
                        cells[base + k] = head;
                        left = run;
                    } else if (left > 0) {
                        left--;
                        cells[base + k] = filler == 0 ? bands[Math.floorMod(k, bands.length)] : filler;
                        if (left == 0 && filler == sand) {
                            left = rnd.nextInt(4);
                            filler = sandstone;
                        }
                    } else if (badlands && k >= depth - 1) cells[base + k] = bands[Math.floorMod(k, bands.length)];
                }
            }
        });

        Caves.carve(grid, depth, cells, top, bp.seed(), bp.caves(), bp.entrances(), Math.max(1, Math.round(depth * 0.15f)), waterId, lava);

        // After the caves: ground a cave opened to the sky gets its biome's top (dirt left bare there
        // would turn to grass later, a block at a time); snow over snowy ground, ice over frozen water.
        int[] height = new int[columns];
        boolean[] bare = new boolean[columns];
        for (int col = 0; col < columns; col++) {
            int base = col * layers, k = layers - 1;
            while (k > 0 && cells[base + k] == Blocks.AIR) k--;
            char[] pal = palettes.get(biome[col]);
            char id = cells[base + k];
            if (id == waterId) {
                if (biome[col].frozen()) cells[base + k] = ice;
                while (k > 0 && (cells[base + k] == waterId || cells[base + k] == ice)) k--;
                height[col] = k - (depth - 1);
                continue;
            }
            if (id == pal[2] && pal[1] != pal[2] && cells[base + k] != stone) cells[base + k] = pal[1];
            height[col] = k - (depth - 1);
            if (k + 1 < layers && cells[base + k] == pal[1]) {
                bare[col] = true;
                if (pal[0] != Blocks.AIR) cells[base + k + 1] = pal[0];
            }
        }
        Underground.ores(grid, depth, cells, height, bp.seed(), bp.ores(), ids);
        if (bp.plants() > 0) {
            String[] names = new String[columns];
            for (int col = 0; col < columns; col++) names[col] = biome[col].id();
            Vegetation.plant(grid, depth, cells, height, names, bare, plants, bp.seed(), bp.plants(), ids);
        }
        String[] names = new String[columns];
        for (int col = 0; col < columns; col++) names[col] = biome[col].id();
        return new Cells(grid, depth, cells, dev.moui.galaxycraft.voxel.PlanetBiomes.of(names));
    }

    private static char id(ToIntFunction<String> ids, String name) {
        return (char) ids.applyAsInt(name);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double bilerp(double[] lattice, int levels, int m, int a, int b, int l, double fa, double fb) {
        int o = (a * (m + 1) + b) * levels + l, right = (m + 1) * levels;
        return lerp(fb, lerp(fa, lattice[o], lattice[o + right]), lerp(fa, lattice[o + levels], lattice[o + right + levels]));
    }
}
