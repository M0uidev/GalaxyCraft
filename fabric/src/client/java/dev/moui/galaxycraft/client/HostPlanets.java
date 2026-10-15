package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.EditCodec;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.net.StationSlots;
import dev.moui.galaxycraft.net.UseCheck;
import dev.moui.galaxycraft.shadow.ShadowColumns;
import dev.moui.galaxycraft.shadow.ShadowMap;
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
        /** A station's: its planet is this one's (replaced when the station grows a new grid). */
        dev.moui.galaxycraft.voxel.Station station;
        /** A station that grew and let its guests go: they ask again, or this is dropped (client tick it was left). */
        int orphaned;
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
        ShadowWorld.rideSink(RIDE_SINK);
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

            @Override
            public void takeDrop(UUID guest, MpPayloads.DropTake t) {
                Minecraft.getInstance().execute(() -> {
                    Entry e = shared.get(t.planet());
                    if (e == null || !e.holders.contains(guest) || e.planet == null) return;
                    if (e.planet == focusPlanet()) PlanetClient.drops().take(guest, t.id());
                    else takeRunDrop(guest, e, t.id());
                });
            }

            @Override
            public void where(UUID guest, MpPayloads.Where w) {
                Minecraft.getInstance().execute(() -> guestWhere(guest, w));
            }

            @Override
            public void throwItem(UUID guest, MpPayloads.Throw t) {
                Minecraft.getInstance().execute(() -> throwFor(guest, t));
            }

            @Override
            public void steer(UUID guest, int keys) {
                ShadowWorld.guestSteer(guest, new ShadowWorld.Steer((keys & MpPayloads.Steer.FORWARD) != 0, (keys & MpPayloads.Steer.BACK) != 0,
                        (keys & MpPayloads.Steer.LEFT) != 0, (keys & MpPayloads.Steer.RIGHT) != 0, (keys & MpPayloads.Steer.SNEAK) != 0));
            }

            @Override
            public void breakBlock(UUID guest, MpPayloads.Break b) {
                Minecraft.getInstance().execute(() -> breakFor(guest, b));
            }

            @Override
            public void mobHit(UUID guest, MpPayloads.MobHit h) {
                Minecraft.getInstance().execute(() -> hitMob(guest, h));
            }
        });
    }

    /** The shadow's word about a guest's ride goes to that guest (the shadow runs on the server thread: the planet's index is read on the client thread). */
    static final ShadowWorld.RideSink RIDE_SINK = new ShadowWorld.RideSink() {
        @Override
        public void seat(UUID guest, VoxelPlanet planet, Vector3d pos) {
            Minecraft.getInstance().execute(() -> {
                for (Entry e : shared.values())
                    if (e.planet == planet && e.holders.contains(guest)) {
                        MpServer.rideSeat(guest, new MpPayloads.RideSeat(e.index, (float) pos.x, (float) pos.y, (float) pos.z));
                        return;
                    }
            });
        }

        @Override
        public void end(UUID guest) {
            MpServer.rideEnd(guest);
        }
    };

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

    /** A guest hit or used a mob of the shadow: as that guest's player (its weapon), from the way it looks. */
    private static void hitMob(UUID guest, MpPayloads.MobHit h) {
        Entry e = shared.get(h.planet());
        VoxelPlanet p = e == null || !e.holders.contains(guest) ? null : e.planet;
        if (p == null || !ShadowWorld.runs(p)) return;
        if (h.kind() == 0) ShadowWorld.attack(h.entity(), guest, new Vector3d(h.lx(), h.ly(), h.lz()));
        else ShadowWorld.interact(h.entity(), guest);
    }

    // ---- guests' presence in the shadow

    /** Blocks around a guest that Minecraft runs, as around the host's Mario. */
    private static final double GUEST_RANGE = ShadowLink.RANGE;
    private static final int GUEST_FOLLOW_TICKS = 20, GUEST_LOST_TICKS = 100;

    private static final class Presence {
        int planet = -1, heard, followed;
        VoxelPlanet on;
    }

    private static final Map<UUID, Presence> presence = new HashMap<>();
    /** Planets whose edits go to a shadow run kept for guests: the observer that sends them there. */
    private static final Map<VoxelPlanet, VoxelPlanet.Listener> shadowFeeds = new HashMap<>();

    /** A guest says where it is: on a planet it holds, the host's shadow runs that planet and puts a stand-in there. */
    private static void guestWhere(UUID guest, MpPayloads.Where w) {
        Presence pr = presence.computeIfAbsent(guest, g -> new Presence());
        pr.heard = now;
        if (w.planet() < 0) {
            leave(guest, pr);
            return;
        }
        Entry e = shared.get(w.planet());
        VoxelPlanet p = e == null || !e.holders.contains(guest) ? null : e.planet;
        if (p == null || !ShadowWorld.available()) {
            leave(guest, pr);
            return;
        }
        if (pr.on != p) {
            leave(guest, pr);
            pr.on = p;
            pr.planet = w.planet();
            pr.followed = GUEST_FOLLOW_TICKS;
        }
        ShadowWorld.runFor(p, e.station != null ? "station-" + e.station.id : "mp" + w.planet()); // a station's strip is the host's own
        if (!shadowFeeds.containsKey(p)) { // what changes on the planet reaches the shadow that runs for the guests
            VoxelPlanet.Listener feed = (cell, id) -> ShadowWorld.setQuiet(p, cell, id);
            shadowFeeds.put(p, feed);
            p.addObserver(feed);
        }
        Vector3d feet = new Vector3d(w.fx(), w.fy(), w.fz());
        ShadowWorld.guest(guest, new ShadowWorld.MarioAt(p, feet, new Vector3d(w.lx(), w.ly(), w.lz()), guest));
        ShadowMap map = ShadowWorld.mapOf(p);
        if (map != null && ++pr.followed >= GUEST_FOLLOW_TICKS) {
            pr.followed = 0;
            ShadowWorld.follow(p, guest, ShadowColumns.near(p, map, feet, GUEST_RANGE), p.grid.cellAt(feet));
        }
    }

    /** The guests standing on planet p. */
    private static List<UUID> guestsOn(VoxelPlanet p) {
        List<UUID> out = new ArrayList<>();
        for (var en : presence.entrySet()) if (en.getValue().on == p) out.add(en.getKey());
        return out;
    }

    // ---- dropped items on planets the host's game is not on

    private static final Map<VoxelPlanet, dev.moui.galaxycraft.voxel.PlanetDrops<net.minecraft.world.item.ItemStack>> runDrops = new HashMap<>();

    /** The shadow dropped an item on a planet that is not the host's focus: it lies there for the guests on it. */
    static void foreignDrop(ShadowWorld.Drop d) {
        if (d.planet() == null || guestsOn(d.planet()).isEmpty()) return;
        runDrops.computeIfAbsent(d.planet(), k -> new dev.moui.galaxycraft.voxel.PlanetDrops<>()).add(d.pos(), d.vel(), d.stack(), d.delay());
    }

    private static void sendRunDrops(Entry e, List<UUID> there) {
        var drops = runDrops.get(e.planet);
        List<MpPayloads.DropItem> items = new ArrayList<>();
        if (drops != null)
            for (var d : drops.all()) {
                if (items.size() >= MpPayloads.MAX_DROPS) break;
                items.add(new MpPayloads.DropItem(d.id, (float) d.pos.x, (float) d.pos.y, (float) d.pos.z, (float) d.vel.x, (float) d.vel.y,
                        (float) d.vel.z, d.delay, d.item));
            }
        MpPayloads.Drops body = new MpPayloads.Drops(e.index, items);
        for (UUID g : there) MpServer.drops(g, body);
    }

    /** A guest stepped on a drop of a planet that is not the host's focus. */
    private static void takeRunDrop(UUID guest, Entry e, int id) {
        var drops = runDrops.get(e.planet);
        if (drops == null) return;
        for (var it = drops.all().iterator(); it.hasNext(); ) {
            var d = it.next();
            if (d.id != id) continue;
            if (d.delay > 0) return;
            it.remove();
            Vector3d at = new Vector3d(d.pos);
            ShadowWorld.give(guest, d.item, left -> {
                var again = runDrops.get(e.planet);
                if (again != null) again.add(at, new Vector3d(), left, DropsClient.RETRY_DELAY);
            });
            return;
        }
    }

    private static void leave(UUID guest, Presence pr) {
        if (pr.on != null) {
            ShadowWorld.follow(pr.on, guest, java.util.Set.of(), -1);
            VoxelPlanet was = pr.on;
            pr.on = null;
            pr.planet = -1;
            ShadowWorld.guest(guest, null);
            boolean others = false;
            for (Presence o : presence.values()) others |= o.on == was;
            VoxelPlanet.Listener feed = others ? null : shadowFeeds.remove(was);
            if (feed != null) was.removeObserver(feed);
        } else {
            ShadowWorld.guest(guest, null);
        }
    }

    /** Client tick: a guest that stopped saying where it is has left the planet. */
    private static void presenceTick() {
        for (var en : presence.entrySet())
            if (en.getValue().on != null && now - en.getValue().heard > GUEST_LOST_TICKS) leave(en.getKey(), en.getValue());
    }

    /** The shadow changed a cell of a planet that runs only for guests (the host's own goes through ShadowLink). */
    static void shadowChange(ShadowWorld.Change c) {
        for (Entry e : shared.values()) {
            if (e.planet != c.planet() || e.planet == null) continue;
            PlanetSession live = liveOf(e.index);
            if (live != null && live.planet() != e.planet) live = null;
            if (live != null) live.applyShared(c.cell(), c.id());
            else {
                e.planet.set(c.cell(), c.id());
                e.editedTick = now;
            }
            return;
        }
    }

    /** A guest's Q: the item leaves its inventory and lies on the planet where it was thrown (if the shadow runs it; else it stays in hand). */
    private static void throwFor(UUID guest, MpPayloads.Throw t) {
        Entry e = shared.get(t.planet());
        VoxelPlanet p = e == null || !e.holders.contains(guest) ? null : e.planet;
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (p == null || server == null || !ShadowWorld.runs(p)) return;
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(guest);
            if (sp == null) return;
            net.minecraft.world.item.ItemStack stack = sp.getInventory().removeFromSelected(t.all());
            if (!stack.isEmpty()) ShadowWorld.throwOnto(p, new Vector3d(t.x(), t.y(), t.z()), new Vector3d(t.vx(), t.vy(), t.vz()), stack);
        });
    }

    /** The planet the host's game is on (its drops are the shared ones), or null. */
    private static VoxelPlanet focusPlanet() {
        dev.moui.galaxycraft.shadow.ShadowWorld.Entities es = dev.moui.galaxycraft.shadow.ShadowWorld.entities();
        return es == null ? null : es.planet();
    }

    /** A guest breaks a block: the shadow does it as that player (drops, tool wear); if the shadow is elsewhere, it is the guest's to do. */
    private static void breakFor(UUID guest, MpPayloads.Break b) {
        Entry e = shared.get(b.planet());
        VoxelPlanet p = e == null || !e.holders.contains(guest) ? null : e.planet;
        if (p == null || b.cell() >= p.grid.cellCount() || !p.info(b.cell()).breakable() || !ShadowWorld.runs(p)) {
            MpServer.useDone(guest, b.nonce(), true);
            return;
        }
        ShadowWorld.destroy(p, b.cell(), guest);
        MpServer.useDone(guest, b.nonce(), false);
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
            dev.moui.galaxycraft.voxel.Station station = null;
            if (StationSlots.isStation(index)) {
                String id = StationClient.idOfSlot(index);
                if (id == null || !StationClient.placed(id)) return false;
                station = StationClient.stationOfSlot(index);
                if (station != null) have = station.planet;
            } else {
                PlanetSession live = liveOf(index);
                if (live != null) have = live.planet();
                else if (PlanetClient.stream() != null) have = PlanetClient.stream().recentPlanet(index);
            }
            if (have == null && residents() >= MAX_RESIDENTS) return false;
            e = new Entry(index);
            e.station = station;
            shared.put(index, e);
            if (have != null) watch(e, have);
        }
        e.orphaned = 0;
        e.holders.add(guest);
        if (e.planet != null && e.editedTick != 0 && liveOf(index) == null) save(e);
        return true;
    }

    private static int residents() {
        int n = 0;
        for (Entry e : shared.values()) {
            if (e.planet != null && liveOf(e.index) != null) continue;
            if (e.station == null && e.planet != null && PlanetClient.stream() != null && PlanetClient.stream().recentPlanet(e.index) == e.planet) continue;
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
        if (StationSlots.isStation(index)) {
            supplyStation(e, data, blocks);
            return;
        }
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

    /** A station held for guests while the host's game does not have it: made from its bytes. */
    private static void supplyStation(Entry e, byte[] data, McBlocks blocks) {
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return dev.moui.galaxycraft.voxel.StationStore.decode(data, blocks,
                        name -> PlanetClient.answer(Minecraft.getInstance().submit(() -> blocks.parse(name)), "block " + name));
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, PlanetClient.maker).whenComplete((st, ex) -> Minecraft.getInstance().execute(() -> {
            if (shared.get(e.index) != e || e.planet != null) return;
            if (ex != null) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] station {} could not be held for guests: {}", e.index, ex.toString());
                for (UUID g : new ArrayList<>(e.holders)) release(g, e.index);
                return;
            }
            dev.moui.galaxycraft.voxel.Station live = StationClient.stationOfSlot(e.index); // came into the game meanwhile: its own object
            e.station = live != null ? live : st;
            watch(e, e.station.planet);
        }));
    }

    /** The station held for guests under that slot (null: none, or still being made). */
    static dev.moui.galaxycraft.voxel.Station stationFor(int slot) {
        Entry e = shared.get(slot);
        return e == null || e.planet == null ? null : e.station;
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
        dropEntry(e);
    }

    private static void dropEntry(Entry e) {
        int index = e.index;
        shared.remove(index);
        if (e.planet != null) {
            e.planet.removeObserver(e.observer);
            if (e.editedTick != 0 && liveOf(index) == null) save(e);
        }
    }

    private static void save(Entry e) {
        e.editedTick = 0;
        if (e.station != null) {
            StationClient.writeResident(e.station);
            return;
        }
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
        presenceTick();
        for (Entry e : new ArrayList<>(shared.values()))
            if (e.orphaned != 0 && e.holders.isEmpty() && now - e.orphaned > 200) dropEntry(e); // its guests did not ask again
        for (Entry e : shared.values()) { // what runs on each planet goes to the guests standing on it
            if (e.planet == null) continue;
            List<UUID> there = guestsOn(e.planet);
            if (there.isEmpty()) {
                runDrops.remove(e.planet);
                continue;
            }
            MpMobs.hostTick(now, e.planet, e.index, there);
            MpLive.hostTick(now, e.planet, e.index, there);
            if (now % 4 != 0) continue;
            if (e.planet == focusPlanet()) PlanetClient.drops().hostSend(e.index, there);
            else sendRunDrops(e, there);
        }
        for (var d : runDrops.entrySet()) d.getValue().tick(d.getKey());
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
            Entry on = shared.get(k.planet());
            int gen = on == null || on.station == null ? 0 : on.station.gridKey(); // the grid these cell numbers go by
            MpServer.publish(new EditCodec.Msg(k.planet(), seq, k.cseq(), defs, java.util.Arrays.copyOf(cells, kept), java.util.Arrays.copyOf(ids, kept), gen), k.author());
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
        if (e.station != null && StationSlots.stale(m.planet(), m.gen(), e.station.gridKey())) {
            // Made for a grid the station no longer has: the cell numbers mean other places. Dropped; the guest fetches the station again.
            GalaxyCraft.LOG.warn("[GalaxyCraft mp] an edit of station {} for an old grid: dropped, the guest is resynced", e.station.id);
            MpServer.resync(guest, m.planet());
            release(guest, m.planet());
            return;
        }
        String[] names = guestNames.computeIfAbsent(guest, g -> new String[EditCodec.MAX_WIRE_IDS]);
        for (EditCodec.Def d : m.defs()) names[d.wireId()] = d.name();
        VoxelPlanet p = e.planet;
        dev.moui.galaxycraft.voxel.Station st = e.station;
        List<Integer> applied = new ArrayList<>();
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
                if (st != null && (id != Blocks.AIR && !st.allowedCell(cell) || st.isCore(cell) && id != p.get(cell))) {
                    bad++; // past the station's limits, or its core
                    record(e.index, cell, p.get(cell));
                    continue;
                }
                if (live != null) live.applyShared(cell, id);
                else {
                    p.set(cell, id);
                    e.editedTick = now;
                }
                if (st != null && id != Blocks.AIR) applied.add(cell);
                record(e.index, cell, p.get(cell)); // told even if nothing changed: it settles the author's pending cell
            }
        } finally {
            author = null;
            authorCseq = 0;
        }
        if (bad > 0) GalaxyCraft.LOG.warn("[GalaxyCraft mp] {} cells of a guest's edit to planet {} were refused", bad, e.index);
        if (st != null && !applied.isEmpty() && st.settle(applied.stream().mapToInt(Integer::intValue).toArray())) regrown(e.index, p);
    }

    /**
     * A station got a new grid (blocks near its side, from a guest or the host): everything that held its old planet
     * object moves to the new one, and the guests are cut off to fetch it (their cell numbers are the old grid's).
     */
    static void regrown(int slot, VoxelPlanet old) {
        Entry e = shared.get(slot);
        if (e == null || e.station == null || e.planet != old || e.station.planet == old) return;
        VoxelPlanet now = e.station.planet;
        old.removeObserver(e.observer);
        e.planet = now;
        now.addObserver(e.observer);
        PlanetSession live = liveOf(slot);
        if (live != null && live.planet() == old) {
            live.swap(now);
            StationClient.regrew();
        }
        for (Presence pr : presence.values()) if (pr.on == old) pr.on = now;
        VoxelPlanet.Listener feed = shadowFeeds.remove(old);
        if (feed != null) {
            old.removeObserver(feed);
            VoxelPlanet.Listener again = (cell, id) -> ShadowWorld.setQuiet(now, cell, id);
            shadowFeeds.put(now, again);
            now.addObserver(again);
        }
        var drops = runDrops.remove(old);
        if (drops != null) runDrops.put(now, drops);
        ShadowWorld.rekey(old, now, "station-" + e.station.id);
        synchronized (LOCK) {
            batches.keySet().removeIf(k -> k.planet() == slot); // cell numbers of the old grid, not yet told: the new bytes have them
        }
        for (UUID g : new ArrayList<>(e.holders)) MpServer.resync(g, slot);
        e.holders.clear();
        e.orphaned = now();
        GalaxyCraft.LOG.info("[GalaxyCraft mp] station {} has a new grid: guests resynced", e.station.id);
    }

    private static int now() {
        return Math.max(1, now);
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
