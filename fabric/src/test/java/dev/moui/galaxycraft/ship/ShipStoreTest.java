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

    private static ShipRecord ship(String id) {
        return new ShipRecord(id, new ShipModules(1, 1, 1, 3), new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, "s");
    }

    @Test
    void cargoSurvivesASaveAndALoad(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ships.json");
        ShipStore s = ShipStore.load(f);
        s.put(ship("a"));
        ShipCargo c = s.cargo("a", 3);
        c.install(0);
        c.install(1);
        c.join(0);
        c.install(2);
        s.save();
        ShipCargo back = ShipStore.load(f).cargo("a", 3);
        assertEquals(4, back.capacity());
        assertEquals(ShipCargo.Bay.LARGE_HEAD, back.bay(0));
        assertEquals(ShipCargo.Bay.LARGE_TAIL, back.bay(1));
        assertEquals(54, back.chest(1).size());
        assertEquals(ShipCargo.Bay.SMALL, back.bay(2));
        assertEquals(ShipCargo.Bay.EMPTY, back.bay(3));
    }

    @Test
    void aShipWithoutCargoHasAnEmptyHold(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ships.json");
        Files.writeString(f, "[{\"id\":\"x\",\"hull\":1,\"engine\":1,\"rudder\":1,\"cabin\":3,\"pos\":[0,0,0]}]");
        ShipCargo c = ShipStore.load(f).cargo("x", 3);
        for (int i = 0; i < 4; i++) assertEquals(ShipCargo.Bay.EMPTY, c.bay(i));
    }

    @Test
    void aDamagedCargoIsAnEmptyHold(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ships.json");
        Files.writeString(f, "[{\"id\":\"x\",\"hull\":1,\"engine\":1,\"rudder\":1,\"cabin\":3,\"pos\":[0,0,0],\"cargo\":\"junk\"},"
                + "{\"id\":\"y\",\"hull\":1,\"engine\":1,\"rudder\":1,\"cabin\":3,\"pos\":[0,0,0],"
                + "\"cargo\":{\"bays\":[\"LARGE_HEAD\",\"SMALL\",\"nonsense\",\"LARGE_TAIL\"]}}]");
        ShipStore s = ShipStore.load(f);
        assertEquals(ShipCargo.Bay.EMPTY, s.cargo("x", 3).bay(0));
        ShipCargo y = s.cargo("y", 3); // a head with no tail and a tail with no head are repaired
        assertNotEquals(ShipCargo.Bay.LARGE_HEAD, y.bay(0));
        assertNotEquals(ShipCargo.Bay.LARGE_TAIL, y.bay(3));
        assertEquals(ShipCargo.Bay.EMPTY, y.bay(2));
    }

    @Test
    void theCabinCanOnlyGrowTheHold(@TempDir Path dir) {
        ShipStore s = ShipStore.load(dir.resolve("ships.json"));
        s.put(ship("a"));
        assertEquals(1, s.cargo("a", 1).capacity());
        assertEquals(4, s.cargo("a", 3).capacity());
        assertEquals(4, s.cargo("a", 1).capacity()); // never shrinks under chests
    }

    @Test
    void theCargoOfARemovedShipIsNotSaved(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ships.json");
        ShipStore s = ShipStore.load(f);
        s.put(ship("a"));
        s.cargo("a", 3).install(0);
        s.remove("a");
        s.save();
        assertEquals("[]", Files.readString(f));
    }
}
