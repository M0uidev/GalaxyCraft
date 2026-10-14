package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PeerTrackTest {
    static final long MS = 1_000_000L;

    static PeerState at(int seq, double x, float vx) {
        return new PeerState(seq, x, 0, 0, 0, 1, 0, 0, 0, 1, vx, 0, 0, Protocol.MODE_STEVE, 0);
    }

    static PeerState facing(int seq, float upX, float upY, float lookX, float lookZ) {
        return new PeerState(seq, 0, 0, 0, upX, upY, 0, lookX, 0, lookZ, 0, 0, 0, Protocol.MODE_STEVE, 0);
    }

    @Test
    void nothingBeforeTheFirstState() {
        assertNull(new PeerTrack().sample(0));
    }

    @Test
    void interpolatesBetweenTwoStatesOneDelayBehind() {
        PeerTrack t = new PeerTrack();
        t.push(at(1, 0, 10), 0);
        t.push(at(2, 1, 10), 100 * MS);
        // at now=150ms we show the moment 50ms into the interval: halfway
        assertEquals(0.5, t.sample(150 * MS).x(), 1e-9);
    }

    @Test
    void extrapolatesWithVelocityThenHolds() {
        PeerTrack t = new PeerTrack();
        t.push(at(1, 0, 10), 0);
        t.push(at(2, 1, 10), 100 * MS);
        // target = now-100ms = 200ms: 100ms after the newest -> 1 + 10*0.1
        assertEquals(2.0, t.sample(300 * MS).x(), 1e-6);
        // past the extrapolation limit: capped at 250ms of travel
        assertEquals(1 + 10 * 0.25, t.sample(2000 * MS).x(), 1e-6);
    }

    @Test
    void hidesAfterFiveSecondsOfSilence() {
        PeerTrack t = new PeerTrack();
        t.push(at(1, 0, 0), 0);
        assertNotNull(t.sample(4_900 * MS));
        assertNull(t.sample(5_100 * MS));
    }

    @Test
    void oldAndDuplicateSequencesAreIgnored() {
        PeerTrack t = new PeerTrack();
        t.push(at(5, 5, 0), 0);
        t.push(at(4, 99, 0), 10 * MS);
        t.push(at(5, 99, 0), 20 * MS);
        assertEquals(5, t.sample(300 * MS).x(), 1e-9);
    }

    @Test
    void sequenceWrapAroundStillCountsAsNewer() {
        PeerTrack t = new PeerTrack();
        t.push(at(Integer.MAX_VALUE, 1, 0), 0);
        t.push(at(Integer.MIN_VALUE, 2, 0), 100 * MS);
        assertEquals(2, t.sample(1000 * MS).x(), 1e-9);
    }

    @Test
    void invalidStatesNeverEnter() {
        PeerTrack t = new PeerTrack();
        t.push(new PeerState(1, Double.NaN, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0), 0);
        assertNull(t.sample(10 * MS));
    }

    @Test
    void upVectorTurnsTheShortWayAndStaysUnit() {
        PeerTrack t = new PeerTrack();
        t.push(facing(1, 0, 1, 0, 1), 0);
        t.push(facing(2, 1, 0, 0, 1), 100 * MS);
        PeerTrack.Pose p = t.sample(150 * MS);
        assertEquals(1.0, Math.hypot(Math.hypot(p.upX(), p.upY()), p.upZ()), 1e-6);
        assertEquals(p.upX(), p.upY(), 1e-6, "halfway between +Y and +X");
    }

    @Test
    void oppositeUpVectorsDoNotDivideByZero() {
        PeerTrack t = new PeerTrack();
        t.push(facing(1, 0, 1, 0, 1), 0);
        t.push(facing(2, 0, -1, 0, 1), 100 * MS);
        PeerTrack.Pose p = t.sample(150 * MS);
        assertTrue(Double.isFinite(p.upX()) && Double.isFinite(p.upY()) && Double.isFinite(p.upZ()));
        assertEquals(1.0, Math.hypot(Math.hypot(p.upX(), p.upY()), p.upZ()), 1e-6);
    }

    @Test
    void lookTurnsTheShortWayAndStaysUnit() {
        PeerTrack t = new PeerTrack();
        t.push(facing(1, 0, 1, 0, 1), 0); // looking +Z
        t.push(facing(2, 0, 1, 1, 0), 100 * MS); // looking +X
        PeerTrack.Pose p = t.sample(150 * MS);
        assertEquals(1.0, Math.hypot(Math.hypot(p.lookX(), p.lookY()), p.lookZ()), 1e-6);
        assertEquals(p.lookX(), p.lookZ(), 1e-6, "halfway between +Z and +X");
    }

    @Test
    void aPeerExactlyOnTheSamePointIsFine() {
        PeerTrack t = new PeerTrack();
        t.push(at(1, 0, 0), 0);
        t.push(at(2, 0, 0), 100 * MS);
        PeerTrack.Pose p = t.sample(150 * MS);
        assertEquals(0, p.x(), 0);
        assertTrue(Double.isFinite(p.upY()));
    }
}
