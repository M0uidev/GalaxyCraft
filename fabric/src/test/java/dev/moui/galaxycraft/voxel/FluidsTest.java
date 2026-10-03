package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FluidsTest {
    // standard(): grass at layer 8, air from 9.
    private final VoxelPlanet p = VoxelPlanet.standard();
    private final CubeSphere g = p.grid;

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
        assertEquals(Material.WATER, p.get(at(12 + 7, 12, 9)));
        assertEquals(7, p.level(at(12 + 7, 12, 9)));
        assertEquals(Material.AIR, p.get(at(12 + 8, 12, 9)));
    }

    @Test
    void waterRecedesWhenItsSourceIsTaken() {
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        run(400);
        p.set(at(12, 12, 9), Material.AIR);
        run(400);
        for (int i = 4; i < 21; i++) assertEquals(Material.AIR, p.get(at(i, 12, 9)), "i " + i);
    }

    @Test
    void waterFallsIntoAHole() {
        p.set(at(12, 12, 8), Material.AIR);
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        run(Fluids.WATER_TICKS);
        assertEquals(Material.WATER, p.get(at(12, 12, 8)));
        assertEquals(Fluids.FALLING, p.level(at(12, 12, 8)));
        assertEquals(Material.AIR, p.get(at(13, 12, 9)), "a source over a hole falls first");
        run(Fluids.WATER_TICKS);
        assertEquals(Material.WATER, p.get(at(13, 12, 9)), "then, the hole full, it spreads");
    }

    @Test
    void lavaTouchingWaterHardens() {
        p.set(at(12, 12, 9), Material.LAVA, Fluids.SOURCE);
        p.set(at(13, 12, 9), Material.WATER, Fluids.SOURCE);
        assertEquals(Material.OBSIDIAN, p.get(at(12, 12, 9)));
    }

    @Test
    void lavaFallingOntoWaterMakesStone() {
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        // Walls keep the water to its cell.
        for (int s : new int[] {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS})
            p.set(g.neighbor(at(12, 12, 9), s), Material.STONE);
        p.set(at(12, 12, 11), Material.LAVA, Fluids.SOURCE);
        run(120);
        assertEquals(Material.STONE, p.get(at(12, 12, 9)));
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
        assertEquals(Material.COBBLESTONE, p.get(gap));
        assertEquals(Material.LAVA, p.get(at(10, 12, 9)), "the lava source stays");
        for (int n = 0; n < 5; n++) {
            p.set(gap, Material.STONE.broken());
            run(Fluids.LAVA_TICKS * 2);
            assertEquals(Material.COBBLESTONE, p.get(gap), "mined " + (n + 1));
        }
        assertEquals(Material.LAVA, p.get(at(10, 12, 9)));
    }

    @Test
    void iceBreaksIntoWaterAndFluidsSurviveASave() {
        assertEquals(Material.WATER, Material.ICE.broken());
        p.set(at(12, 12, 9), Material.WATER, 3);
        VoxelPlanet q = VoxelPlanet.of(g, p.depth, p.cells().clone());
        assertEquals(Material.WATER, q.get(at(12, 12, 9)));
        assertEquals(3, q.level(at(12, 12, 9)));
        assertTrue(q.fluids().scheduled() > 0, "it flows again once loaded");
    }

    @Test
    void fluidsDrawWithoutCollision() {
        p.set(at(12, 12, 9), Material.WATER, Fluids.SOURCE);
        int chunk = p.chunkOf(at(12, 12, 9));
        var water = PlanetMesher.quads(p, chunk).stream().filter(q -> q.tile() == Material.WATER.top).toList();
        assertEquals(5, water.size(), "top and four sides, not the bottom on grass");
        assertTrue(water.stream().noneMatch(PlanetMesher.Quad::solid));
        double top = water.stream().filter(q -> q.side() == CubeSphere.TOP).findFirst().orElseThrow().corners()[0].length();
        assertEquals(g.radius(9) + 8 / 9.0, top, 1e-6);
    }
}
