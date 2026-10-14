package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.EditCodec;
import dev.moui.galaxycraft.net.EditSync;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * A guest's end of shared building. What the player breaks or places is already done here (prediction) and
 * is sent to the host; what the host says (its own changes, other guests', the echo of ours) is applied to the
 * planet. A guest never simulates a host planet. All on the client's thread.
 */
final class MpEdits {
    /** Edits that came before their planet was in, per planet, at most this many messages (then the planet is fetched again). */
    private static final int MAX_HELD = 256;
    /** Reconciling which planets we still hold: every this many ticks, a planet unseen this many times in a row is let go. */
    private static final int CHECK_EVERY = 20, MISSES = 3;

    private static final class Held {
        final int index;
        final VoxelPlanet planet;
        final EditSync sync;
        final VoxelPlanet.Listener observer;
        int[] cells = new int[16], ids = new int[16];
        int n, misses;

        Held(int index, VoxelPlanet planet, int snapshotSeq) {
            this.index = index;
            this.planet = planet;
            this.sync = new EditSync(snapshotSeq);
            this.observer = (cell, id) -> {
                PlanetSession s = PlanetClient.sessionOf(index);
                if (s == null || !s.playerEditing() || s.planet() != planet) return;
                if (n == cells.length) {
                    cells = java.util.Arrays.copyOf(cells, n * 2);
                    ids = java.util.Arrays.copyOf(ids, n * 2);
                }
                cells[n] = cell;
                ids[n++] = id;
            };
        }
    }

    private static final Map<Integer, Held> held = new HashMap<>();
    private static final Map<Integer, List<EditCodec.Msg>> early = new HashMap<>();
    /** The host's block texts by its wire ids, and ours: per connection. */
    private static final String[] hostNames = new String[EditCodec.MAX_WIRE_IDS];
    private static final Map<Integer, Integer> wireOf = new HashMap<>();
    private static final List<String> wireNames = new ArrayList<>();
    private static final Set<Integer> told = new HashSet<>();
    private static final Set<String> unknown = new HashSet<>();
    private static int tick;

    private MpEdits() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Edits.TYPE,
                (edits, ctx) -> ctx.client().execute(() -> onEdits(edits)));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Resync.TYPE,
                (r, ctx) -> ctx.client().execute(() -> onResync(r.index())));
    }

    /** The planet is complete in this game (made from the host's copy, or kept from before): its edits are followed. */
    static void attach(int index, PlanetSession session) {
        VoxelPlanet p = session.planet();
        session.setFollower(true);
        if (MpPlanets.seqOf(index) < 0) { // the host is not sending us this planet's edits (we let it go): a fresh copy
            GalaxyCraft.LOG.warn("[GalaxyCraft mp] planet {} came back without its subscription: fetching it again", index);
            PlanetClient.resyncPlanet(index);
            return;
        }
        Held had = held.get(index);
        if (had != null && had.planet == p) {
            had.misses = 0;
            return;
        }
        if (had != null) detach(index, false);
        Held h = new Held(index, p, MpPlanets.seqOf(index));
        held.put(index, h);
        p.addObserver(h.observer);
        List<EditCodec.Msg> waiting = early.remove(index);
        if (waiting != null) for (EditCodec.Msg m : waiting) receive(h, m);
    }

    private static void detach(int index, boolean tellHost) {
        Held h = held.remove(index);
        if (h != null) h.planet.removeObserver(h.observer);
        early.remove(index);
        MpPlanets.forgetFresh(index);
        if (tellHost && ClientPlayNetworking.canSend(MpPayloads.PlanetDrop.TYPE)) ClientPlayNetworking.send(new MpPayloads.PlanetDrop(index));
    }

    private static void onResync(int index) {
        GalaxyCraft.LOG.warn("[GalaxyCraft mp] planet {}: the host cut us off (too far behind); fetching it again", index);
        detach(index, false);
        PlanetClient.resyncPlanet(index);
    }

    private static void onEdits(MpPayloads.Edits edits) {
        EditCodec.Msg m;
        try {
            m = EditCodec.decode(edits.data());
        } catch (EditCodec.Bad bad) {
            GalaxyCraft.LOG.warn("[GalaxyCraft mp] a bad Edits from the host: {}", bad.getMessage());
            return;
        }
        for (EditCodec.Def d : m.defs()) hostNames[d.wireId()] = d.name();
        Held h = held.get(m.planet());
        if (h != null) {
            receive(h, m);
            return;
        }
        if (MpPlanets.seqOf(m.planet()) < 0) return; // a planet we never asked for (or let go): not ours
        List<EditCodec.Msg> list = early.computeIfAbsent(m.planet(), k -> new ArrayList<>());
        if (list.size() >= MAX_HELD) {
            early.remove(m.planet());
            MpPlanets.forgetFresh(m.planet());
            return;
        }
        list.add(m);
    }

    private static void receive(Held h, EditCodec.Msg m) {
        if (!h.sync.accepts(m.seq())) return;
        McBlocks blocks = PlanetClient.blocks();
        if (blocks == null) return;
        int[] ids = new int[m.ids().length];
        for (int i = 0; i < ids.length; i++) {
            String name = hostNames[m.ids()[i]];
            ids[i] = name == null ? -1 : blocks.parse(name);
            if (name != null && ids[i] == Blocks.AIR && !name.equals(blocks.name(Blocks.AIR))) {
                if (unknown.add(name)) GalaxyCraft.LOG.warn("[GalaxyCraft mp] the host has a block this game does not: {}", name);
                ids[i] = -1;
            }
        }
        h.sync.apply(m, ids, (cell, id) -> {
            if (id >= 0 && cell < h.planet.grid.cellCount()) h.planet.setFromRemote(cell, id);
        });
    }

    /** End of a client tick: what the player changed goes to the host; planets we no longer hold are let go. */
    static void tick() {
        if (held.isEmpty()) return;
        McBlocks blocks = PlanetClient.blocks();
        if (blocks != null && ClientPlayNetworking.canSend(MpPayloads.Edits.TYPE))
            for (Held h : held.values()) {
                if (h.n == 0) continue;
                send(h, blocks);
            }
        if (++tick % CHECK_EVERY != 0) return;
        for (Held h : new ArrayList<>(held.values())) {
            PlanetSession s = PlanetClient.sessionOf(h.index);
            boolean live = s != null && s.active() && s.planet() == h.planet;
            GalaxyStream stream = PlanetClient.stream();
            boolean kept = stream != null && stream.recentPlanet(h.index) == h.planet;
            if (live || kept) h.misses = 0;
            else if (++h.misses >= MISSES) detach(h.index, true);
        }
    }

    private static void send(Held h, McBlocks blocks) {
        for (int from = 0; from < h.n; from += EditCodec.MAX_ENTRIES) {
            int n = Math.min(EditCodec.MAX_ENTRIES, h.n - from);
            int cseq = h.sync.nextCseq();
            int[] cells = new int[n], ids = new int[n];
            List<EditCodec.Def> defs = new ArrayList<>();
            int kept = 0;
            for (int i = 0; i < n; i++) {
                Integer w = wireOf.get(h.ids[from + i]);
                if (w == null) {
                    if (wireNames.size() >= EditCodec.MAX_WIRE_IDS) continue; // no more kinds of block to name
                    w = wireNames.size();
                    wireOf.put(h.ids[from + i], w);
                    wireNames.add(blocks.name(h.ids[from + i]));
                }
                if (told.add(w)) defs.add(new EditCodec.Def(w, wireNames.get(w)));
                cells[kept] = h.cells[from + i];
                ids[kept++] = w;
                h.sync.local(h.cells[from + i], cseq);
            }
            if (kept == 0) continue;
            ClientPlayNetworking.send(new MpPayloads.Edits(EditCodec.encode(new EditCodec.Msg(h.index, 0, cseq, defs,
                    java.util.Arrays.copyOf(cells, kept), java.util.Arrays.copyOf(ids, kept)))));
        }
        h.n = 0;
    }

    /** Left the world: nothing is followed, the palettes start again with the next connection. */
    static void reset() {
        for (Held h : held.values()) h.planet.removeObserver(h.observer);
        held.clear();
        early.clear();
        java.util.Arrays.fill(hostNames, null);
        wireOf.clear();
        wireNames.clear();
        told.clear();
        unknown.clear();
    }
}
