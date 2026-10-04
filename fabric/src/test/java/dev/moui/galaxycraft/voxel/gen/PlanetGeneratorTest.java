package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeBlocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanetGeneratorTest {
    private static final Blocks B = CubeBlocks.INSTANCE;

    /** Smooth waves, other ones per field and seed. */
    private static TerrainNoise waves(long seed) {
        return (f, x, y, z) -> {
            double s = seed * 0.37 + f.ordinal() * 1.7;
            return 0.6 * Math.sin(x * 0.05 + s) * Math.cos(y * 0.04 - s) + 0.4 * Math.sin(z * 0.06 + 2 * s);
        };
    }

    /** Desert where it is warm, plains elsewhere; peaks are high and rough. */
    private static final BiomeTable TABLE = new BiomeTable() {
        @Override public String find(Climate c, boolean water) {
            if (water && c.continentalness() < -0.3) return c.temperature() < -0.3 ? "minecraft:frozen_ocean" : "minecraft:ocean";
            return c.temperature() > 0 ? "minecraft:desert" : "minecraft:plains";
        }

        @Override public Climate.Span span(String biome) {
            return switch (biome) {
                case "minecraft:desert", "minecraft:plains" -> new Climate.Span(new Climate(0, 0, -1, -1, -1), new Climate(0.5, 0.5, 1, 1, 1));
                case "minecraft:jagged_peaks" -> new Climate.Span(new Climate(0.5, -1, 0.5, -1, -1), new Climate(1, -0.78, 1, 1, 1));
                case "minecraft:ocean", "minecraft:frozen_ocean" -> new Climate.Span(new Climate(-1, -1, -1, -1, -1), new Climate(-0.45, 1, 1, 1, 1));
                default -> null;
            };
        }

        @Override public List<String> land() {
            return List.of("minecraft:desert", "minecraft:jagged_peaks", "minecraft:plains");
        }

        @Override public List<String> all() {
            return List.of("minecraft:desert", "minecraft:frozen_ocean", "minecraft:jagged_peaks", "minecraft:ocean", "minecraft:plains");
        }

        @Override public boolean watery(String biome) {
            return biome.endsWith("ocean");
        }
    };

    /** Sand is cobblestone here, sandstone obsidian; the rest by its kind. */
    private static int id(String name) {
        Material m = name.contains("sandstone") ? Material.OBSIDIAN : name.contains("sand") ? Material.COBBLESTONE
                : name.contains("bedrock") ? Material.BEDROCK : name.contains("dirt") ? Material.DIRT
                : name.contains("grass") ? Material.GRASS : name.contains("snow") ? Material.ICE
                : name.contains("water") ? Material.WATER : name.contains("ice") ? Material.LAVA : name.contains("gravel") ? Material.DIRT
                : Material.STONE;
        return B.id(m);
    }

    private static PlanetBlueprint bp(int radius, int air, long seed, String biome, int size) {
        return PlanetBlueprint.standard("g", radius).withMode(PlanetBlueprint.Mode.GENERATED).withBiome(seed, biome, size)
                .withAir(air);
    }

    private static VoxelPlanet build(PlanetBlueprint bp) {
        return PlanetGenerator.build(bp, waves(bp.seed()), TABLE, B, PlanetGeneratorTest::id);
    }

    /** Height of the ground's top in a column, as k. */
    private static int top(VoxelPlanet p, int cell0) {
        int k = p.grid.layers - 1;
        while (k > 0 && (p.get(cell0 + k) == Blocks.AIR || p.material(cell0 + k) == Material.ICE)) k--;
        return k;
    }

    @Test void noSeamsOnTheCubesEdges() {
        VoxelPlanet p = build(bp(48, 16, 3, "minecraft:jagged_peaks", 0));
        CubeSphere g = p.grid;
        int worstAcross = 0, worstInside = 0;
        for (int c = 0; c < g.cellCount(); c += g.layers)
            for (int side = CubeSphere.I_MINUS; side <= CubeSphere.J_PLUS; side++) {
                int nb = g.neighbor(c, side);
                int d = Math.abs(top(p, c) - top(p, nb));
                if (g.face(nb) != g.face(c)) worstAcross = Math.max(worstAcross, d);
                else worstInside = Math.max(worstInside, d);
            }
        assertTrue(worstAcross <= worstInside + 1, "across edges " + worstAcross + ", inside faces " + worstInside);
    }

    @Test void groundStaysBetweenBedrockAndTheSky() {
        for (String biome : List.of("minecraft:jagged_peaks", "minecraft:desert")) {
            VoxelPlanet p = build(bp(64, 8, 5, biome, 0));
            CubeSphere g = p.grid;
            for (int c = 0; c < g.cellCount(); c += g.layers) {
                assertEquals(Material.BEDROCK, p.material(c));
                assertNotEquals(Blocks.AIR, p.get(c + 1), "a block above the bedrock");
                assertTrue(top(p, c) <= g.layers - 1 - 4, "four of air above");
            }
        }
    }

    @Test void sameSeedSamePlanet() {
        assertArrayEquals(build(bp(32, 12, 7, "minecraft:jagged_peaks", 0)).cells(), build(bp(32, 12, 7, "minecraft:jagged_peaks", 0)).cells());
        assertFalse(java.util.Arrays.equals(build(bp(32, 12, 7, "minecraft:jagged_peaks", 0)).cells(),
                build(bp(32, 12, 8, "minecraft:jagged_peaks", 0)).cells()));
    }

    @Test void aDesertPlanetIsSandWithBareCliffs() {
        VoxelPlanet p = build(bp(40, 12, 1, "minecraft:desert", 0));
        CubeSphere g = p.grid;
        int sand = 0, other = 0;
        for (int c = 0; c < g.cellCount(); c += g.layers) {
            Material m = p.material(c + top(p, c));
            if (m == Material.COBBLESTONE) sand++;
            else assertEquals(Material.STONE, m, "a cliff's top is the desert's stone");
            if (m != Material.COBBLESTONE) other++;
        }
        assertTrue(sand > 10 * other, sand + " sand, " + other + " cliffs");
    }

    @Test void randomPicksALandBiomeBySeed() {
        Set<String> seen = new HashSet<>();
        for (long s = 0; s < 20; s++) seen.add(PlanetGenerator.biome(bp(32, 8, s, PlanetBlueprint.RANDOM, 0), TABLE));
        assertTrue(TABLE.land().containsAll(seen));
        assertTrue(seen.size() > 1);
        assertThrows(IllegalArgumentException.class, () -> build(bp(32, 8, 0, "minecraft:nether_wastes", 0)));
    }

    @Test void severalBiomesMix() {
        VoxelPlanet p = build(bp(48, 12, 2, "minecraft:plains", 64));
        CubeSphere g = p.grid;
        Set<Material> tops = new HashSet<>();
        for (int c = 0; c < g.cellCount(); c += g.layers) tops.add(p.material(c + top(p, c)));
        assertTrue(tops.contains(Material.COBBLESTONE) && tops.contains(Material.GRASS), tops.toString());
    }

    /** Water cells in a column, from the top down to the first that is not. */
    private static int water(VoxelPlanet p, int c0) {
        int k = p.grid.layers - 1, w = 0;
        while (k > 0 && p.get(c0 + k) == Blocks.AIR) k--;
        while (k > 0 && (p.material(c0 + k) == Material.WATER || p.material(c0 + k) == Material.LAVA)) {
            w++;
            k--;
        }
        return w;
    }

    @Test void shallowSeasUpToTheBaseSurface() {
        VoxelPlanet p = build(bp(64, 12, 4, "minecraft:plains", 48).withWater(true));
        CubeSphere g = p.grid;
        int wet = 0, frozen = 0;
        for (int c = 0; c < g.cellCount(); c += g.layers) {
            int w = water(p, c);
            assertTrue(w <= PlanetGenerator.MAX_WATER_DEPTH, w + " deep");
            if (w == 0) continue;
            wet++;
            assertNotEquals(Blocks.AIR, p.get(c + p.depth - 1), "water reaches the base surface");
            assertEquals(Blocks.AIR, p.get(c + p.depth), "and no higher");
            if (p.material(c + p.depth - 1) == Material.LAVA) frozen++; // ice is lava in the tests
            assertNotEquals(Material.GRASS, p.material(c + p.depth - 1 - w), "no grass under water");
        }
        assertTrue(wet > 100 && frozen > 0, wet + " wet columns, " + frozen + " frozen");
        assertFalse(p.fluids().tick(), "still water: nothing to tick");
    }

    @Test void anOceanPlanetIsMostlyWaterWithIslands() {
        VoxelPlanet p = build(bp(48, 12, 6, "minecraft:ocean", 0).withWater(true));
        CubeSphere g = p.grid;
        int wet = 0, dry = 0;
        for (int c = 0; c < g.cellCount(); c += g.layers) if (water(p, c) > 0) wet++; else dry++;
        assertTrue(wet > dry && dry > 0, wet + " wet, " + dry + " dry");
        assertEquals(0, water(build(bp(48, 12, 6, "minecraft:ocean", 0)), 0) + water(build(bp(48, 12, 6, "minecraft:desert", 0)), 0));
    }

    /** Every block its own id (cells only, no VoxelPlanet: these are not CubeBlocks ids). */
    private static final java.util.Map<String, Integer> UNIQUE = new java.util.HashMap<>();

    private static int unique(String name) {
        return UNIQUE.computeIfAbsent(name, k -> UNIQUE.size() + 1);
    }

    private static PlanetGenerator.Cells cells(PlanetBlueprint bp) {
        return PlanetGenerator.cells(bp, waves(bp.seed()), TABLE, PlanetGeneratorTest::unique);
    }

    /** Index of the highest ground cell (not air, not water) in a column. */
    private static int ground(PlanetGenerator.Cells c, int base) {
        int k = c.grid().layers - 1;
        while (k > 0 && (c.cells()[base + k] == Blocks.AIR || c.cells()[base + k] == unique("minecraft:water")
                || c.cells()[base + k] == unique("minecraft:snow") || c.cells()[base + k] == unique("minecraft:ice"))) k--;
        return k;
    }

    @Test void cavesOnlyCarveTheGroundAndNeverTheBedrock() {
        PlanetBlueprint bp = bp(96, 16, 3, "minecraft:plains", 0).withWater(true);
        PlanetGenerator.Cells solid = cells(bp.withUnderground(0, true, 0)), caves = cells(bp.withUnderground(60, true, 0));
        int carved = 0, ground = 0;
        for (int i = 0; i < solid.cells().length; i++) {
            if (solid.cells()[i] != Blocks.AIR && solid.cells()[i] != unique("minecraft:water")) ground++;
            if (solid.cells()[i] == caves.cells()[i]) continue;
            assertEquals(Blocks.AIR, caves.cells()[i], "caves only take blocks away");
            assertNotEquals(unique("minecraft:bedrock"), solid.cells()[i], "not the bedrock");
            assertNotEquals(unique("minecraft:water"), solid.cells()[i], "not the water");
            carved++;
        }
        assertTrue(carved > ground / 100, carved + " of " + ground + " carved");
    }

    @Test void withoutEntrancesAndUnderWaterTheRoofStays() {
        PlanetBlueprint bp = bp(96, 16, 3, "minecraft:plains", 48).withWater(true);
        PlanetGenerator.Cells solid = cells(bp.withUnderground(0, true, 0)), closed = cells(bp.withUnderground(100, false, 0)),
                open = cells(bp.withUnderground(100, true, 0));
        CubeSphere g = solid.grid();
        int holes = 0;
        for (int base = 0; base < solid.cells().length; base += g.layers) {
            int top = ground(solid, base);
            boolean wet = solid.cells()[base + top + 1] == unique("minecraft:water") || solid.cells()[base + top + 1] == unique("minecraft:ice");
            for (int k = top - Underground.ROOF + 1; k <= top; k++) {
                assertEquals(solid.cells()[base + k], closed.cells()[base + k], "a roof without entrances");
                if (wet) assertEquals(solid.cells()[base + k], open.cells()[base + k], "a roof under water");
            }
            if (open.cells()[base + top] == Blocks.AIR) holes++;
        }
        assertTrue(holes > 0, "entrances open somewhere");
    }

    @Test void oresAtTheirDepthsDeepslateBelow() {
        PlanetBlueprint bp = bp(96, 16, 5, "minecraft:plains", 0).withUnderground(0, true, 100);
        PlanetGenerator.Cells c = cells(bp);
        CubeSphere g = c.grid();
        int deepTop = (c.depth() - 1) / 3;
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        for (int i = 0; i < c.cells().length; i++) {
            count.merge((int) c.cells()[i], 1, Integer::sum);
            int k = g.k(i);
            if (c.cells()[i] == unique("minecraft:deepslate")) assertTrue(k <= deepTop, "deepslate only deep");
            if (c.cells()[i] == unique("minecraft:diamond_ore") || c.cells()[i] == unique("minecraft:deepslate_diamond_ore"))
                assertTrue(k <= c.depth() / 3 + 2, "diamonds by the bedrock, not at " + k);
        }
        for (String ore : List.of("coal", "iron", "diamond"))
            assertTrue(count.getOrDefault(unique("minecraft:" + ore + "_ore"), 0) + count.getOrDefault(unique("minecraft:deepslate_" + ore + "_ore"), 0) > 0, ore);
        assertTrue(count.getOrDefault(unique("minecraft:deepslate_diamond_ore"), 0) > count.getOrDefault(unique("minecraft:diamond_ore"), 0));
        PlanetGenerator.Cells none = cells(bp.withUnderground(0, true, 0));
        assertFalse(java.util.Arrays.equals(none.cells(), c.cells()));
        for (char id : none.cells()) assertNotEquals(unique("minecraft:coal_ore"), (int) id);
    }

    @Test void blueprintsSavedBeforeAreLayered() {
        PlanetBlueprint old = PlanetBlueprint.fromJson("{\"name\":\"o\",\"radius\":20,\"air\":8,\"layers\":[{\"block\":\"x\",\"thickness\":1}]}");
        assertEquals(PlanetBlueprint.Mode.LAYERS, old.mode());
        assertEquals(PlanetBlueprint.RANDOM, old.biome());
        PlanetBlueprint g = bp(32, 8, 9, "minecraft:desert", 0);
        assertEquals(g, PlanetBlueprint.fromJson(g.toJson()));
    }
}
