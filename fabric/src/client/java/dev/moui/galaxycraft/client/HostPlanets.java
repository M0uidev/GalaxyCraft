package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.EditCodec;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.net.UseCheck;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.PlanetStore;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;

/**
 * The host's end of shared building. A planet some guest holds is <em>shared</em>: the planet object the
 * host's game has (complete, or kept in {@link GalaxyStream}), or one made headless from the bytes sent
 * (a resident) when the host is not there. The shared planet is where guests' edits are applied, in
 * arrival order; every change to it, whoever made it, is sent on to the guests that hold it. All on
 * the client's thread.
 */
final class HostPlanets {
    /** Planets held only for guests (the host's game has them nowhere else) at most. */
    static final int MAX_RESIDENTS = 8;
    /** A planet edited while not complete is saved this long (client ticks, 30 s) after its last edit. */
    private static final int SAVE_AFTER = 600;

    private record Queued(UUID guest, EditCodec.Msg msg) {}

    private static final class Entry {
        final int index;
        VoxelPlanet planet; // null while a resident is being made from its bytes
        final Set<UUID> holders = new HashSet<>();
        final List<Queued> queue = new ArrayList<>();
        int editedTick; // 0: nothing unsaved here (a complete planet saves itself)
        VoxelPlanet.Listener observer;

        Entry(int index) {
            this.index = index;
        }
    }

    private record Key(int planet, UUID author, int cseq) {}

    private static final class Batch {
        int[] cells = new int[16], ids = new int[16];
        int n;

        void add(int cell, int id) {
            if (n == cells.length) {
                cells = java.util.Arrays.copyOf(cells, n * 2);
                ids = java.util.Arrays.copyOf(ids, n * 2);
            }
            cells[n] = cell;
            ids[n++] = id;
        }
    }

    private static final Map<Integer, Entry> shared = new HashMap<>();
    private static final Map<Integer, Integer> seqs = new HashMap<>();
    private static final Map<Key, Batch> batches = new LinkedHashMap<>();
    private static final Object LOCK = new Object();
    private static UUID author;
    private static int authorCseq;
    private static int now;
    /** What the host calls each block when it tells guests (wire id -> text), and back. */
    private static final Map<Integer, Integer> wireOf = new HashMap<>();
    private static final List<String> wireNames = new ArrayList<>();
    /** What each guest calls its blocks (its own wire ids -> text). */
    private static final Map<UUID, String[]> guestNames = new HashMap<>();

    private HostPlanets() {}

    static void register() {
        MpServer.setEditSink(new MpServer.EditSink() {
            @Override
            public void edits(UUID guest, EditCodec.Msg m) {
                Minecraft.getInstance().execute(() -> apply(guest, m));
            }

            @Override
            public void dropped(UUID guest, int planet) {
                Minecraft.getInstance().execute(() -> release(guest, planet));
            }

            @Override
            public void use(UUID guest, MpPayloads.Use u) {
                Minecraft.getInstance().execute(() -> useBlock(guest, u));
            }
        });
    }

    /**
     * A guest right clicked a block: Minecraft's own use of it, in the shadow, as that guest's player (a door opens,
     * a chest opens its menu on the guest's screen). Works for the planet the shadow runs; for any other the click
     * passes at once and the guest places its block. What the shadow changes reaches every guest as an ordinary edit.
     */
    private static void useBlock(UUID guest, MpPayloads.Use u) {
        Entry e = shared.get(u.planet());
        VoxelPlanet p = e == null || !e.holders.contains(guest) ? null : e.planet;
        if (p == null || !UseCheck.valid(u.cell(), p.grid.cellCount(), u.face(), u.hand(), u.hx(), u.hy(), u.hz())
                || !ShadowWorld.runs(p)) {
            MpServer.useDone(guest, u.nonce(), true);
            return;
        }
        ShadowWorld.use(p, u.cell(), u.face(), new net.minecraft.world.phys.Vec3(u.hx(), u.hy(), u.hz()), guest,
                u.hand() == 0 ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND,
                () -> MpServer.useDone(guest, u.nonce(), true), () -> MpServer.useDone(guest, u.nonce(), false));
    }

    private static PlanetSession liveOf(int index) {
        PlanetSession s = PlanetClient.sessionOf(index);
        return s != null && s.active() ? s : null;
    }

    /** The host's sequence for that planet: every edit up to it is in the planet as it is now. */
    static int seq(int index) {
        return seqs.getOrDefault(index, 0);
    }

