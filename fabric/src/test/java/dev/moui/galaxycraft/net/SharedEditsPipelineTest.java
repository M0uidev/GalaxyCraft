package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeBlocks;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * The whole edit path without Minecraft: guests' planets and the host's, joined by the real codec, hub and sync.
 * What the glue does (HostPlanets, MpEdits) is repeated here in a few lines, so the rules it relies on are tested.
 */
class SharedEditsPipelineTest {
    static final int PLANET = 0;

    final Blocks blocks = CubeBlocks.INSTANCE;
    final VoxelPlanet host = VoxelPlanet.ofRadius(12, blocks);
    final EditHub<String> hub = new EditHub<>((g, p) -> fail("no resync expected"));
    int hseq;
    final Map<Integer, Integer> hostWire = new HashMap<>();
    final List<String> hostNames = new ArrayList<>();
    // the batch being collected on the host: author -> (cells, ids)
    String author;
    int authorCseq;
    final List<int[]> collected = new ArrayList<>(); // {cell, id}

    final class Guest {
        final String name;
        VoxelPlanet planet;
        EditSync sync;
        final String[] hostPalette = new String[EditCodec.MAX_WIRE_IDS];
        final Map<Integer, Integer> wireOf = new HashMap<>();
        final List<String> names = new ArrayList<>();
        final java.util.Set<Integer> told = new java.util.HashSet<>();

        Guest(String name) {
            this.name = name;
        }

        /** The planet as the host has it now, with the host's sequence of that moment. */
        void download() {
            hub.subscribe(name, PLANET);
            int snapshot = hseq;
            planet = VoxelPlanet.ofRadius(12, blocks);
            System.arraycopy(host.cells(), 0, planet.cells(), 0, planet.cells().length);
            // (the real game reads the cells again from bytes; the same content)
            sync = new EditSync(snapshot);
            hub.deliver(name, PLANET, snapshot);
        }

        /** The player changes a cell: done here at once, sent to the host. */
        void edit(int cell, int id) {
            planet.set(cell, id);
            int cseq = sync.nextCseq();
            sync.local(cell, cseq);
            Integer w = wireOf.get(id);
            List<EditCodec.Def> defs = new ArrayList<>();
            if (w == null) {
                w = names.size();
                wireOf.put(id, w);
                names.add(blocks.name(id));
            }
            if (told.add(w)) defs.add(new EditCodec.Def(w, names.get(w)));
            byte[] wire = EditCodec.encode(new EditCodec.Msg(PLANET, 0, cseq, defs, new int[] {cell}, new int[] {w}));
            hostReceives(this, wire);
        }

        void pump() throws Exception {
            List<EditCodec.Msg> got = new ArrayList<>();
            hub.drain(name, 1 << 20, got::add);
            for (EditCodec.Msg m : got) {
                EditCodec.Msg back = EditCodec.decode(EditCodec.encode(m)); // through the wire
                for (var d : back.defs()) hostPalette[d.wireId()] = d.name();
                if (!sync.accepts(back.seq())) continue;
                int[] ids = new int[back.ids().length];
                for (int i = 0; i < ids.length; i++) ids[i] = blocks.parse(hostPalette[back.ids()[i]]);
                sync.apply(back, ids, planet::setFromRemote);
            }
        }
    }

    final Map<String, String[]> guestNames = new HashMap<>();

    void hostReceives(Guest from, byte[] wire) {
        try {
            EditCodec.Msg m = EditCodec.decode(wire);
            String[] names = guestNames.computeIfAbsent(from.name, k -> new String[EditCodec.MAX_WIRE_IDS]);
            for (var d : m.defs()) names[d.wireId()] = d.name();
            author = from.name;
            authorCseq = m.origin();
            for (int i = 0; i < m.cells().length; i++) {
                int id = blocks.parse(names[m.ids()[i]]);
                host.set(m.cells()[i], id);
                collected.add(new int[] {m.cells()[i], host.get(m.cells()[i])});
            }
            flush();
        } catch (EditCodec.Bad e) {
            fail(e);
        } finally {
            author = null;
            authorCseq = 0;
        }
    }

