package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PlanetXferTest {
    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void etagTellsBytesApart() {
        byte[] a = bytes(1000, 1);
        byte[] b = a.clone();
        assertEquals(PlanetXfer.etag(a), PlanetXfer.etag(b));
        b[500] ^= 1;
        assertNotEquals(PlanetXfer.etag(a), PlanetXfer.etag(b));
        assertNotEquals(PlanetXfer.etag(a), PlanetXfer.etag(new byte[999]));
    }

    @Test
    void aPlanetCutAndPutTogetherIsTheSame() {
        for (int n : new int[] {1, PlanetXfer.CHUNK - 1, PlanetXfer.CHUNK, PlanetXfer.CHUNK + 1, 5 * PlanetXfer.CHUNK + 77}) {
            byte[] data = bytes(n, n);
            var asm = new PlanetXfer.Assembler(n, PlanetXfer.etag(data));
            assertNull(asm.result());
            for (int i = 0; i < PlanetXfer.chunks(n); i++) assertTrue(asm.accept(i, PlanetXfer.slice(data, i)), "chunk " + i + " of " + n);
            assertTrue(asm.done());
            assertArrayEquals(data, asm.result());
        }
    }

    @Test
    void badTransfersAreRefused() {
        byte[] data = bytes(3 * PlanetXfer.CHUNK, 3);
        long etag = PlanetXfer.etag(data);
        assertThrows(IllegalArgumentException.class, () -> new PlanetXfer.Assembler(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlanetXfer.Assembler(PlanetXfer.MAX_BYTES + 1, 0));
        var asm = new PlanetXfer.Assembler(data.length, etag);
        assertFalse(asm.accept(1, PlanetXfer.slice(data, 1)), "out of order");
        assertFalse(asm.accept(0, new byte[10]), "short chunk");
        assertTrue(asm.accept(0, PlanetXfer.slice(data, 0)));
        assertFalse(asm.accept(0, PlanetXfer.slice(data, 0)), "twice");
        assertTrue(asm.accept(1, PlanetXfer.slice(data, 1)));
        byte[] bad = PlanetXfer.slice(data, 2);
        bad[0] ^= 1;
        assertTrue(asm.accept(2, bad));
        assertNull(asm.result(), "corrupt: the etag does not match");
        assertFalse(asm.accept(3, new byte[1]), "more than promised");
    }

    @Test
    void theOutboxLetsOutABudgetPerTickAtLeastOne() {
        var box = new PlanetXfer.Outbox<Integer>();
        for (int i = 0; i < 10; i++) box.add(i, 30_000);
        List<Integer> sent = new ArrayList<>();
        box.drain(PlanetXfer.BUDGET_PER_TICK, sent::add);
        assertEquals(List.of(0, 1, 2), sent, "64 KB lets three of 30 KB out (the last one crosses the line)");
        assertEquals(7 * 30_000L, box.bytes());
        var big = new PlanetXfer.Outbox<String>();
        big.add("huge", 10_000_000);
        List<String> one = new ArrayList<>();
        big.drain(1, one::add);
        assertEquals(List.of("huge"), one);
        assertTrue(big.isEmpty());
        box.clear();
        assertTrue(box.isEmpty());
        assertEquals(0, box.bytes());
    }

    @org.junit.jupiter.api.Test
    void theLaneTheGuestStandsOnGoesFirstAndEachLaneStaysInOrder() {
        PlanetXfer.Outbox<String> box = new PlanetXfer.Outbox<>();
        box.add("2a", 10, 2);
        box.add("2b", 10, 2);
        box.add("5a", 10, 5);
        box.add("5b", 10, 5);
        java.util.List<String> sent = new java.util.ArrayList<>();
        box.drain(1000, sent::add, 5);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("5a", "5b", "2a", "2b"), sent);
        org.junit.jupiter.api.Assertions.assertTrue(box.isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(0, box.bytes());
    }

    @org.junit.jupiter.api.Test
    void withoutAPreferredLaneTheOldestFirstAndBudgetStops() {
        PlanetXfer.Outbox<String> box = new PlanetXfer.Outbox<>();
        box.add("a", 10, 1);
        box.add("b", 10, 1);
        box.add("c", 10, 2);
        java.util.List<String> sent = new java.util.ArrayList<>();
        box.drain(15, sent::add);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("a", "b"), sent); // the second goes: budget was left
        box.drain(15, sent::add);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("a", "b", "c"), sent);
    }

    @org.junit.jupiter.api.Test
    void aBackedUpConnectionSendsLess() {
        org.junit.jupiter.api.Assertions.assertEquals(48_000, PlanetXfer.sendBudget(48_000, 1_000_000));
        org.junit.jupiter.api.Assertions.assertEquals(10_000, PlanetXfer.sendBudget(48_000, 10_000));
        org.junit.jupiter.api.Assertions.assertEquals(0, PlanetXfer.sendBudget(48_000, 0));
    }
}
