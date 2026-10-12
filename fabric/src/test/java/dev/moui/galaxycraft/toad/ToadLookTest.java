package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToadLookTest {
    @Test void everyTypeLooksDifferentAtAGlance() {
        Set<Integer> caps = new HashSet<>(), vests = new HashSet<>();
        for (ToadType t : ToadType.values()) {
            caps.add(ToadLook.of(t).cap());
            vests.add(ToadLook.of(t).vest());
        }
        assertEquals(ToadType.values().length, caps.size(), "every type has its own cap colour");
        assertTrue(vests.size() >= 3, "and vests are not all one colour either");
    }

    @Test void everyTypeCarriesItsOwnThing() {
        Set<String> carried = new HashSet<>();
        for (ToadType t : ToadType.values()) {
            assertNotNull(ToadLook.of(t).goods(), t + " carries something");
            assertTrue(carried.add(ToadLook.of(t).goods()), "no two types carry the same thing");
        }
    }

    @Test void thePickelAndItsAnimationsGoTogether() {
        assertEquals("WaitPickel", ToadLook.of(ToadType.MINER).idle());
        assertEquals("TalkPickel", ToadLook.of(ToadType.MINER).talk());
        assertEquals("Wait", ToadLook.of(ToadType.GUIDE).idle());
    }

    @Test void capsAreOpaqueRgba() {
        for (ToadType t : ToadType.values()) assertEquals(0xFF, ToadLook.of(t).cap() & 0xFF);
    }
}