    /** What HostPlanets.tick does: one message for what was collected, in the host's order. */
    void flush() {
        if (collected.isEmpty()) return;
        int n = collected.size();
        int[] cells = new int[n], ids = new int[n];
        List<EditCodec.Def> defs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = collected.get(i)[1];
            Integer w = hostWire.get(id);
            if (w == null) {
                w = hostNames.size();
                hostWire.put(id, w);
                hostNames.add(blocks.name(id));
            }
            final int ww = w;
            if (defs.stream().noneMatch(d -> d.wireId() == ww)) defs.add(new EditCodec.Def(w, hostNames.get(w)));
            cells[i] = collected.get(i)[0];
            ids[i] = w;
        }
        collected.clear();
        hub.publish(new EditCodec.Msg(PLANET, ++hseq, authorCseq, defs, cells, ids), author);
    }

    /** The host's own change (its player, a leaf falling): no author. */
    void hostEdit(int cell, int id) {
        host.set(cell, id);
        collected.add(new int[] {cell, id});
        flush();
    }

    int stoneCell() {
        return host.grid.cellAt(new Vector3d(0, 11.5, 0));
    }

    int obsidian() {
        return blocks.id(Material.OBSIDIAN);
    }

    @Test
    void aGuestsBreakReachesTheHostAndEveryoneElse() throws Exception {
        Guest a = new Guest("a"), b = new Guest("b");
        a.download();
        b.download();
        int cell = stoneCell();
        assertNotEquals(Blocks.AIR, host.get(cell));
        a.edit(cell, Blocks.AIR);
        a.pump();
        b.pump();
        assertEquals(Blocks.AIR, host.get(cell));
        assertEquals(Blocks.AIR, b.planet.get(cell));
        assertFalse(a.sync.hasPending(), "the echo settled the author");
        assertEquals(Blocks.AIR, a.planet.get(cell));
    }

    @Test
    void theHostsOwnChangesReachGuests() throws Exception {
        Guest a = new Guest("a");
        a.download();
        int cell = stoneCell();
        hostEdit(cell, obsidian());
        a.pump();
        assertEquals(obsidian(), a.planet.get(cell));
    }

    @Test
    void bothChangeTheSameCellAndEveryoneEndsWithTheLastToArriveAtTheHost() throws Exception {
        Guest a = new Guest("a"), b = new Guest("b");
        a.download();
        b.download();
        int cell = stoneCell();
        a.edit(cell, Blocks.AIR);       // A breaks it...
        b.edit(cell, obsidian());       // ...and B places obsidian there, before hearing from the host
        a.pump();
        b.pump();
        assertEquals(obsidian(), host.get(cell), "host order: B's came last");
        assertEquals(obsidian(), a.planet.get(cell));
        assertEquals(obsidian(), b.planet.get(cell));
        assertFalse(a.sync.hasPending());
        assertFalse(b.sync.hasPending());
    }

    @Test
    void anEditWhileThePlanetDownloadsIsAppliedAfterIt() throws Exception {
        Guest a = new Guest("a"), late = new Guest("late");
        a.download();
        int c1 = stoneCell(), c2 = c1;
        assertNotEquals(Blocks.AIR, host.get(c1));
        // late asks (subscribed, pending); its bytes are taken at this moment...
        hub.subscribe("late", PLANET);
        int snapshot = hseq;
        late.planet = VoxelPlanet.ofRadius(12, blocks);
        System.arraycopy(host.cells(), 0, late.planet.cells(), 0, late.planet.cells().length);
        late.sync = new EditSync(snapshot);
        // ...and an edit happens before the bytes are delivered
        a.edit(c2, Blocks.AIR);
        late.pump();
        assertNotEquals(Blocks.AIR, late.planet.get(c2), "nothing before the planet is delivered");
        hub.deliver("late", PLANET, snapshot);
        late.pump();
        assertEquals(Blocks.AIR, late.planet.get(c2));
    }

    @Test
    void anEditAlreadyInTheBytesIsNotAppliedTwiceOutOfOrder() throws Exception {
        Guest a = new Guest("a");
        a.download();
        int cell = stoneCell();
        hostEdit(cell, Blocks.AIR); // seq 1, in the bytes of someone who asks now
        hub.subscribe("late", PLANET);
        int snapshot = hseq;
        Guest late = new Guest("late");
        late.planet = VoxelPlanet.ofRadius(12, blocks);
        System.arraycopy(host.cells(), 0, late.planet.cells(), 0, late.planet.cells().length);
        late.sync = new EditSync(snapshot);
        hostEdit(cell, obsidian()); // seq 2, after
        hub.deliver("late", PLANET, snapshot);
        late.pump();
        assertEquals(obsidian(), late.planet.get(cell));
    }

    @Test
    void manyCellsInOneBatchTravelTogether() throws Exception {
        Guest a = new Guest("a");
        a.download();
        int base = stoneCell();
        for (int i = 0; i < 50; i++) hostEdit(base + i, i % 2 == 0 ? Blocks.AIR : obsidian());
        a.pump();
        for (int i = 0; i < 50; i++) assertEquals(host.get(base + i), a.planet.get(base + i), "cell " + i);
    }
}
