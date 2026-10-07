package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanetBlueprintTest {
    private static final Blocks B = CubeBlocks.INSTANCE;

    private static String name(Material m) {
        return B.name(B.id(m));
    }

    @Test void layersFromTheSurfaceDownToBedrock() {
        PlanetBlueprint bp = new PlanetBlueprint("t", 32, 10, List.of(
                new PlanetBlueprint.Layer(name(Material.COBBLESTONE), 2), new PlanetBlueprint.Layer(name(Material.STONE), 1)));
        VoxelPlanet p = bp.build(B);
        CubeSphere g = p.sphere();
        int d = p.depth;
        assertEquals(bp.crustDepth(), d);
        assertEquals(d + 10, g.layers);
        assertEquals(32.0, p.surface());
        assertEquals(Material.COBBLESTONE, p.material(g.index(2, 4, 4, d - 1)));
        assertEquals(Material.COBBLESTONE, p.material(g.index(2, 4, 4, d - 2)));
        assertEquals(Material.STONE, p.material(g.index(2, 4, 4, d - 3)));
        assertEquals(Material.STONE, p.material(g.index(2, 4, 4, 1)), "the last layer fills down");
        assertEquals(Material.BEDROCK, p.material(g.index(2, 4, 4, 0)));
        assertEquals(Material.AIR, p.material(g.index(2, 4, 4, d)));
    }

    @Test void standardMatchesSpawn() {
        VoxelPlanet a = VoxelPlanet.ofRadius(40, B);
        PlanetBlueprint bp = new PlanetBlueprint("s", 40, VoxelPlanet.defaultAir(40), List.of(
                new PlanetBlueprint.Layer(name(Material.GRASS), 1), new PlanetBlueprint.Layer(name(Material.DIRT), 2),
                new PlanetBlueprint.Layer(name(Material.STONE), 1)));
        assertArrayEquals(a.cells(), bp.build(B).cells());
    }

    @Test void problems() {
        assertNull(PlanetBlueprint.standard("x", 32).problem());
        assertNotNull(new PlanetBlueprint("x", 5, 8, PlanetBlueprint.standard("x", 32).layers()).problem());
        assertNotNull(new PlanetBlueprint("x", 32, 8, List.of()).problem());
        assertNotNull(new PlanetBlueprint("", 32, 8, PlanetBlueprint.standard("x", 32).layers()).problem());
        assertNotNull(new PlanetBlueprint("x", 32, 8, List.of(new PlanetBlueprint.Layer("a", 0))).problem());
    }

    @Test void layersFitInTheCrust() {
        assertEquals(7, PlanetBlueprint.room(32), "crust 8, one of them bedrock");
        assertArrayEquals(new int[] {1, 2, 4}, PlanetBlueprint.fit(new int[] {1, 2, 4}, 7));
        assertArrayEquals(new int[] {3, 3, 1}, PlanetBlueprint.fit(new int[] {3, 3, 5}, 7), "cut from the bottom");
        assertArrayEquals(new int[] {5, 1, 1}, PlanetBlueprint.fit(new int[] {9, 9, 9}, 7));
        assertArrayEquals(new int[] {1, 1, 1}, PlanetBlueprint.fit(new int[] {0, -3, 1}, 2), "never under 1");
    }

    @Test void storeRoundTrip(@TempDir Path dir) throws Exception {
        BlueprintStore store = new BlueprintStore(dir.resolve("bp"));
        assertEquals(List.of(), store.list());
        PlanetBlueprint bp = PlanetBlueprint.standard("Moon base/1", 48);
        store.write(bp);
        store.write(PlanetBlueprint.standard("alpha", 20));
        assertEquals(List.of("alpha", "Moon_base_1"), store.list());
        assertEquals(bp, store.read("Moon base/1").orElseThrow());
        assertTrue(store.read("nope").isEmpty());
        Files.writeString(store.file("bad"), "{");
        assertThrows(java.io.IOException.class, () -> store.read("bad"));
        assertTrue(store.readLast().isEmpty());
        PlanetBlueprint gen = bp.withMode(PlanetBlueprint.Mode.GENERATED).withBiome(5, "minecraft:desert", 0);
        store.writeLast(gen);
        assertEquals(gen, store.readLast().orElseThrow());
        assertFalse(store.list().contains("last-blueprint"), "not one of the saved ones");
        store.delete("alpha");
        assertEquals(List.of("bad", "Moon_base_1"), store.list());
    }
}
