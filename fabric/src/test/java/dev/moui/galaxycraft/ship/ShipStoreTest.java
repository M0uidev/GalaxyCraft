package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShipStoreTest {
    @Test
    void roundTripsShips(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ships.json");
        ShipStore s = ShipStore.load(f);
        s.put(new ShipRecord("a", new ShipModules(2, 3, 1, 2), new double[] {1.5, 2, -3}, new double[] {0, 0.5, 0, 0.5}, "GalaxyCraftSpace"));
        s.save();
        ShipStore back = ShipStore.load(f);
        assertEquals(1, back.all().size());
        ShipRecord r = back.all().get(0);
        assertEquals("a", r.id());
        assertEquals(new ShipModules(2, 3, 1, 2), r.modules());
        assertArrayEquals(new double[] {1.5, 2, -3}, r.pos());
        assertArrayEquals(new double[] {0, 0.5, 0, 0.5}, r.rot());
        assertEquals("GalaxyCraftSpace", r.stage());
    }

    @Test
    void aCorruptFileIsAnEmptyStore(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ships.json");
        Files.writeString(f, "{ not json");
        assertTrue(ShipStore.load(f).all().isEmpty());
    }

    @Test
    void invalidModulesAreRepairedOnLoad(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ships.json");
        Files.writeString(f, "[{\"id\":\"x\",\"hull\":0,\"engine\":0,\"rudder\":0,\"cabin\":0,\"pos\":[0,0,0],\"rot\":[0,0,0,1],\"stage\":\"s\"}]");
        assertTrue(ShipStore.load(f).all().get(0).modules().valid());
    }

    @Test
    void aShipWithoutOrientationLoadsUpright(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ships.json");
        Files.writeString(f, "[{\"id\":\"x\",\"hull\":1,\"engine\":1,\"rudder\":1,\"cabin\":1,\"pos\":[0,0,0]}]");
        ShipRecord r = ShipStore.load(f).all().get(0);
        assertArrayEquals(new double[] {0, 0, 0, 1}, r.rot());
        assertEquals("", r.stage());
    }

    @Test
    void removeReturnsTheShip(@TempDir Path dir) {
        ShipStore s = ShipStore.load(dir.resolve("ships.json"));
        s.put(new ShipRecord("a", new ShipModules(0, 1, 0, 1), new double[3], new double[] {0, 0, 0, 1}, "s"));
        assertTrue(s.remove("a").isPresent());
        assertTrue(s.remove("a").isEmpty());
    }
}
