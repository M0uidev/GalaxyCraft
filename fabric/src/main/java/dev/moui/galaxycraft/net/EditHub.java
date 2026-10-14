package dev.moui.galaxycraft.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The server's end of shared edits: which guest holds which planet, the edits waiting for each guest
 * and their order. Pure (no Minecraft), on one thread. A planet is first <em>pending</em>: edits that
 * arrive before its bytes are delivered are kept, and after delivery those newer than the bytes follow
 * them. A guest too far behind is cut off that planet with a resync.
 */
public final class EditHub<G> {
    /** Edits waiting for one guest, over this many bytes (estimated): it is resynced on the planet that overflowed. */
    public static final long MAX_QUEUE_BYTES = 8L << 20;

    public interface Sink<G> {
        void resync(G guest, int planet);
    }

    private static final class Sub {
        boolean live;
        final List<EditCodec.Msg> buffer = new ArrayList<>();
    }

    private static final class Peer {
        final Map<Integer, Sub> subs = new HashMap<>();
        final Set<Integer> known = new HashSet<>();
        final ArrayDeque<EditCodec.Msg> queue = new ArrayDeque<>();
        long bytes;
    }

    private final Map<G, Peer> peers = new HashMap<>();
    private final Sink<G> sink;

    public EditHub(Sink<G> sink) {
        this.sink = sink;
    }

    static long size(EditCodec.Msg m) {
        return m.cells().length * 3L + m.defs().size() * 40L + 16;
    }

    /** The guest asked for the planet: edits are kept for it from now on, until its bytes are delivered. */
    public void subscribe(G g, int planet) {
        Peer p = peers.computeIfAbsent(g, k -> new Peer());
        Sub old = p.subs.put(planet, new Sub());
        if (old != null) for (var m : old.buffer) p.bytes -= size(m);
    }

    /** The planets the guest holds (pending or live). */
    public List<Integer> planetsOf(G g) {
        Peer p = peers.get(g);
        return p == null ? List.of() : new ArrayList<>(p.subs.keySet());
    }

    public boolean subscribed(G g, int planet) {
        Peer p = peers.get(g);
        return p != null && p.subs.containsKey(planet);
    }

    /** The planet's bytes (taken at edit sequence snapshotSeq) are queued for the guest: the newer edits kept so far follow them. */
    public void deliver(G g, int planet, int snapshotSeq) {
        Peer p = peers.get(g);
        Sub s = p == null ? null : p.subs.get(planet);
        if (s == null) return;
        s.live = true;
        List<EditCodec.Msg> held = new ArrayList<>(s.buffer);
        s.buffer.clear();
        for (var m : held) {
            p.bytes -= size(m);
            if (m.seq() > snapshotSeq) enqueue(g, p, m);
        }
    }

    public void cancel(G g, int planet) {
        Peer p = peers.get(g);
        if (p != null) remove(p, planet);
    }

    public void forget(G g) {
        peers.remove(g);
    }

    /** An edit of a planet (from the host's game, in its order); the author's copy keeps the origin, the others' has 0. */
    public void publish(EditCodec.Msg m, G author) {
        for (var e : new ArrayList<>(peers.entrySet())) {
            Peer p = e.getValue();
            Sub s = p.subs.get(m.planet());
            if (s == null) continue;
            EditCodec.Msg mine = e.getKey().equals(author) ? m : m.withOrigin(0);
            if (s.live) enqueue(e.getKey(), p, mine);
            else {
                s.buffer.add(mine);
                p.bytes += size(mine);
                if (p.bytes > MAX_QUEUE_BYTES) overflow(e.getKey(), p, m.planet());
            }
        }
    }

    /** A message for one guest only (a correction of its edit). */
    public void publishTo(G g, EditCodec.Msg m) {
        Peer p = peers.get(g);
        Sub s = p == null ? null : p.subs.get(m.planet());
        if (s == null) return;
        if (s.live) enqueue(g, p, m);
        else {
            s.buffer.add(m);
            p.bytes += size(m);
        }
    }

    /** Sends from the front while the budget lasts (the first always goes). Each carries only the defs the guest lacks. */
    public void drain(G g, int budget, Consumer<EditCodec.Msg> send) {
        Peer p = peers.get(g);
        if (p == null) return;
        while (!p.queue.isEmpty() && budget > 0) {
            EditCodec.Msg m = p.queue.poll();
            long sz = size(m);
            p.bytes -= sz;
            budget -= (int) Math.min(sz, Integer.MAX_VALUE);
            List<EditCodec.Def> fresh = new ArrayList<>();
            for (var d : m.defs()) if (p.known.add(d.wireId())) fresh.add(d);
            send.accept(fresh.size() == m.defs().size() ? m : m.withDefs(fresh));
        }
    }

    private void enqueue(G g, Peer p, EditCodec.Msg m) {
        p.queue.add(m);
        p.bytes += size(m);
        if (p.bytes > MAX_QUEUE_BYTES) overflow(g, p, m.planet());
    }

    private void overflow(G g, Peer p, int planet) {
        remove(p, planet);
        sink.resync(g, planet);
    }

    private void remove(Peer p, int planet) {
        Sub s = p.subs.remove(planet);
        if (s != null) for (var m : s.buffer) p.bytes -= size(m);
        for (var it = p.queue.iterator(); it.hasNext(); ) {
            var m = it.next();
            if (m.planet() == planet) {
                it.remove();
                p.bytes -= size(m);
            }
        }
    }
}
