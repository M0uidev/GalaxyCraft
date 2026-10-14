package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditHubTest {
    final List<String> resyncs = new ArrayList<>();
    final EditHub<String> hub = new EditHub<>((g, planet) -> resyncs.add(g + ":" + planet));
    final List<EditCodec.Msg> sent = new ArrayList<>();

    static EditCodec.Msg m(int planet, int seq, int origin, int... cells) {
        int[] ids = new int[cells.length];
        java.util.Arrays.fill(ids, 1);
        return new EditCodec.Msg(planet, seq, origin, List.of(new EditCodec.Def(1, "a")), cells, ids);
    }

    List<EditCodec.Msg> drain(String g) {
        List<EditCodec.Msg> out = new ArrayList<>();
        hub.drain(g, 1 << 20, out::add);
        return out;
    }

    @Test
    void editsWhilePendingComeAfterDeliverAndAboveTheSnapshot() {
        hub.subscribe("g", 4);
        hub.publish(m(4, 1, 0, 10), null); // in the planet's bytes
        hub.publish(m(4, 3, 0, 11), null); // after them
        assertTrue(drain("g").isEmpty(), "nothing before the planet is delivered");
        hub.deliver("g", 4, 2);
        var got = drain("g");
        assertEquals(1, got.size());
        assertEquals(3, got.get(0).seq());
    }

    @Test
    void onlySubscribedGuestsGetEdits() {
        hub.subscribe("g1", 4);
        hub.deliver("g1", 4, 0);
        hub.subscribe("g2", 5);
        hub.deliver("g2", 5, 0);
        hub.publish(m(4, 1, 0, 1), null);
        assertEquals(1, drain("g1").size());
        assertTrue(drain("g2").isEmpty());
        assertTrue(drain("nobody").isEmpty());
    }

    @Test
    void theAuthorKeepsTheOriginOthersGetZero() {
        for (String g : List.of("a", "b")) {
            hub.subscribe(g, 4);
            hub.deliver(g, 4, 0);
        }
        hub.publish(m(4, 5, 9, 1), "a");
        assertEquals(9, drain("a").get(0).origin());
        assertEquals(0, drain("b").get(0).origin());
    }

    @Test
    void defsAreGivenOnce() {
        hub.subscribe("g", 4);
        hub.deliver("g", 4, 0);
        hub.publish(m(4, 1, 0, 1), null);
        hub.publish(m(4, 2, 0, 2), null);
        var got = drain("g");
        assertEquals(1, got.get(0).defs().size());
        assertEquals(0, got.get(1).defs().size());
    }

    @Test
    void aCorrectionGoesOnlyToItsGuest() {
        for (String g : List.of("a", "b")) {
            hub.subscribe(g, 4);
            hub.deliver(g, 4, 0);
        }
        hub.publishTo("a", m(4, 0, 3, 1));
        assertEquals(1, drain("a").size());
        assertTrue(drain("b").isEmpty());
    }

    @Test
    void overTheCapResyncsAndDropsTheSubscription() {
        hub.subscribe("g", 4);
        hub.deliver("g", 4, 0);
        int[] cells = new int[EditCodec.MAX_ENTRIES];
        for (int i = 0; i < cells.length; i++) cells[i] = i;
        for (int seq = 1; seq < 5000 && resyncs.isEmpty(); seq++) hub.publish(m(4, seq, 0, cells), null);
        assertEquals(List.of("g:4"), resyncs);
        assertFalse(hub.subscribed("g", 4));
        assertTrue(drain("g").isEmpty());
        hub.publish(m(4, 9999, 0, 1), null);
        assertTrue(drain("g").isEmpty());
    }

    @Test
    void cancelAndForgetStopEdits() {
        for (String g : List.of("a", "b")) {
            hub.subscribe(g, 4);
            hub.deliver(g, 4, 0);
        }
        hub.cancel("a", 4);
        hub.forget("b");
        hub.publish(m(4, 1, 0, 1), null);
        assertTrue(drain("a").isEmpty());
        assertTrue(drain("b").isEmpty());
        assertFalse(hub.subscribed("a", 4));
    }

    @Test
    void drainRespectsTheBudgetButAlwaysSendsOne() {
        hub.subscribe("g", 4);
        hub.deliver("g", 4, 0);
        for (int i = 1; i <= 5; i++) hub.publish(m(4, i, 0, i), null);
        hub.drain("g", 1, sent::add);
        assertEquals(1, sent.size());
        hub.drain("g", 1 << 20, sent::add);
        assertEquals(5, sent.size());
        assertEquals(List.of(1, 2, 3, 4, 5), sent.stream().map(EditCodec.Msg::seq).toList());
    }

    @Test
    void aGuestsPlanetsAreListed() {
        hub.subscribe("g", 4);
        hub.subscribe("g", 7);
        hub.deliver("g", 4, 0);
        assertEquals(java.util.Set.of(4, 7), new java.util.HashSet<>(hub.planetsOf("g")));
        hub.cancel("g", 4);
        assertEquals(List.of(7), hub.planetsOf("g"));
        assertTrue(hub.planetsOf("nobody").isEmpty());
    }
}
