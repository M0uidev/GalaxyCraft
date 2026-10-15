package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class StationListTest {
    static StationList.Head head(int slot, String id, String stage) {
        return new StationList.Head(slot, id, "Station " + id, stage, 1e7, -5, 3.25, 0, 0.5, 0, 0.5, 9, 1, 9);
    }

    @Test void theCodecRoundTrips() {
        var m = new StationList.Msg(true, List.of(head(StationSlots.BASE, "cafe0001", "GalaxyCraftSpace"), head(StationSlots.BASE + 1, "cafe0002", null)));
        assertEquals(m, StationList.decode(StationList.encode(m)));
    }

    @Test void junkIsNotAList() {
        assertNull(StationList.decode(new byte[] {1, 2, 3}));
        byte[] ok = StationList.encode(new StationList.Msg(false, List.of(head(StationSlots.BASE, "a", "S"))));
        assertNull(StationList.decode(java.util.Arrays.copyOf(ok, ok.length - 1)), "truncated");
        assertNull(StationList.decode(java.util.Arrays.copyOf(ok, ok.length + 1)), "trailing byte");
        assertNull(StationList.decode(StationList.encode(new StationList.Msg(false, List.of(head(5, "a", "S"))))), "a slot that is a planet number");
        assertNull(StationList.decode(StationList.encode(new StationList.Msg(false,
                List.of(new StationList.Head(StationSlots.BASE, "a", "n", "S", Double.NaN, 0, 0, 0, 0, 0, 1, 1, 1, 1))))), "not finite");
    }

    @Test void tooManyStationsCannotBeEncoded() {
        var many = new java.util.ArrayList<StationList.Head>();
        for (int i = 0; i <= StationList.MAX_HEADS; i++) many.add(head(StationSlots.BASE + i, "s" + i, "S"));
        assertThrows(IllegalArgumentException.class, () -> StationList.encode(new StationList.Msg(true, many)));
    }

    @Test void aFullListReplacesAndADeltaChanges() {
        Map<Integer, StationList.Head> known = new TreeMap<>();
        known = StationList.merge(known, new StationList.Msg(true, List.of(head(StationSlots.BASE, "a", "S"), head(StationSlots.BASE + 1, "b", "S"))));
        assertEquals(2, known.size());
        known = StationList.merge(known, new StationList.Msg(false, List.of(head(StationSlots.BASE + 2, "c", "S"), head(StationSlots.BASE, "a", null))));
        assertEquals(List.of(StationSlots.BASE + 1, StationSlots.BASE + 2), List.copyOf(known.keySet()), "a packed one leaves, a new one comes");
        known = StationList.merge(known, new StationList.Msg(true, List.of(head(StationSlots.BASE + 1, "b", "S"))));
        assertEquals(List.of(StationSlots.BASE + 1), List.copyOf(known.keySet()));
    }

    @Test void theDiffIsWhatAGuestNeedsToCatchUp() {
        Map<Integer, StationList.Head> before = new TreeMap<>(), now = new TreeMap<>();
        for (var h : List.of(head(StationSlots.BASE, "a", "S"), head(StationSlots.BASE + 1, "b", "S"))) before.put(h.slot(), h);
        now.put(StationSlots.BASE, head(StationSlots.BASE, "a", "S")); // unchanged
        now.put(StationSlots.BASE + 2, head(StationSlots.BASE + 2, "c", "S")); // placed; "b" was packed
        var delta = StationList.diff(before, now);
        assertEquals(2, delta.size());
        assertEquals(now, StationList.merge(before, new StationList.Msg(false, delta)));
        assertTrue(StationList.diff(now, now).isEmpty());
    }

    @Test void slotsAreLazyStableAndNeverReused() {
        var s = new StationSlots();
        int a = s.assign("a"), b = s.assign("b");
        assertNotEquals(a, b);
        assertEquals(a, s.assign("a"));
        assertEquals("b", s.idOf(b));
        assertTrue(StationSlots.isStation(a));
        assertFalse(StationSlots.isStation(4095));
        assertEquals(0, s.slotOf("zzz"));
        assertNull(s.idOf(StationSlots.BASE + 99));
    }

    @Test void aGuestLearnsOnlySanePairs() {
        var s = new StationSlots();
        assertTrue(s.learn(StationSlots.BASE + 3, "a"));
        assertTrue(s.learn(StationSlots.BASE + 3, "a"), "the same again");
        assertFalse(s.learn(StationSlots.BASE + 3, "b"), "a slot belongs to one station");
        assertFalse(s.learn(StationSlots.BASE + 4, "a"), "a station has one slot");
        assertFalse(s.learn(12, "c"), "a planet number");
        assertEquals(StationSlots.BASE + 3, s.slotOf("a"));
    }

    @Test void editsOfAnOldGridAreStale() {
        int slot = StationSlots.BASE;
        assertTrue(StationSlots.stale(slot, 0, 77));
        assertTrue(StationSlots.stale(slot, 76, 77));
        assertFalse(StationSlots.stale(slot, 77, 77));
        assertFalse(StationSlots.stale(3, 0, 0), "planets have no grid key");
    }

    @Test void theGenTravelsInTheEditCodecAndOnlyWhenSet() throws Exception {
        var plain = new EditCodec.Msg(StationSlots.BASE, 2, 1, List.of(new EditCodec.Def(1, "minecraft:stone")), new int[] {5, 9}, new int[] {1, 1});
        assertEquals(0, EditCodec.decode(EditCodec.encode(plain)).gen());
        var withGen = plain.withGen(123456789);
        var back = EditCodec.decode(EditCodec.encode(withGen));
        assertEquals(123456789, back.gen());
        assertEquals(StationSlots.BASE, back.planet());
        assertArrayEquals(new int[] {5, 9}, back.cells());
        assertTrue(EditCodec.encode(withGen).length > EditCodec.encode(plain).length);
        assertEquals(123456789, withGen.withOrigin(0).withDefs(List.of()).gen(), "kept when the hub rewrites the message");
    }
}
