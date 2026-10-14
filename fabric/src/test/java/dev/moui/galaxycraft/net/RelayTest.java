package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayTest {
    static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    static class Rec implements Relay.Sink {
        final List<String> log = new ArrayList<>();

        public void peerState(UUID to, UUID from, PeerState s) {
            log.add("state " + to.getLeastSignificantBits() + "<-" + from.getLeastSignificantBits() + " #" + s.seq());
        }

        public void peerGone(UUID to, UUID gone) {
            log.add("gone " + to.getLeastSignificantBits() + " " + gone.getLeastSignificantBits());
        }

        public void kick(UUID who, String reason) {
            log.add("kick " + who.getLeastSignificantBits());
        }
    }

    static byte[] st(int seq) {
        return Wire.encode(new PeerState(seq, 1, 2, 3, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0));
    }

    @Test
    void stateGoesToEveryoneElse() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B); relay.join(C);
        relay.state(A, st(1), 0);
        assertEquals(List.of("state 2<-1 #1", "state 3<-1 #1"), r.log);
    }

    @Test
    void leaveTellsTheOthers() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        relay.leave(A);
        assertEquals(List.of("gone 2 1"), r.log);
        r.log.clear();
        relay.state(B, st(1), 0);
        assertEquals(List.of(), r.log, "nobody left to send to");
    }

    @Test
    void statesFromStrangersAreIgnored() {
        Rec r = new Rec();
        new Relay(r).state(A, st(1), 0);
        assertEquals(List.of(), r.log);
    }

    @Test
    void rateLimitDropsTheExcess() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        for (int i = 0; i < 100; i++) relay.state(A, st(i), 0); // all in the same instant
        long sent = r.log.stream().filter(s -> s.startsWith("state")).count();
        assertTrue(sent <= 8, "burst only, got " + sent);
        r.log.clear();
        relay.state(A, st(200), 1_000_000_000L); // a second later it flows again
        assertEquals(1, r.log.size());
    }

    @Test
    void fiveMalformedInARowKick() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        for (int i = 0; i < 4; i++) relay.state(A, new byte[3], i * 100_000_000L);
        assertEquals(List.of(), r.log);
        relay.state(A, new byte[3], 500_000_000L);
        assertEquals(List.of("kick 1", "gone 2 1"), r.log);
    }

    @Test
    void aGoodStateResetsTheMalformedCount() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        for (int i = 0; i < 4; i++) relay.state(A, new byte[3], i * 100_000_000L);
        relay.state(A, st(1), 400_000_000L);
        for (int i = 0; i < 4; i++) relay.state(A, new byte[3], (5 + i) * 100_000_000L);
        assertTrue(r.log.stream().noneMatch(s -> s.startsWith("kick")));
    }

    @Test
    void invalidValuesAreDroppedNotRelayed() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        relay.join(A); relay.join(B);
        relay.state(A, Wire.encode(new PeerState(1, Double.NaN, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0)), 0);
        assertEquals(List.of(), r.log);
    }

    @Test
    void ninthPlayerIsKicked() {
        Rec r = new Rec();
        Relay relay = new Relay(r);
        for (int i = 1; i <= Protocol.MAX_PLAYERS; i++) relay.join(new UUID(0, i));
        relay.join(new UUID(0, 99));
        assertEquals(List.of("kick 99"), r.log);
    }
}
