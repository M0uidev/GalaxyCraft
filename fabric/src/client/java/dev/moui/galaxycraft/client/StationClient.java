package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityBody;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.net.StationList;
import dev.moui.galaxycraft.net.StationSlots;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.station.PackedStationItem;
import dev.moui.galaxycraft.station.StationBlocks;
import dev.moui.galaxycraft.station.StationHooks;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.FlatGrid;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.Station;
import dev.moui.galaxycraft.voxel.StationStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The world's stations on the client, next to the planets: a Station Core used in open space
 * makes one; blocks placed on one grow it (and regrow its grid now and then); its core's menu
 * packs it into an item, used in open space again to unfold it. A station placed in this stage
 * near Mario is active (a PlanetSession sent to the game); farther off it rests in its file.
 */
public final class StationClient {
    /** Active within this many blocks of Mario, inactive past the second. */
    public static final double ACTIVE = 2000, INACTIVE = 2500;
    /** A new station's core: this far ahead of the player and below its feet, blocks. */
    static final double AHEAD = 6, BELOW = 2;
    private static final int SCAN_TICKS = 20, SAVE_TICKS = 200;

    /** slot: 0 for a station of this game's own world; a wire slot (see StationSlots) for one the host sent a guest. */
    record Active(PlanetSession session, Station station, int slot) {}

    private static final List<Active> active = new ArrayList<>();
    private static StationStore store;
    /** A guest's copies of the host's stations (never the guest's own stations/ folder). */
    private static StationStore cache;
    /** Station ids and their wire slots: handed out by the host, learned by a guest. */
    private static final StationSlots slots = new StationSlots();
    /** Guest: the host's placed stations, by slot, as it last told. */
    private static java.util.Map<Integer, StationList.Head> remote = new java.util.TreeMap<>();
    /** Guest: stations being fetched (to be activated, or, if already active, to swap to a new grid). */
    private static final java.util.Map<Integer, java.util.concurrent.CompletableFuture<Station>> making = new java.util.HashMap<>();
    /** Host: the stations as last told to the guests. */
    private static java.util.Map<Integer, StationList.Head> told = new java.util.TreeMap<>();
    private static List<StationStore.Header> headers = List.of();
    private static boolean rescan = true;
    private static int sinceScan, sinceSave;
    private static int regrows;
    private static final java.util.Random random = new java.util.Random();

    public static final StationHooks HOOKS = new StationHooks() {
        @Override public boolean placeCore(Player player, InteractionHand hand) {
            return StationClient.placeCore(player, hand);
        }

        @Override public boolean unfold(Player player, InteractionHand hand, String id) {
            return StationClient.unfold(player, hand, id);
        }
    };

    private StationClient() {}

    private static String testStage;
    private static Blocks testBlocks;

    /** Game tests without the game (no link, so no stage or blocks of PlanetClient's): theirs. */
    public static void testSetup(Path planetsDir, String stage, Blocks blocks) {
        testStage = stage;
        testBlocks = blocks;
        enterWorld(planetsDir);
    }

    private static String stage() {
        return testStage != null ? testStage : PlanetClient.stage();
    }

    private static Blocks blocks() {
        return testBlocks != null ? testBlocks : PlanetClient.blocks();
    }

    /** The active stations' sessions. */
    public static List<PlanetSession> sessions() {
        List<PlanetSession> out = new ArrayList<>(active.size());
        for (Active a : active) out.add(a.session());
        return out;
    }

    /** Grids made anew so far (tests). */
    public static int regrows() {
        return regrows;
    }

    /** The station a session streams, if it is one of ours. */
    public static Optional<Station> of(PlanetSession s) {
        for (Active a : active) if (a.session() == s) return Optional.of(a.station());
        return Optional.empty();
    }

    /** A world's planets folder: its stations live in its stations/ (null: no world). */
    public static void enterWorld(Path planetsDir) {
        unloadAll();
        store = planetsDir == null ? null : new StationStore(planetsDir.resolve("stations"));
        cache = planetsDir == null ? null : new StationStore(planetsDir.resolve("mp-cache").resolve("stations"));
        slots.clear();
        remote = new java.util.TreeMap<>();
        told = new java.util.TreeMap<>();
        making.clear();
        rescan = true;
    }

