package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class InboundGuardTest {
    @Test
    void anOrdinaryGuestIsNeverRefused() {
        InboundGuard g = new InboundGuard();
        long t = 0;
        for (int i = 0; i < 60 * 25; i++) { // a minute at 25 messages a second
            t += 40_000_000L;
            assertEquals(InboundGuard.Verdict.OK, g.admit(t));
        }
    }

    @Test
    void aJoinBurstPasses() {
        InboundGuard g = new InboundGuard();
        for (int i = 0; i < InboundGuard.BURST; i++) assertEquals(InboundGuard.Verdict.OK, g.admit(0));
    }

    @Test
    void aFloodIsDroppedThenKicked() {
        InboundGuard g = new InboundGuard();
        long t = 0;
        InboundGuard.Verdict v = InboundGuard.Verdict.OK;
        int sent = 0;
        while (v != InboundGuard.Verdict.KICK && sent < 100_000) {
            t += 1_000_000L; // 1000 a second
            v = g.admit(t);
            sent++;
        }
        assertEquals(InboundGuard.Verdict.KICK, v);
        assertNotEquals(100_000, sent);
        assertEquals(true, sent < 2000, "kicked within about two seconds, after " + sent);
    }

    @Test
    void aShortSpikeDoesNotKick() {
        InboundGuard g = new InboundGuard();
        for (int i = 0; i < InboundGuard.BURST + 100; i++) assertNotEquals(InboundGuard.Verdict.KICK, g.admit(0));
        assertEquals(InboundGuard.Verdict.OK, g.admit(5_000_000_000L));
    }
}
