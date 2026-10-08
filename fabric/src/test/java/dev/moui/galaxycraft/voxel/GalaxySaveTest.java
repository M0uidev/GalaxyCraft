package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GalaxySaveTest {
    @TempDir Path world;

    @Test void aNewWorldHasNoGalaxyYet() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        assertTrue(g.isNew());
        assertTrue(g.spot().isEmpty());
        g.markMade();
        assertFalse(g.isNew());
        assertTrue(Files.isDirectory(g.planets()));
        assertEquals(world.resolve("galaxycraft/planets"), g.planets());
    }

    @Test void theSpotComesBack() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        GalaxySave.Spot spot = new GalaxySave.Spot(2, 0.5, -0.25, 0.75, 91.5f, -12f);
        g.writeSpot(spot);
        assertEquals(spot, GalaxySave.of(world).spot().orElseThrow());
    }

    @Test void theBedIsKeptApartFromTheSpot() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        assertTrue(g.bed().isEmpty());
        GalaxySave.Spot bed = new GalaxySave.Spot(1, 0, 3900, 0, 0, 0), spot = new GalaxySave.Spot(1, 10, 3880, 0, 5, 0);
        g.writeBed(bed);
        g.writeSpot(spot);
        assertEquals(bed, GalaxySave.of(world).bed().orElseThrow());
        assertEquals(spot, GalaxySave.of(world).spot().orElseThrow());
        g.clearBed();
        assertTrue(GalaxySave.of(world).bed().isEmpty());
    }

    @Test void aBrokenSpotIsNoSpot() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        g.markMade();
        Files.writeString(world.resolve("galaxycraft/player.json"), "{not json");
        assertTrue(g.spot().isEmpty());
        Files.writeString(world.resolve("galaxycraft/player.json"), "{\"planet\": 0, \"dx\": 0, \"dy\": 0, \"dz\": 0}");
        assertTrue(g.spot().isEmpty()); // no direction: nowhere to stand
    }

    @Test void theGalaxyComesBack() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        assertTrue(g.galaxy().isEmpty());
        var o = GalaxyCatalog.Options.defaults(42);
        var es = GalaxyCatalog.make(o, 48, java.util.List.of("minecraft:plains"), 80).entries();
        g.writeGalaxy(new GalaxySave.Galaxy(1, o, es));
        GalaxySave.Galaxy back = GalaxySave.of(world).galaxy().orElseThrow();
        assertEquals(o, back.options());
        assertEquals(es, back.entries());
    }

    @Test void aBrokenGalaxyIsNone() throws Exception {
        Files.createDirectories(world.resolve("galaxycraft"));
        Files.writeString(world.resolve("galaxycraft/galaxy.json"), "{nope");
        assertTrue(GalaxySave.of(world).galaxy().isEmpty());
    }

    @Test void aWorldFromBeforeCatalogsGetsOneFromItsPlanetFiles() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        PlanetStore store = new PlanetStore(g.planets());
        VoxelPlanet p = VoxelPlanet.standard();
        int[] indices = {0, 2, 5};
        for (int i : indices) {
            var saved = new PlanetStore.Saved(p.grid.n, p.sphere().core, p.grid.layers, p.depth, new org.joml.Vector3d(i * 1000, 0, -i), p.cells());
            store.write(PlanetStore.key("S", i), saved, CubeBlocks.INSTANCE);
        }
        GalaxySave.Galaxy made = g.fromFiles(store, "S");
        assertEquals(3, made.entries().size());
        for (int k = 0; k < 3; k++) {
            GalaxyCatalog.Entry e = made.entries().get(k);
            assertEquals(indices[k], e.index());
            assertEquals(indices[k] * 1000, e.x(), 1e-9);
            assertEquals(Math.round(p.surface()), e.radius());
            assertEquals(GalaxyCatalog.Kind.BLUEPRINT, e.kind());
            assertNull(e.blueprint());
        }
        assertEquals(3, made.options().count());
    }

    @Test void aGalaxySavedBeforeLayoutsIsLayoutOne() {
        assertEquals(1, new GalaxySave.Galaxy(0, null, java.util.List.of()).layout());
        assertEquals(1, new GalaxySave.Galaxy(1, null, java.util.List.of()).layout());
        assertEquals(2, new GalaxySave.Galaxy(2, null, java.util.List.of()).layout());
    }
}
