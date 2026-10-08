package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FluidsTest {
    // standard(): grass at layer 8, air from 9.
    private final VoxelPlanet p = VoxelPlanet.standard();
    private final CubeSphere g = p.sphere();

    private int at(int i, int j, int k) {
        return g.index(0, i, j, k);
    }

    private void run(int ticks) {
        for (int t = 0; t < ticks; t++) p.fluids().tick();
    }

    @Test
    void waterSpreadsSevenBlocksOnFlatGround() {
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        run(400);
        assertEquals(Material.WATER, p.material(at(12 + 7, 12, 9)));
        assertEquals(7, p.level(at(12 + 7, 12, 9)));
        assertEquals(Material.AIR, p.material(at(12 + 8, 12, 9)));
    }

    @Test
    void waterRecedesWhenItsSourceIsTaken() {
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        run(400);
        p.set(at(12, 12, 9), Material.AIR);
        run(400);
        for (int i = 4; i < 21; i++) assertEquals(Material.AIR, p.material(at(i, 12, 9)), "i " + i);
    }

    @Test
    void waterFallsIntoAHole() {
        p.set(at(12, 12, 8), Material.AIR);
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        run(Fluids.WATER_TICKS);
        assertEquals(Material.WATER, p.material(at(12, 12, 8)));
        assertEquals(Fluids.FALLING, p.level(at(12, 12, 8)));
        assertEquals(Material.AIR, p.material(at(13, 12, 9)), "a source over a hole falls first");
        run(Fluids.WATER_TICKS);
        assertEquals(Material.WATER, p.material(at(13, 12, 9)), "then, the hole full, it spreads");
    }

    @Test
    void lavaTouchingWaterHardens() {
        p.set(at(12, 12, 9), Material.LAVA, Fluids.SOURCE);
        p.set(at(13, 12, 9), Material.WATER, Fluids.SOURCE);
        assertEquals(Material.OBSIDIAN, p.material(at(12, 12, 9)));
    }

    @Test
    void lavaFallingOntoWaterMakesStone() {
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        // Walls keep the water to its cell.
        for (int s : new int[] {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS})
            p.set(g.neighbor(at(12, 12, 9), s), Material.STONE);
        p.set(at(12, 12, 11), Material.LAVA, Fluids.SOURCE);
        run(120);
        assertEquals(Material.STONE, p.material(at(12, 12, 9)));
    }

    /**
     * Minecraft's classic generator, in a trench: lava | gap | dip | floor | water. The water
     * flows toward the dip and falls into it, never into the gap; the lava flows into the gap,
     * meets the water and turns into cobblestone, again each time it is mined.
     */
    @Test
    void cobblestoneGenerator() {
        for (int i = 8; i <= 16; i++)
            for (int j = 11; j <= 13; j++) p.set(at(i, j, 9), Material.STONE);
        for (int i = 10; i <= 14; i++) p.set(at(i, 12, 9), Material.AIR);
        p.set(at(12, 12, 8), Material.AIR); // the dip
        p.set(at(10, 12, 9), Material.LAVA, Fluids.SOURCE);
        p.set(at(14, 12, 9), Material.WATER, Fluids.SOURCE);
        run(200);
        int gap = at(11, 12, 9);
        assertEquals(Material.COBBLESTONE, p.material(gap));
        assertEquals(Material.LAVA, p.material(at(10, 12, 9)), "the lava source stays");
        for (int n = 0; n < 5; n++) {
            p.set(gap, Material.AIR);
            run(Fluids.LAVA_TICKS * 2);
            assertEquals(Material.COBBLESTONE, p.material(gap), "mined " + (n + 1));
        }
        assertEquals(Material.LAVA, p.material(at(10, 12, 9)));
    }

    @Test
    void iceBreaksIntoWaterAndFluidsSurviveASave() {
        p.set(at(12, 12, 9), Material.WATER, 3);
        VoxelPlanet q = VoxelPlanet.of(g, p.depth, p.cells().clone(), p.blocks);
        assertEquals(Material.WATER, q.material(at(12, 12, 9)));
        assertEquals(3, q.level(at(12, 12, 9)));
        assertTrue(q.fluids().scheduled() > 0, "it flows again once loaded");
    }

    @Test
    void fluidsDrawWithoutCollision() {
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        int chunk = p.chunkOf(at(12, 12, 9));
        var water = PlanetMesher.quads(p, chunk).stream().filter(q -> q.tile() == p.info(at(12, 12, 9)).tile()).toList();
        assertEquals(5, water.size(), "top and four sides, not the bottom on grass");
        int before = PlanetMesher.collision(p, chunk).size();
        p.set(at(12, 12, 9), Material.AIR);
        assertEquals(before, PlanetMesher.collision(p, chunk).size(), "water adds no collision");
        // A lone source: Minecraft's corners average it (weight 10) with the air beside it (1 each).
        double top = water.stream().filter(q -> q.side() == CubeSphere.TOP).findFirst().orElseThrow().corners()[0].length();
        assertEquals(g.radius(9) + (8 / 9.0 * 10) / 12, top, 1e-6);
        assertTrue(water.stream().allMatch(PlanetMesher.Quad::translucent), "water is drawn translucent");
    }

    @Test
    void aLakeIsOneFlatSheet() {
        for (int i = 10; i <= 14; i++)
            for (int j = 10; j <= 14; j++) p.set(at(i, j, 9), Material.WATER, Fluids.SOURCE);
        int c = at(12, 12, 9);
        var quads = PlanetMesher.quads(p, p.chunkOf(c)).stream().filter(q -> q.tile() == p.info(c).tile()).toList();
        var tops = quads.stream().filter(q -> q.side() == CubeSphere.TOP).toList();
        assertEquals(25, tops.size());
        // Inside the lake every corner is a source's height; no faces between its cells.
        for (var q : tops)
            for (var v : q.corners()) {
                double h = v.length() - g.radius(9);
                assertTrue(h <= 8 / 9.0 + 1e-6 && h > 0.7, "corner at " + h);
            }
        var mid = PlanetMesher.quads(p, p.chunkOf(c)).stream().filter(q -> q.side() >= CubeSphere.I_MINUS)
                .filter(q -> q.translucent()).count();
        assertEquals(20, mid, "sides only around the lake, against the air");
    }

    @Test
    void flowingWaterSlopesDownstream() {
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        p.set(at(13, 12, 9), Material.WATER, 3);
        double[] f = PlanetMesher.flow(p, Blocks.WATER, at(13, 12, 9));
        assertTrue(f[0] > 0, "flows away from the source, along +i");
    }

    @Test
    void waterWashesAwayFlowers() {
        p.set(at(13, 12, 9), CubeBlocks.FLOWER);
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        run(Fluids.WATER_TICKS * 2);
        assertEquals(Material.WATER, p.material(at(13, 12, 9)));
    }
}