    /** The stage changes (or the world is left): every station is saved, and the scene drops them. */
    static void unloadAll() {
        saveAll();
        making.clear();
        for (Active a : active) a.session().unload();
        active.clear();
    }

    /** Each client tick, after the planets': stations near Mario in this stage come and go, edits are saved. */
    static void tick(String stage, Vector3d marioUniverse) {
        if (MpUse.guest()) { // a guest has the host's stations, not its own
            remoteTick(stage, marioUniverse);
            return;
        }
        if (store == null || stage == null) return;
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            saveAll();
        }
        if (++sinceScan < SCAN_TICKS && !rescan || marioUniverse == null && wanted == null) return;
        sinceScan = 0;
        if (rescan) headers = store.list();
        rescan = false;
        tellGuests();
        for (Active a : new ArrayList<>(active))
            if (!stage.equals(a.station().stage) || !a.station().id.equals(wanted) && marioUniverse != null
                    && blocks(a.station().center.distance(marioUniverse)) > INACTIVE) deactivate(a);
        for (StationStore.Header h : headers) {
            if (!stage.equals(h.stage()) || active.size() >= Station.MAX_ACTIVE || isActive(h.id())) continue;
            if (!h.id().equals(wanted) && (marioUniverse == null || blocks(h.center().distance(marioUniverse)) > ACTIVE)) continue;
            int slot = slots.slotOf(h.id());
            if (slot != 0 && HostPlanets.loading(slot)) continue; // a guest's copy is being made here: not twice
            try {
                Station held = slot == 0 ? null : HostPlanets.stationFor(slot); // held for guests: the same object, so their edits stay in it
                Station s = held != null ? held : store.read(h.id(), blocks());
                PlanetSession session = activate(s);
                if (held != null && HostPlanets.takeEdited(slot)) session.markEdited(); // guests built on it while it was not here
                GalaxyCraft.LOG.info("Station {} ({}) in", s.id, s.name);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not load station {}: {}", h.id(), e.toString());
            }
        }
    }

    /** The station kept in the scene for the player to land on (not dropped for being far off), or null. */
    private static String wanted;

    /**
     * The player is about to land on this station (a saved spot): it comes in at the next scan
     * wherever it is, and stays until {@link #landed()}. False: no such station placed in this stage.
     */
    static boolean want(String id) {
        if (store == null || id == null) return false;
        if (header(id).isEmpty()) return false;
        wanted = id;
        rescan = true;
        return true;
    }

    /** The player landed (or gave up): the station is dropped like any far one. */
    static void landed() {
        wanted = null;
    }

    /** Where a placed station of the stage is, universe units (null: none such). */
    static Vector3d centerOf(String id) {
        return header(id).map(h -> new Vector3d(h.center())).orElse(null);
    }

    private static Optional<StationStore.Header> header(String id) {
        String stage = stage();
        if (store == null || stage == null) return Optional.empty();
        for (StationStore.Header h : store.list())
            if (h.id().equals(id) && stage.equals(h.stage())) return Optional.of(h);
        return Optional.empty();
    }

    /** The active station's session by id, or null. */
    static PlanetSession sessionOf(String id) {
        for (Active a : active) if (a.station().id.equals(id)) return a.session();
        return null;
    }

    /** The active station whose gravity box has this galaxy point (units) inside, or null. */
    static Station standingOn(Vector3d galPoint) {
        Vector3d blocks = new Vector3d(galPoint).mul(GravityFrame.SCALE);
        for (Active a : active)
            if (a.session().body(GravityFrame.SCALE).outside(blocks) <= 0) return a.station();
        return null;
    }

    private static boolean isActive(String id) {
        for (Active a : active) if (a.station().id.equals(id)) return true;
        return false;
    }

    private static double blocks(double units) {
        return units * GravityFrame.SCALE;
    }

    private static PlanetSession activate(Station s) {
        return activate(s, 0);
    }

    private static PlanetSession activate(Station s, int slot) {
        PlanetSession session = new PlanetSession(1 / GravityFrame.SCALE);
        session.setBlocks(blocks());
        session.spawnStation(s);
        session.clean(); // as it is on disk
        active.add(new Active(session, s, slot));
        return session;
    }

    private static void deactivate(Active a) {
        save(a);
        active.remove(a);
        PlanetClient.retire(a.session());
        GalaxyCraft.LOG.info("Station {} out", a.station().id);
    }

    static void saveAll() {
        for (Active a : active) if (a.slot() == 0 && a.session().unsaved()) save(a);
    }

    private static void save(Active a) {
        if (a.slot() != 0) return; // the host's, not ours to keep
        a.session().clean();
        write(a.station());
    }

    /**
     * The headers the scan goes by, as the file is about to be: a station packed a moment ago must
     * not come back from its old header (or from a list() racing the write), and saving an edit
     * reads no files.
     */
    private static void remember(Station s) {
        List<StationStore.Header> next = new ArrayList<>(headers);
        next.removeIf(h -> h.id().equals(s.id));
        next.add(new StationStore.Header(s.id, s.name, s.stage, s.center, s.rotation, 0, s.bounds.spanX(), s.bounds.spanY(),
                s.bounds.spanZ()));
        headers = next;
    }

    /** Written on the planets' saver thread, from a copy of its cells taken here. */
    private static void write(Station s) {
        StationStore store = StationClient.store;
        if (store == null) return;
        Station copy = new Station(s.id, s.name, s.rotation, s.planet, s.bounds);
        copy.stage = s.stage;
        copy.center = new Vector3d(s.center);
        char[] cells = s.planet.cells().clone();
        Blocks blocks = blocks();
        remember(copy);
        PlanetClient.save(() -> {
            try {
                store.write(copy, cells, blocks);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not save station {}: {}", s.id, e.toString());
            }
        });
    }

    /** Where a station goes up in front of the player, and how it is turned; null if the player is nowhere. */
    public record Spot(Vector3d center, Quaterniond rotation) {}

    public static Spot spot(Player player) {
        GravityFrame frame = GalaxyCraftClient.frame();
        Optional<Vector3d> feet = GalaxyCraftClient.galaxyPos();
        if (frame == null || feet.isEmpty()) return null;
        Vector3d up = frame.upGal().normalize();
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        Vector3d forward = new Vector3d(look).fma(-look.dot(up), up);
        if (forward.lengthSquared() < 1e-6) forward = new Vector3d(up).orthogonalize(new Vector3d(up.y, up.z, up.x));
        forward.normalize();
        Vector3d right = new Vector3d(up).cross(forward);
        Quaterniond rotation = new Quaterniond().setFromNormalized(new Matrix3d(right, up, forward));
        Vector3d center = new Vector3d(feet.get()).fma(AHEAD / GravityFrame.SCALE, forward).fma(-BELOW / GravityFrame.SCALE, up);
        return new Spot(center, rotation);
    }

    /** Why no station may go up at a spot (a translation key), or null. */
    static String refusal(Vector3d centerUnits) {
        String stage = stage();
        if (stage == null || !Layout.SPACE_STAGE.equals(stage) && !Boolean.getBoolean("galaxycraft.stationsAnywhere")) return "open_space";
        List<GravityBody> bodies = new ArrayList<>();
        for (PlanetSession s : PlanetClient.bodies()) if (s.active()) bodies.add(s.body(GravityFrame.SCALE));
        bodies.addAll(PlanetClient.farBodies()); // and the galaxy's planets not streamed in yet
        return Station.refusal(new Vector3d(centerUnits).mul(GravityFrame.SCALE), bodies, active.size(), Station.MAX_ACTIVE);
    }

    static boolean placeCore(Player player, InteractionHand hand) {
        Spot at = spot(player);
        return at != null && place(player, hand, at) != null;
    }

    /** A station up at that spot, if it may go there: its session (null: refused, said on the action bar). */
    public static PlanetSession place(Player player, InteractionHand hand, Spot at) {
        if (MpUse.guest()) { // phase 2: a guest asks the host to place it
            tell(player, "message.galaxycraft.station.host_only");
            return null;
        }
        if (store == null || blocks() == null) return null;
        String why = refusal(at.center());
        if (why != null) {
            GalaxyCraft.LOG.info("No station at {}: {}", at.center(), why);
            tell(player, "message.galaxycraft.station." + why);
            return null;
        }
        Blocks b = blocks();
        Station s = Station.create(Station.newId(random), "Station", at.rotation(), b, (char) b.parse("minecraft:smooth_stone"),
                (char) b.parse(StationBlocks.CORE_NAME));
        s.stage = stage();
        s.center = at.center();
        PlanetSession session = activate(s);
        write(s);
        ToadClient.putTechnician(session, s.id, s.center, s.rotation);
        useUp(player, hand, StationBlocks.CORE_ITEM);
        GalaxyCraft.LOG.info("Station {} up at {}", s.id, s.center);
        return session;
    }

    static boolean unfold(Player player, InteractionHand hand, String id) {
        Spot at = spot(player);
        return at != null && unfold(player, hand, id, at) != null;
    }

    /** A packed station unfolded at that spot: its session (null: refused). */
    public static PlanetSession unfold(Player player, InteractionHand hand, String id, Spot at) {
        if (MpUse.guest()) {
            tell(player, "message.galaxycraft.station.host_only");
            return null;
        }
        if (store == null || blocks() == null || isActive(id)) return null;
        String why = refusal(at.center());
        if (why != null) {
            tell(player, "message.galaxycraft.station." + why);
            return null;
        }
        Station s;
        try {
            s = store.read(id, blocks());
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("Could not unfold station {}: {}", id, e.toString());
            tell(player, "message.galaxycraft.station.lost");
            return null;
        }
        if (s.stage != null) return null; // placed already: this item is a stale copy
        Station turned = turned(s, at.rotation());
        turned.stage = stage();
        turned.center = at.center();
        PlanetSession session = activate(turned);
        write(turned);
        ToadClient.putTechnician(session, turned.id, turned.center, turned.rotation);
        useUp(player, hand, StationBlocks.PACKED);
        GalaxyCraft.LOG.info("Station {} unfolded at {}", s.id, turned.center);
        return session;
    }

    /** The same station turned another way: its cells on a grid with that rotation. */
    private static Station turned(Station s, Quaterniond rotation) {
        FlatGrid g = s.grid();
        FlatGrid to = new FlatGrid(g.n, g.layers, g.ox, g.oy, g.oz, rotation);
        Station out = new Station(s.id, s.name, rotation, dev.moui.galaxycraft.voxel.VoxelPlanet.flat(to, s.planet.cells(), s.planet.blocks), s.bounds);
        out.stage = s.stage;
        out.center = s.center;
        return out;
    }

    /** Packs a station into its item: out of space, its file kept, the item in the player's inventory. */
    public static void pack(PlanetSession session) {
        Optional<Active> found = active.stream().filter(a -> a.session() == session).findFirst();
        if (found.isEmpty()) return;
        if (found.get().slot() != 0) { // phase 2: a guest asks the host to pack it
            Player who = Minecraft.getInstance().player;
            if (who != null) tell(who, "message.galaxycraft.station.host_only");
            return;
        }
        Station s = found.get().station();
        s.stage = null;
        ToadClient.removeTechnician(s.id);
        found.get().session().clean();
        write(s);
        active.remove(found.get());
        PlanetClient.retire(session);
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        var item = PackedStationItem.stack(s.id, s.name, s.bounds.spanX(), s.bounds.spanY(), s.bounds.spanZ(), s.blockCount());
        ShadowWorld.give(player.getUUID(), item, left -> GalaxyCraft.LOG.warn("Inventory full: station {} stays packed in its file "
                + "(/galaxycraft station restore {})", s.id, s.id));
        player.sendOverlayMessage(Component.translatable("message.galaxycraft.station.packed", s.name));
        GalaxyCraft.LOG.info("Station {} packed", s.id);
    }

    static void rename(PlanetSession session, String name) {
        if (MpUse.guest()) return; // phase 2
        of(session).ifPresent(s -> {
            s.name = name.isBlank() ? "Station" : name.strip();
            session.markEdited();
        });
    }

    /** Whether cell is a station's core. */
    public static boolean isCore(PlanetSession session, int cell) {
        Optional<Station> s = of(session);
        if (s.isEmpty() || cell < 0) return false;
        FlatGrid g = s.get().grid();
        return g.stationX(cell) == 0 && g.stationY(cell) == 0 && g.stationZ(cell) == 0;
    }

    /**
     * Blocks were just placed at cells of a station (a door's halves): past its limits they are all
     * taken back (false: the item is not used up); otherwise the station grows, and regrows its
     * grid near the side.
     */
    public static boolean afterPlace(PlanetSession session, int... cells) {
        Optional<Active> act = active.stream().filter(a -> a.session() == session).findFirst();
        if (act.isEmpty()) return true;
        Station s = act.get().station();
        boolean guest = act.get().slot() != 0; // the host regrows the station and sends the new grid (Resync)
        FlatGrid g = s.grid();
        for (int cell : cells) {
            if (cell < 0 || s.allowed(g.stationX(cell), g.stationY(cell), g.stationZ(cell))) continue;
            for (int c : cells) if (c >= 0) s.planet.set(c, Blocks.AIR);
            Player player = Minecraft.getInstance().player;
            if (player != null) tell(player, "message.galaxycraft.station.too_big");
            return false;
        }
        boolean regrow = false;
        for (int cell : cells) if (cell >= 0) regrow |= s.changed(cell);
        if (regrow && !guest) {
            dev.moui.galaxycraft.voxel.VoxelPlanet old = s.planet;
            s.regrow();
            session.swap(s.planet);
            HostPlanets.regrown(slots.slotOf(s.id), old);
            regrows++;
            GalaxyCraft.LOG.info("Station {} regrown: {} × {} × {} grid", s.id, s.grid().n, s.grid().layers, s.grid().n);
        }
        return true;
    }

    // ---- multiplayer: the host's stations for the guests

    /** The wire slot of a station: the host hands them out as it speaks of the stations; a guest knows the ones it was told. 0: none. */
    public static int slotOf(String id) {
        return MpUse.guest() ? slots.slotOf(id) : slots.assign(id);
    }

    static String idOfSlot(int slot) {
        return slots.idOf(slot);
    }

    static Station stationOfSlot(int slot) {
        String id = slots.idOf(slot);
        if (id == null) return null;
        for (Active a : active) if (a.station().id.equals(id)) return a.station();
        return null;
    }

    static PlanetSession sessionOfSlot(int slot) {
        String id = slots.idOf(slot);
        if (id == null) return null;
        for (Active a : active) if (a.station().id.equals(id)) return a.session();
        return null;
    }

    /** Host: whether the world has this station placed (in any stage). */
    static boolean placed(String id) {
        for (StationStore.Header h : headers) if (h.id().equals(id) && h.stage() != null) return true;
        return stationOfSlot(slots.slotOf(id)) != null;
    }

    static void regrew() {
        regrows++;
    }

    private static Station copyOf(Station s) {
        Station copy = new Station(s.id, s.name, s.rotation, s.planet, s.bounds);
        copy.stage = s.stage;
        copy.center = new Vector3d(s.center);
        return copy;
    }

    /** Host: a station's file bytes for a guest, as of now (a station in the game or held for guests is taken as it is; else its file). Null: no such station. */
    static java.util.concurrent.CompletableFuture<byte[]> serve(int slot) {
        String id = slots.idOf(slot);
        Blocks b = blocks();
        StationStore st = store;
        if (id == null || b == null || st == null) return null;
        Station now = stationOfSlot(slot);
        if (now == null) now = HostPlanets.stationFor(slot);
        if (now != null) {
            Station copy = copyOf(now);
            char[] cells = now.planet.cells().clone();
            return PlanetClient.onSaver(() -> {
                try {
                    return StationStore.encode(copy, cells, b);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        if (!placed(id)) return null;
        return PlanetClient.onSaver(() -> {
            try {
                return java.nio.file.Files.readAllBytes(st.file(id));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** Host: a station's file bytes, kept for the resident copy (a guest edited it while the host was away). */
    static void writeResident(Station s) {
        write(s);
    }

    private static String clip(String text) {
        String t = text;
        while (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static StationList.Head head(int slot, String id, String name, String stage, Vector3d c, org.joml.Quaterniond r, int sx, int sy, int sz) {
        return new StationList.Head(slot, id, clip(name), stage, c.x, c.y, c.z, r.x, r.y, r.z, r.w, sx, sy, sz);
    }

    /** Host: the placed stations of the world by slot (those in the game as they are now, the others as their files say). */
    private static java.util.Map<Integer, StationList.Head> hostList() {
        java.util.Map<Integer, StationList.Head> out = new java.util.TreeMap<>();
        for (StationStore.Header h : headers)
            if (h.stage() != null && out.size() < StationList.MAX_HEADS)
                out.put(slots.assign(h.id()), head(slots.assign(h.id()), h.id(), h.name(), h.stage(), h.center(), h.rotation(), h.spanX(), h.spanY(), h.spanZ()));
        for (Active a : active) {
            Station s = a.station();
            if (s.stage == null || out.size() >= StationList.MAX_HEADS && !out.containsKey(slots.assign(s.id))) continue;
            int slot = slots.assign(s.id);
            out.put(slot, head(slot, s.id, s.name, s.stage, s.center, s.rotation, s.bounds.spanX(), s.bounds.spanY(), s.bounds.spanZ()));
        }
        return out;
    }

    /** Host, at each scan: what changed in the stations goes to the guests. */
    private static void tellGuests() {
        java.util.Map<Integer, StationList.Head> now = hostList();
        List<StationList.Head> changed = StationList.diff(told, now);
        if (changed.isEmpty()) return;
        told = now;
        dev.moui.galaxycraft.net.MpServer.sendStations(null, StationList.encode(new StationList.Msg(false, changed)));
    }

    /** Host: a guest entered the galaxy; it is told every station. */
    static void tellJoined(java.util.UUID guest) {
        if (MpUse.guest() || store == null) return;
        headers = store.list(); // as the files are now (the scan may not have run yet)
        dev.moui.galaxycraft.net.MpServer.sendStations(guest,
                StationList.encode(new StationList.Msg(true, new ArrayList<>(hostList().values()))));
    }

    static void registerMp() {
        dev.moui.galaxycraft.net.MpServer.setJoinListener(id -> Minecraft.getInstance().execute(() -> tellJoined(id)));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                dev.moui.galaxycraft.net.MpPayloads.StationUpdate.TYPE, (u, ctx) -> ctx.client().execute(() -> onList(u.data())));
    }

    /** Guest: the host told its stations (all of them, or the ones that changed). */
    static void onList(byte[] data) {
        StationList.Msg m = StationList.decode(data);
        if (m == null) {
            GalaxyCraft.LOG.warn("[GalaxyCraft mp] a bad station list from the host");
            return;
        }
        List<StationList.Head> ok = new ArrayList<>();
        for (StationList.Head h : m.heads()) if (slots.learn(h.slot(), h.id())) ok.add(h);
        remote = StationList.merge(remote, new StationList.Msg(m.full(), ok));
        rescan = true;
    }

    private static Active activeBySlot(int slot) {
        for (Active a : active) if (a.slot() == slot) return a;
        return null;
    }

    private static double headDistance(StationList.Head h, Vector3d mario) {
        return blocks(new Vector3d(h.cx(), h.cy(), h.cz()).distance(mario));
    }

    /** Guest, each tick: the host's stations near Mario in this stage are fetched and come into the game; far ones go. */
    private static void remoteTick(String stage, Vector3d mario) {
        if (stage == null || cache == null) return;
        remotePoll(stage);
        if (++sinceScan < SCAN_TICKS && !rescan || mario == null) return;
        sinceScan = 0;
        rescan = false;
        for (Active a : new ArrayList<>(active)) {
            if (a.slot() == 0) continue;
            StationList.Head h = remote.get(a.slot());
            if (h == null || !stage.equals(h.stage()) || headDistance(h, mario) > INACTIVE) deactivateRemote(a);
        }
        for (StationList.Head h : remote.values()) {
            if (!stage.equals(h.stage()) || making.containsKey(h.slot()) || activeBySlot(h.slot()) != null) continue;
            if (active.size() + making.size() >= Station.MAX_ACTIVE || headDistance(h, mario) > ACTIVE) continue;
            fetch(h);
        }
    }

    private static void fetch(StationList.Head h) {
        Blocks b = blocks();
        if (b == null || cache == null || making.containsKey(h.slot())) return;
        StationStore from = cache;
        java.util.concurrent.CompletableFuture<Void> got = MpPlanets.fetch(h.slot(), from.file(h.id()));
        if (got == null) return; // too many asked of the host already: tried again at the next scan
        making.put(h.slot(), got.thenApplyAsync(v -> {
            try {
                return from.read(h.id(), b, name -> PlanetClient.answer(Minecraft.getInstance().submit(() -> b.parse(name)), "block " + name));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, PlanetClient.maker));
    }

    /** Guest: stations that arrived come into the game; one already in it takes its new grid (the host regrew it). */
    private static void remotePoll(String stage) {
        for (var it = making.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getValue().isDone()) continue;
            it.remove();
            int slot = e.getKey();
            Station s;
            try {
                s = e.getValue().join();
            } catch (RuntimeException ex) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] station {} from the host: {}", slot, ex.toString());
                continue;
            }
            StationList.Head h = remote.get(slot);
            if (h == null || !stage.equals(h.stage())) continue;
            s.name = h.name();
            s.stage = h.stage();
            s.center = new Vector3d(h.cx(), h.cy(), h.cz());
            Active had = activeBySlot(slot);
            if (had == null) {
                if (active.size() >= Station.MAX_ACTIVE) continue;
                MpEdits.attach(slot, activate(s, slot));
                GalaxyCraft.LOG.info("[GalaxyCraft mp] station {} ({}) from the host is in", s.id, s.name);
            } else {
                had.session().swap(s.planet);
                had.session().clean();
                active.set(active.indexOf(had), new Active(had.session(), s, slot));
                MpEdits.attach(slot, had.session());
                GalaxyCraft.LOG.info("[GalaxyCraft mp] station {} has a new grid from the host", s.id);
            }
        }
    }

    private static void deactivateRemote(Active a) {
        active.remove(a);
        PlanetClient.retire(a.session());
        MpEdits.drop(a.slot());
        GalaxyCraft.LOG.info("[GalaxyCraft mp] station {} out", a.station().id);
    }

    /** Guest: the host cut us off this station (it has a new grid, or we fell behind): its bytes are fetched again. */
    static void resyncRemote(int slot) {
        StationList.Head h = remote.get(slot);
        if (h != null && activeBySlot(slot) != null) fetch(h);
    }

    /** /galaxycraft station list: every station of the world. */
    static String list() {
        if (store == null) return "No world";
        List<StationStore.Header> all = store.list();
        if (all.isEmpty()) return "No stations yet";
        StringBuilder out = new StringBuilder();
        for (StationStore.Header h : all)
            out.append(out.isEmpty() ? "" : "\n").append(String.format("%s \"%s\": %s, %d blocks, %d × %d × %d", h.id(), h.name(),
                    h.stage() == null ? "packed" : "in " + h.stage(), h.blocks(), h.spanX(), h.spanZ(), h.spanY()));
        return out.toString();
    }

    /** /galaxycraft station restore <id>: a packed station's item again (one lost burns no station). */
    static String restore(String id) {
        if (store == null) return "No world";
        for (StationStore.Header h : store.list()) {
            if (!h.id().equals(id)) continue;
            if (h.stage() != null) return "Station " + id + " is placed in " + h.stage() + ": pack it up from its core";
            Player player = Minecraft.getInstance().player;
            if (player == null) return "No player";
            ShadowWorld.give(player.getUUID(), PackedStationItem.stack(h.id(), h.name(), h.spanX(), h.spanY(), h.spanZ(), h.blocks()), left -> {});
            return "Gave back station " + id;
        }
        return "No station " + id;
    }

    private static void tell(Player player, String key) {
        player.sendOverlayMessage(Component.translatable(key));
    }

    /** One fewer of the item in hand, outside creative (on the server, which syncs back). */
    static void useUp(Player player, InteractionHand hand, Item item) {
        if (player.getAbilities().instabuild) return;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        java.util.UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null && sp.getItemInHand(hand).is(item)) sp.getItemInHand(hand).shrink(1);
        });
    }
}
