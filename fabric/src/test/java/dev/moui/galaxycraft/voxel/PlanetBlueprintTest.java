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
        CubeSphere g = p.grid;
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
        store.delete("alpha");
        assertEquals(List.of("bad", "Moon_base_1"), store.list());
    }
}
