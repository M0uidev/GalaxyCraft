package dev.moui.galaxycraft.launchstar;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.launchstar.StarOps.Kind;
import dev.moui.galaxycraft.launchstar.StarOps.Request;
import dev.moui.galaxycraft.net.StarCodec;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StarOpsTest {
    final AtomicInteger n = new AtomicInteger();
    final StarStore host = StarStore.memory();

    Request place(double x, String body, String code) {
        return new Request(StarOps.PLACE, "guest-chose-this", new double[] {x, 0, 0}, new double[] {0, 2, 0}, "Space", body, code);
    }

    StarOps.Result apply(Request r) {
        return StarOps.apply(host, r, () -> "id" + n.incrementAndGet(), 0.01);
    }

    @Test void placeUsesTheHostsIdAndNormalisesUp() {
        var res = apply(place(1, "p1", null));
        assertEquals(Kind.PUT, res.kind());
        assertEquals("id1", res.star().id());
        assertEquals(1, res.star().up()[1], 1e-9);
        assertEquals(1, host.all().size());
    }

    @Test void secondPlaceOnAnotherBodyLinks() {
        apply(place(1, "p1", null));
        var b = apply(place(5, "p2", null)).star();
        assertEquals("id1", host.partnerOf(b.id()).orElseThrow().id());
    }

    @Test void crowdedOrBadPlacesAreRejected() {
        apply(place(1, "p1", null));
        assertEquals(Kind.REJECT, apply(place(1.001, "p1", null)).kind());
        assertEquals(Kind.REJECT, apply(place(Double.NaN, "p1", null)).kind());
        assertEquals(Kind.REJECT, apply(new Request(StarOps.PLACE, "", new double[] {9, 0, 0}, new double[] {0, 0, 0}, "Space", "p", null)).kind());
        assertEquals(Kind.REJECT, apply(place(9, "x".repeat(StarOps.MAX_NAME + 1), null)).kind());
        assertEquals(1, host.all().size());
    }

    @Test void pickupRemovesOnceThenLosesTheRace() {
        String id = apply(place(1, "p1", null)).star().id();
        var first = apply(new Request(StarOps.PICKUP, id, null, null, null, null, null));
        assertEquals(Kind.GONE, first.kind());
        assertEquals(id, first.star().id());
        assertEquals(Kind.REJECT, apply(new Request(StarOps.PICKUP, id, null, null, null, null, null)).kind());
    }

    @Test void codeChangesAndBlankIsRefused() {
        String id = apply(place(1, "p1", null)).star().id();
        var res = apply(new Request(StarOps.CODE, id, null, null, null, null, "  hello  "));
        assertEquals(Kind.PUT, res.kind());
        assertEquals("hello", res.star().code());
        assertEquals(Kind.REJECT, apply(new Request(StarOps.CODE, id, null, null, null, null, " ")).kind());
        assertEquals(Kind.REJECT, apply(new Request(StarOps.CODE, "nope", null, null, null, null, "x")).kind());
    }

    @Test void mirrorFollowsTheHost() throws Exception {
        StarStore mirror = StarStore.memory();
        var a = apply(place(1, "p1", null));
        var b = apply(place(5, "p2", null));
        mirror.replaceAll(StarCodec.decodeList(StarCodec.encodeList(host.all())));
        assertEquals(2, mirror.all().size());
        assertTrue(mirror.partnerOf(a.star().id()).isPresent());
        var renamed = apply(new Request(StarOps.CODE, b.star().id(), null, null, null, null, "zz")).star();
        mirror.putExact(StarCodec.decode(StarCodec.encode(renamed)));
        assertEquals("zz", mirror.get(b.star().id()).code());
        assertTrue(mirror.partnerOf(a.star().id()).isEmpty());
        mirror.remove(a.star().id());
        assertEquals(1, mirror.all().size());
    }

    @Test void capsTheNumberOfStars() {
        for (int i = 0; i < StarOps.MAX_STARS; i++) assertEquals(Kind.PUT, apply(place(i * 10 + 1, "p" + i, "c" + i)).kind());
        assertEquals(Kind.REJECT, apply(place(99999, "pz", "cz")).kind());
    }

    @Test void memoryStoreSavesNothing() {
        assertDoesNotThrow(() -> StarStore.memory().save());
    }
}
