package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StationStoreTest {
    @Test void roundTripKeepsEverything(@TempDir Path dir) throws Exception {
        Station s = Station.create("cafe0001", "Farm", new Quaterniond().rotateXYZ(0.1, 0.2, 0.3), CubeBlocks.INSTANCE,
                StationTest.STONE, StationTest.CORE);
        int c = s.grid().cellOf(s.grid().ox + 1, 0, 0);
        s.planet.set(c, StationTest.STONE);
        s.changed(c);
        s.regrow();
        s.stage = "GalaxyCraftSpace";
        s.center = new Vector3d(1e7, -5, 3.25);
        StationStore store = new StationStore(dir);
        store.write(s, CubeBlocks.INSTANCE);
        Station r = store.read("cafe0001", CubeBlocks.INSTANCE);
        assertEquals("cafe0001", r.id);
        assertEquals("Farm", r.name);
        assertEquals("GalaxyCraftSpace", r.stage);
        assertEquals(0, r.center.distance(s.center), 1e-9);
        assertTrue(r.rotation.equals(s.rotation, 1e-12));
        assertArrayEquals(s.planet.cells(), r.planet.cells());
        assertEquals(s.bounds, r.bounds);
        assertEquals(s.grid().ox, r.grid().ox);
        StationStore.Header h = store.list().getFirst();
        assertEquals(1, store.list().size());
        assertEquals(82, h.blocks());
        assertEquals(s.bounds.spanX(), h.spanX());
    }

    @Test void packedHasNoStage(@TempDir Path dir) throws Exception {
        Station s = StationTest.station("cafe0002");
        s.stage = null;
        s.center = new Vector3d();
        new StationStore(dir).write(s, CubeBlocks.INSTANCE);
        assertNull(new StationStore(dir).list().getFirst().stage());
        assertNull(new StationStore(dir).read("cafe0002", CubeBlocks.INSTANCE).stage);
    }

    @Test void aBrokenFileIsSkipped(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad00000.gxstation"), "nope");
        assertTrue(new StationStore(dir).list().isEmpty());
    }

    @Test void noFolderIsNoStations(@TempDir Path dir) {
        assertTrue(new StationStore(dir.resolve("missing")).list().isEmpty());
    }
}
