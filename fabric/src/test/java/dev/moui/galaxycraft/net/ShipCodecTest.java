package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.ShipRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShipCodecTest {
    static ShipRecord ship(String id) {
        return new ShipRecord(id, new ShipModules(2, 3, 1, 2), new double[] {1.5, -2, 3e6}, new double[] {0, 0.6, 0, 0.8}, "Space");
    }

    @Test void oneShipRoundTrips() throws Exception {
        ShipRecord r = ship("a");
        ShipRecord back = ShipCodec.decode(ShipCodec.encode(r));
        assertEquals(r.id(), back.id());
        assertEquals(r.modules(), back.modules());
        assertArrayEquals(r.pos(), back.pos());
        assertArrayEquals(r.rot(), back.rot());
        assertEquals("Space", back.stage());
    }

    @Test void aListRoundTrips() throws Exception {
        var back = ShipCodec.decodeList(ShipCodec.encodeList(List.of(ship("a"), ship("pad-b"))));
        assertEquals(2, back.size());
        assertEquals("pad-b", back.get(1).id());
    }

    @Test void junkIsRefused() {
        assertThrows(ShipCodec.Bad.class, () -> ShipCodec.decode(new byte[] {0, 5, 1}));
        assertThrows(ShipCodec.Bad.class, () -> ShipCodec.decode(new byte[ShipCodec.MAX_LIST + 1]));
        byte[] ok = ShipCodec.encode(ship("a"));
        byte[] extra = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(ShipCodec.Bad.class, () -> ShipCodec.decode(extra));
    }

    @Test void notFiniteOrInvalidModulesAreRefused() {
        ShipRecord nan = new ShipRecord("a", new ShipModules(0, 1, 0, 1), new double[] {Double.NaN, 0, 0}, new double[] {0, 0, 0, 1}, "");
        assertThrows(ShipCodec.Bad.class, () -> ShipCodec.decode(ShipCodec.encode(nan)));
        ShipRecord bad = new ShipRecord("a", new ShipModules(0, 0, 0, 0), new double[3], new double[] {0, 0, 0, 1}, "");
        assertThrows(ShipCodec.Bad.class, () -> ShipCodec.decode(ShipCodec.encode(bad)));
    }
}