    /**
     * A guest asks for a planet. Makes it shared (false: there is no room for one more resident). If the host
     * has it unsaved outside a session, it is written first so the bytes sent are current.
     */
    static boolean admit(UUID guest, int index) {
        Entry e = shared.get(index);
        if (e == null) {
            VoxelPlanet have = null;
            PlanetSession live = liveOf(index);
            if (live != null) have = live.planet();
            else if (PlanetClient.stream() != null) have = PlanetClient.stream().recentPlanet(index);
            if (have == null && residents() >= MAX_RESIDENTS) return false;
            e = new Entry(index);
            shared.put(index, e);
            if (have != null) watch(e, have);
        }
        e.holders.add(guest);
        if (e.planet != null && e.editedTick != 0 && liveOf(index) == null) save(e);
        return true;
    }

    private static int residents() {
        int n = 0;
        for (Entry e : shared.values()) {
            if (e.planet != null && liveOf(e.index) != null) continue;
            if (e.planet != null && PlanetClient.stream() != null && PlanetClient.stream().recentPlanet(e.index) == e.planet) continue;
            n++;
        }
        return n;
    }

    private static void watch(Entry e, VoxelPlanet p) {
        e.planet = p;
        int index = e.index;
        e.observer = (cell, id) -> record(index, cell, id);
        p.addObserver(e.observer);
        for (Queued q : e.queue) apply(q.guest(), q.msg());
        e.queue.clear();
    }

