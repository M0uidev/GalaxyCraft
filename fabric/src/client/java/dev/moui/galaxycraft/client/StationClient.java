package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityBody;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
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

    record Active(PlanetSession session, Station station) {}

    private static final List<Active> active = new ArrayList<>();
    private static StationStore store;
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
        rescan = true;
    }

    /** The stage changes (or the world is left): every station is saved, and the scene drops them. */
    static void unloadAll() {
        saveAll();
        for (Active a : active) a.session().unload();
        active.clear();
        rescan = true;
    }

    /** Each client tick, after the planets': stations near Mario in this stage come and go, edits are saved. */
    static void tick(String stage, Vector3d marioUniverse) {
        if (store == null || stage == null) return;
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            saveAll();
        }
        if (++sinceScan < SCAN_TICKS && !rescan || marioUniverse == null) return;
        sinceScan = 0;
        if (rescan) headers = store.list();
        rescan = false;
        for (Active a : new ArrayList<>(active))
            if (!stage.equals(a.station().stage) || blocks(a.station().center.distance(marioUniverse)) > INACTIVE) deactivate(a);
        for (StationStore.Header h : headers) {
            if (!stage.equals(h.stage()) || active.size() >= Station.MAX_ACTIVE || isActive(h.id())) continue;
            if (blocks(h.center().distance(marioUniverse)) > ACTIVE) continue;
            try {
                Station s = store.read(h.id(), blocks());
                activate(s);
                GalaxyCraft.LOG.info("Station {} ({}) in", s.id, s.name);
            } catch (IOException e) {
                GalaxyCraft.LOG.warn("Could not load station {}: {}", h.id(), e.toString());
            }
        }
    }

    private static boolean isActive(String id) {
        for (Active a : active) if (a.station().id.equals(id)) return true;
        return false;
    }

    private static double blocks(double units) {
        return units * GravityFrame.SCALE;
    }

    private static PlanetSession activate(Station s) {
        PlanetSession session = new PlanetSession(1 / GravityFrame.SCALE);
        session.setBlocks(blocks());
        session.spawnStation(s);
        session.clean(); // as it is on disk
        active.add(new Active(session, s));
        return session;
    }

    private static void deactivate(Active a) {
        save(a);
        active.remove(a);
        PlanetClient.retire(a.session());
        GalaxyCraft.LOG.info("Station {} out", a.station().id);
    }

    static void saveAll() {
        for (Active a : active) if (a.session().unsaved()) save(a);
    }

    private static void save(Active a) {
        a.session().clean();
        write(a.station());
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
        rescan = true;
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
        return Station.refusal(new Vector3d(centerUnits).mul(GravityFrame.SCALE), bodies, active.size(), Station.MAX_ACTIVE);
    }

    static boolean placeCore(Player player, InteractionHand hand) {
        Spot at = spot(player);
        return at != null && place(player, hand, at) != null;
    }

    /** A station up at that spot, if it may go there: its session (null: refused, said on the action bar). */
    public static PlanetSession place(Player player, InteractionHand hand, Spot at) {
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
        Station s = found.get().station();
        s.stage = null;
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
     * A block was just placed at cell of a station: past its limits it is taken back (false: the
     * item is not used up); otherwise the station grows, and regrows its grid near the side.
     */
    public static boolean afterPlace(PlanetSession session, int cell) {
        Optional<Station> found = of(session);
        if (found.isEmpty() || cell < 0) return true;
        Station s = found.get();
        FlatGrid g = s.grid();
        if (!s.allowed(g.stationX(cell), g.stationY(cell), g.stationZ(cell))) {
            s.planet.set(cell, Blocks.AIR);
            Player player = Minecraft.getInstance().player;
            if (player != null) tell(player, "message.galaxycraft.station.too_big");
            return false;
        }
        if (s.changed(cell)) {
            s.regrow();
            session.swap(s.planet);
            regrows++;
            GalaxyCraft.LOG.info("Station {} regrown: {} × {} × {} grid", s.id, s.grid().n, s.grid().layers, s.grid().n);
        }
        return true;
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
    private static void useUp(Player player, InteractionHand hand, Item item) {
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