    /** The bytes made for a guest are in: a planet nobody holds here yet is made from them. */
    static void supply(int index, byte[] data) {
        Entry e = shared.get(index);
        if (e == null || e.planet != null) return;
        McBlocks blocks = PlanetClient.blocks();
        if (blocks == null) return;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                PlanetStore.Saved s = PlanetStore.decode(data, blocks,
                        name -> PlanetClient.answer(Minecraft.getInstance().submit(() -> blocks.parse(name)), "block " + name));
                VoxelPlanet p = VoxelPlanet.of(new dev.moui.galaxycraft.voxel.CubeSphere(s.n(), s.core(), s.layers()), s.depth(), s.cells(), blocks);
                p.setBiomes(s.biomes());
                return p;
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, PlanetClient.maker).whenComplete((p, ex) -> Minecraft.getInstance().execute(() -> {
            if (shared.get(index) != e || e.planet != null) return;
            if (ex != null) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] planet {} could not be held for guests: {}", index, ex.toString());
                for (UUID g : new ArrayList<>(e.holders)) release(g, index);
                return;
            }
            VoxelPlanet live = liveOf(index) != null ? liveOf(index).planet() : null;
            watch(e, live != null ? live : p);
        }));
    }

    /** The host's game is about to make this planet complete: the shared one, if any, so no copy forms. Null: none, or still being made (wait). */
    static VoxelPlanet planetFor(int index) {
        Entry e = shared.get(index);
        return e == null ? null : e.planet;
    }

    static boolean loading(int index) {
        Entry e = shared.get(index);
        return e != null && e.planet == null;
    }

    /** The planet becomes complete: whether it holds edits not yet saved (then its session must save them). */
    static boolean takeEdited(int index) {
        Entry e = shared.get(index);
        if (e == null || e.editedTick == 0) return false;
        e.editedTick = 0;
        return true;
    }

    private static void release(UUID guest, int index) {
        Entry e = shared.get(index);
        if (e == null) return;
        e.holders.remove(guest);
        if (!e.holders.isEmpty()) return;
        shared.remove(index);
        if (e.planet != null) {
            e.planet.removeObserver(e.observer);
            if (e.editedTick != 0 && liveOf(index) == null) save(e);
        }
    }

    private static void save(Entry e) {
        e.editedTick = 0;
        PlanetClient.entry(e.index).ifPresent(c -> PlanetClient.saveCells(e.index, e.planet, c.center()));
    }

    /** Anything unsaved goes to disk (the world is closing or being saved). */
    static void flushAll() {
        for (Entry e : shared.values())
            if (e.planet != null && e.editedTick != 0 && liveOf(e.index) == null) save(e);
    }

    // ---- what the planet does -> the guests

    private static void record(int index, int cell, int id) {
        synchronized (LOCK) {
            batches.computeIfAbsent(new Key(index, author, authorCseq), k -> new Batch()).add(cell, id);
        }
    }

    private static int wire(int blockId, McBlocks blocks) {
        Integer w = wireOf.get(blockId);
        if (w != null) return w;
        if (wireNames.size() >= EditCodec.MAX_WIRE_IDS) return -1;
        wireOf.put(blockId, wireNames.size());
        wireNames.add(blocks.name(blockId));
        return wireNames.size() - 1;
    }

    /** End of a client tick: what changed goes out, one message per planet and author, in the host's order. */
    static void tick() {
        now++;
        List<Map.Entry<Key, Batch>> out;
        synchronized (LOCK) {
            if (batches.isEmpty()) out = List.of();
            else {
                out = new ArrayList<>(batches.entrySet());
                batches.clear();
            }
        }
        McBlocks blocks = PlanetClient.blocks();
        for (var b : out) flush(b.getKey(), b.getValue(), blocks);
        if (now % 20 == 0)
            for (Entry e : shared.values())
                if (e.planet != null && e.editedTick != 0 && now - e.editedTick > SAVE_AFTER && liveOf(e.index) == null) save(e);
    }

    private static void flush(Key k, Batch b, McBlocks blocks) {
        for (int from = 0; from < b.n; from += EditCodec.MAX_ENTRIES) {
            int n = Math.min(EditCodec.MAX_ENTRIES, b.n - from);
            int[] cells = java.util.Arrays.copyOfRange(b.cells, from, from + n), ids = new int[n];
            Set<Integer> used = new HashSet<>();
            int kept = 0;
            for (int i = 0; i < n; i++) {
                int w = wire(b.ids[from + i], blocks);
                if (w < 0) continue; // out of wire ids: that cell is not told (a limit of 4096 kinds of block)
                cells[kept] = cells[i];
                ids[kept++] = w;
                used.add(w);
            }
            if (kept == 0) continue;
            List<EditCodec.Def> defs = new ArrayList<>();
            for (int w : used) defs.add(new EditCodec.Def(w, wireNames.get(w)));
            int seq = seqs.merge(k.planet(), 1, Integer::sum);
            MpServer.publish(new EditCodec.Msg(k.planet(), seq, k.cseq(), defs, java.util.Arrays.copyOf(cells, kept), java.util.Arrays.copyOf(ids, kept)), k.author());
        }
    }

    // ---- a guest's edit -> the planet

    private static void apply(UUID guest, EditCodec.Msg m) {
        Entry e = shared.get(m.planet());
        McBlocks blocks = PlanetClient.blocks();
        if (e == null || blocks == null || !e.holders.contains(guest)) return;
        if (e.planet == null) {
            if (e.queue.size() < 64) e.queue.add(new Queued(guest, m));
            return;
        }
        String[] names = guestNames.computeIfAbsent(guest, g -> new String[EditCodec.MAX_WIRE_IDS]);
        for (EditCodec.Def d : m.defs()) names[d.wireId()] = d.name();
        VoxelPlanet p = e.planet;
        PlanetSession live = liveOf(e.index);
        if (live != null && live.planet() != p) live = null;
        int bad = 0;
        author = guest;
        authorCseq = m.origin();
        try {
            for (int i = 0; i < m.cells().length; i++) {
                int cell = m.cells()[i];
                if (cell < 0 || cell >= p.grid.cellCount()) {
                    bad++;
                    continue;
                }
                String name = names[m.ids()[i]];
                int id = name == null ? Blocks.AIR : blocks.parse(name);
                if (name == null || id == Blocks.AIR && !name.equals(blocks.name(Blocks.AIR))) {
                    bad++;
                    record(e.index, cell, p.get(cell)); // the guest gets the cell as it is here
                    continue;
                }
                if (live != null) live.applyShared(cell, id);
                else {
                    p.set(cell, id);
                    e.editedTick = now;
                }
                record(e.index, cell, p.get(cell)); // told even if nothing changed: it settles the author's pending cell
            }
        } finally {
            author = null;
            authorCseq = 0;
        }
        if (bad > 0) GalaxyCraft.LOG.warn("[GalaxyCraft mp] {} cells of a guest's edit to planet {} were refused", bad, e.index);
    }

    /** Left the world: nothing is shared any more. */
    static void reset() {
        for (Entry e : shared.values()) if (e.planet != null) e.planet.removeObserver(e.observer);
        shared.clear();
        seqs.clear();
        synchronized (LOCK) {
            batches.clear();
        }
        wireOf.clear();
        wireNames.clear();
        guestNames.clear();
    }
}
