package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.map.MapShips;
import dev.moui.galaxycraft.map.Trip;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.play.PlayConfig;
import dev.moui.galaxycraft.ship.CargoCodec;
import dev.moui.galaxycraft.ship.CargoLayout;
import dev.moui.galaxycraft.ship.SeatLayout;
import dev.moui.galaxycraft.ship.ShipCargo;
import dev.moui.galaxycraft.ship.ShipMenu;
import dev.moui.galaxycraft.ship.ShipHudModel;
import dev.moui.galaxycraft.ship.ShipBlocks;
import dev.moui.galaxycraft.ship.ShipBoarding;
import dev.moui.galaxycraft.ship.ShipHooks;
import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.ShipOps;
import dev.moui.galaxycraft.ship.ShipPilots;
import dev.moui.galaxycraft.ship.ShipRecord;
import dev.moui.galaxycraft.ship.ShipStats;
import dev.moui.galaxycraft.ship.ShipStore;
import dev.moui.galaxycraft.ship.ShipThrust;
import dev.moui.galaxycraft.ship.ToadShipItem;
import dev.moui.galaxycraft.ship.model.ShipModels;
import dev.moui.galaxycraft.view.EntityWire;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The world's ships on the client: put down from a Toad Ship item, boarded with a right click and
 * left with Shift. Aboard, the ship hovers where W A S D, Space and Ctrl take it, at the speed
 * its stats give (ShipThrust); the ship goes where the player is. A ship nobody is aboard is
 * solid. Drawn by EntityClient.
 */
public final class ShipClient {
    /** A ship is clicked within this many blocks of the eye, by a sphere of this radius. */
    static final double REACH = 8, RADIUS = 2.5;
    /** A ship is put down this far ahead of the player and this far below its feet, blocks. */
    private static final double AHEAD = 6, BELOW = 1;
    /** Where on the ship the aboard player's feet are, blocks above its middle (the cabin). */
    private static final double SEAT_HEIGHT = -0.8;
    private static final int SAVE_TICKS = 100;
    /** Where a player who leaves stands: this far out to the side and this far below the ship's middle, blocks. */
    private static final double EXIT_SIDE = 3.8, EXIT_BELOW = 1.6;
    /** A pad's half thickness and the ship's keel below its middle, blocks; a pad is found within this reach and above by this much. */
    private static final double PAD_HOVER = 7, PAD_HALF = ShipModels.PAD_THICK / 32, KEEL = 2.0, PAD_REACH = 90, PAD_ABOVE = 70, PAD_BELOW = 8;

    public static final ShipHooks HOOKS = new ShipHooks() {
        @Override
        public boolean place(Player player, InteractionHand hand, ShipModules modules) {
            return ShipClient.place(player, hand, modules);
        }

        @Override
        public boolean placePad(Player player, InteractionHand hand) {
            return ShipClient.placePad(player, hand);
        }
    };

    private static ShipStore store;
    private static Path file;
    private static boolean dirty;
    private static int sinceSave;
    private static String boarded;
    private static boolean sneakWas;
    /** This game is a guest: its ships are a mirror of the host's (never saved); a placing, boarding or leaving goes to the host. */
    private static boolean mirror;
    /** Who flies which ship (the host decides; a guest keeps what it was told). */
    private static final ShipPilots pilots = new ShipPilots();
    /** A guest asked to board this ship; the host's yes arrives as a ShipPilot naming us. */
    private static String awaitingBoard, pendingBoard;
    /** Where the pilots of the other ships say their ships are: the record glides to it between the reports. */
    private static final java.util.Map<String, double[][]> targets = new java.util.HashMap<>();
    private static int poseTick;

    private ShipClient() {}

    /** A world's planets folder: its ships live in its ships.json (null: no world). */
    public static void enterWorld(Path planetsDir) {
        saveNow();
        leaveNow(null);
        mirror = false;
        pilots.clear();
        targets.clear();
        awaitingBoard = pendingBoard = null;
        file = planetsDir == null ? null : planetsDir.resolve("ships.json");
        store = file == null ? null : ShipStore.load(file);
        dirty = false;
        MapShips.install(MAP_SHIPS);
        ShipModels.load(shipDir());
    }

    /** The launcher's <data>/ship, where the SMG2 models are; -Dgalaxycraft.shipDir points tests elsewhere. */
    static Path shipDir() {
        String own = System.getProperty("galaxycraft.shipDir");
        if (own != null) return Path.of(own);
        return PlayConfig.dataDir(System.getenv(), java.util.Map.of("user.home", System.getProperty("user.home", "")),
                System.getProperty("os.name", "").toLowerCase().contains("win")).resolve("ship");
    }

    /** The ships (tests). */
    public static List<ShipRecord> ships() {
        return store == null ? List.of() : store.all();
    }

    /** The ship the player is aboard, if any (tests). */
    public static Optional<ShipRecord> aboard() {
        if (store == null || boarded == null) return Optional.empty();
        return store.all().stream().filter(r -> r.id().equals(boarded)).findFirst();
    }

    // ---- placing ----

    /** Where a ship or pad would go down, how it is turned, and whether it may (problem: a translation key). */
    record Plan(Vector3d center, Quaterniond rot, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    /** How far ahead (blocks) the ground is looked for under a point, when the crosshair meets none. */
    private static final double DOWN_REACH = 14;

    /**
     * Where the item in hand would go: on the ground the crosshair meets (a planet's or a space
     * station's), else on the ground below a point ahead; a ship in open space floats ahead. A ship
     * rests on its keel, a pad lies flat; turned to the planet's up there and the way the player looks.
     */
    static Plan plan(Player player, boolean pad) {
        GravityFrame frame = GalaxyCraftClient.frame();
        if (frame == null || store == null) return null;
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        Vector3d[] hit = null;
        double best = Double.MAX_VALUE;
        for (PlanetSession s : PlanetClient.bodies()) {
            if (!s.active()) continue;
            Vector3d[] h = s.surfaceAt(eye, look);
            if (h != null && h[0].distance(eye) < best) {
                best = h[0].distance(eye);
                hit = h;
            }
        }
        Vector3d fup = frame.upGal().normalize();
        Vector3d flat = new Vector3d(look).fma(-look.dot(fup), fup);
        if (flat.lengthSquared() < 1e-6) flat = new Vector3d(fup).orthogonalize(new Vector3d(fup.y, fup.z, fup.x));
        flat.normalize();
        if (hit == null) { // not pointing at the ground: down from a point ahead, nearer if there is none
            for (double ahead : new double[] {AHEAD, 4, 2.5}) {
                Vector3d from = new Vector3d(eye).fma(ahead / GravityFrame.SCALE, flat);
                best = Double.MAX_VALUE;
                for (PlanetSession s : PlanetClient.bodies()) {
                    if (!s.active()) continue;
                    Vector3d[] h = s.surfaceAlong(from, new Vector3d(fup).negate(), DOWN_REACH);
                    if (h != null && h[0].distance(from) < best) {
                        best = h[0].distance(from);
                        hit = h;
                    }
                }
                if (hit != null) break;
            }
        }
        boolean hover = false;
        if (hit == null) { // the ground under the player but none ahead (an edge): level with it, out past the edge
            Vector3d[] under = null;
            best = Double.MAX_VALUE;
            for (PlanetSession s : PlanetClient.bodies()) {
                if (!s.active()) continue;
                Vector3d[] h = s.surfaceAlong(eye, new Vector3d(fup).negate(), DOWN_REACH);
                if (h != null && h[0].distance(eye) < best) {
                    best = h[0].distance(eye);
                    under = h;
                }
            }
            if (under != null && !pad) {
                hit = new Vector3d[] {new Vector3d(under[0]).fma(AHEAD / GravityFrame.SCALE, flat), under[1]};
                hover = true;
            }
        }
        if (hit == null) {
            if (pad) return new Plan(new Vector3d(), new Quaterniond(), "message.galaxycraft.pad.ground");
            StationClient.Spot at = StationClient.spot(player); // open space: floats ahead
            if (at == null) return null;
            Vector3d up = new Vector3d(0, 1, 0).rotate(at.rotation());
            Vector3d c = new Vector3d(at.center()).fma((2 - BELOW) / GravityFrame.SCALE, up);
            return new Plan(c, at.rotation(), crowded(c, 5.5) ? "message.galaxycraft.ship.crowded" : null);
        }
        Vector3d up = hit[1];
        Vector3d fwd = new Vector3d(look).fma(-look.dot(up), up);
        if (fwd.lengthSquared() < 1e-6) fwd = new Vector3d(up).orthogonalize(new Vector3d(up.y, up.z, up.x));
        fwd.normalize();
        Vector3d right = new Vector3d(up).cross(fwd);
        Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(right, up, fwd));
        double lift = pad ? PAD_HALF : KEEL + 0.05;
        Vector3d c = new Vector3d(hit[0]).fma(lift / GravityFrame.SCALE, up);
        return new Plan(c, q, crowded(c, pad ? 3.5 : 5.5) ? "message.galaxycraft.ship.crowded" : null);
    }

    /** A Toad Ship item used: it goes down on the ground ahead (or floats in space), true if it did. */
    static boolean place(Player player, InteractionHand hand, ShipModules modules) {
        Plan p = plan(player, false);
        if (p == null) return false;
        if (!p.ok()) {
            player.sendOverlayMessage(Component.translatable(p.problem()));
            return false;
        }
        Quaterniond q = p.rot();
        ShipRecord put = new ShipRecord(UUID.randomUUID().toString(), modules, new double[] {p.center().x, p.center().y, p.center().z},
                new double[] {q.x, q.y, q.z, q.w}, stageName());
        if (mirror) { // a guest: the host puts it down, checks and uses up the item, and answers with a ShipPut
            MpShips.send(ShipOps.PLACE_SHIP, hand == InteractionHand.MAIN_HAND ? 0 : 1, "", put);
            return true;
        }
        store.put(put);
        dirty = true;
        MpShips.published(put);
        StationClient.useUp(player, hand, ShipBlocks.TOAD_SHIP);
        GalaxyCraft.LOG.info("A ship {} is down at {}", modules, p.center());
        syncHulls();
        return true;
    }

    /** A Landing Pad item used: down flat on the ground, a planet's or a station's. */
    static boolean placePad(Player player, InteractionHand hand) {
        Plan p = plan(player, true);
        if (p == null) return false;
        if (!p.ok()) {
            player.sendOverlayMessage(Component.translatable(p.problem()));
            return false;
        }
        Quaterniond q = p.rot();
        ShipRecord put = new ShipRecord(ShipRecord.PAD_PREFIX + UUID.randomUUID(), new ShipModules(0, 1, 0, 1),
                new double[] {p.center().x, p.center().y, p.center().z}, new double[] {q.x, q.y, q.z, q.w}, stageName());
        if (mirror) {
            MpShips.send(ShipOps.PLACE_PAD, hand == InteractionHand.MAIN_HAND ? 0 : 1, "", put);
            return true;
        }
        store.put(put);
        dirty = true;
        MpShips.published(put);
        StationClient.useUp(player, hand, ShipBlocks.LANDING_PAD);
        syncHulls();
        player.sendOverlayMessage(Component.translatable("message.galaxycraft.pad.placed"));
        return true;
    }

    /** Another ship or pad of this stage has its middle within r blocks of a point. */
    private static boolean crowded(Vector3d gal, double r) {
        for (ShipRecord o : store.all()) {
            if (!o.stage().equals(stageName())) continue;
            if (new Vector3d(o.pos()[0], o.pos()[1], o.pos()[2]).distance(gal) * GravityFrame.SCALE < r) return true;
        }
        return false;
    }

    /** Takes every ship and pad out of the world (tests). */
    public static void clearAll() {
        if (store == null) return;
        for (ShipRecord r : store.all()) store.remove(r.id());
        dirty = true;
        syncHulls();
    }

    private static String stageName() {
        String s = PlanetClient.stage();
        return s == null ? "" : s;
    }

    // ---- each tick ----

    /** The ship's velocity while boarded, Minecraft space, blocks a tick. */
    private static Vector3d velocity = new Vector3d();
    /** Ships whose hull is in the collision field: id -> the record it was made from. */
    private static final java.util.Map<String, ShipRecord> solid = new java.util.HashMap<>();
    private static final int FIELD_ID = 0x5A000000;

    /**
     * Client tick: boarding and leaving, the aboard player's flight, and the hulls' collision.
     * rightClick: the button went down this tick; sneak: Shift held; c: the flight keys. Returns
     * true if the click was the ship's.
     */
    static boolean tick(LocalPlayer player, GravityFrame frame, boolean rightClick, boolean sneak, ShipThrust.Controls c) {
        boolean sneakEdge = sneak && !sneakWas;
        sneakWas = sneak;
        if (store == null || player == null || frame == null) return false;
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            if (dirty) saveNow();
        }
        if (!targets.isEmpty()) glide();
        if (pendingBoard != null) { // the host said yes to our boarding
            String id = pendingBoard;
            pendingBoard = awaitingBoard = null;
            ShipRecord want = store.all().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
            if (want != null && boarded == null) board(player, frame, want);
        }
        syncHulls();
        if (boarded != null) {
            Optional<ShipRecord> r = aboard();
            if (r.isEmpty() || sneakEdge) {
                leaveNow(player);
                return false;
            }
            if (MapClient.courses().get(r.get().id()).isPresent() && c.forward()) {
                MapClient.courses().remove(r.get().id()); // W takes the controls: the map's trip ends here
                MapClient.save();
            }
            if (MapClient.courses().get(r.get().id()).isEmpty()) fly(player, frame, r.get(), c);
            if (rightClick && docked == null) {
                cyclePad(player, frame);
                return true;
            }
            return false;
        }
        if (!rightClick) return false;
        ShipRecord target = aimedShip(player, frame);
        if (target == null) return false;
        if (sneak) openCargo(target);
        else if (mirror) {
            String other = pilots.pilotOf(target.id());
            if (other != null) player.sendOverlayMessage(Component.translatable("message.galaxycraft.ship.occupied"));
            else {
                awaitingBoard = target.id();
                MpShips.send(dev.moui.galaxycraft.net.MpPayloads.ShipDo.BOARD, 0, target.id(), null);
            }
        } else if (!pilots.board(target.id(), player.getUUID().toString())) {
            player.sendOverlayMessage(Component.translatable("message.galaxycraft.ship.occupied"));
        } else {
            MpShips.pilot(target.id(), player.getUUID().toString());
            board(player, frame, target);
        }
        return true;
    }

    /** The world's registries the cargo's items are saved with (kept: the last save can come after the server has stopped). */
    private static volatile net.minecraft.core.HolderLookup.Provider registries;

    private static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> itemOps() {
        if (registries == null) throw new IllegalStateException("no world's registries yet");
        return net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries);
    }

    static {
        CargoCodec.setItemCodec(new CargoCodec.ItemCodec() {
            @Override
            public com.google.gson.JsonElement encode(net.minecraft.world.item.ItemStack stack) {
                return net.minecraft.world.item.ItemStack.CODEC.encodeStart(itemOps(), stack).getOrThrow();
            }

            @Override
            public net.minecraft.world.item.ItemStack decode(com.google.gson.JsonElement json) {
                return net.minecraft.world.item.ItemStack.CODEC.parse(itemOps(), json).result()
                        .orElse(net.minecraft.world.item.ItemStack.EMPTY);
            }
        });
    }

    /**
     * Opens a ship's hold: a menu from the integrated server over the cargo the store keeps (single player: the
     * server and the store share this process; the edits are saved with the ships).
     */
    /** A ship's hold, for the game tests that fill it. */
    public static ShipCargo cargoForTest(ShipRecord r) {
        return store.cargo(r.id(), r.modules().cabin());
    }

    static void openCargo(ShipRecord r) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        if (me == null || store == null || r.isPad()) return;
        if (mirror) { // a guest: the host opens its hold's menu for us (the items stay with the host)
            MpShips.send(dev.moui.galaxycraft.net.MpPayloads.ShipDo.CARGO, 0, r.id(), null);
            return;
        }
        openCargoFor(me.getUUID(), r);
    }

    /** The host's game: the screen of a ship's hold for a player (the host itself, or a friend asking). */
    private static void openCargoFor(UUID who, ShipRecord r) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || store == null || mirror) return;
        registries = server.registryAccess();
        ShipCargo cargo = store.cargo(r.id(), r.modules().cabin());
        server.execute(() -> {
            net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(who);
            if (sp != null) ShipMenu.open(sp, r.id(), cargo, r.modules(), m -> mc.execute(() -> setModules(r.id(), m)),
                    () -> mc.execute(() -> {
                        dirty = true;
                        MpShips.bays(r.id(), cargo.layout());
                    }));
        });
    }

    /** The host's game: a friend wants a ship's hold open. */
    static void hostCargo(UUID guest, String id) {
        if (store == null || mirror) return;
        ShipRecord r = store.all().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (r == null || r.isPad()) return;
        openCargoFor(guest, r);
    }

    /** The host's hold changed (a guest): the bays of this ship, to draw its chests. */
    static void mirrorBays(String id, byte[] bays) {
        if (!mirror || store == null) return;
        ShipRecord r = store.all().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (r != null && !r.isPad()) store.cargo(id, r.modules().cabin()).applyLayout(bays);
    }

    /** A ship's parts changed in its screen: the same ship, new modules (it is drawn from them at once). */
    private static void setModules(String id, ShipModules m) {
        if (store == null) return;
        for (ShipRecord r : store.all())
            if (r.id().equals(id)) {
                ShipRecord now = new ShipRecord(id, m, r.pos(), r.rot(), r.stage());
                store.put(now);
                dirty = true;
                MpShips.published(now); // a friend's edit of the parts reaches everyone
                MpShips.bays(id, store.cargo(id, m.cabin()).layout());
                return;
            }
    }

    /** The parked ship of this stage the crosshair is on (within reach), or null. */
    private static ShipRecord aimedShip(Player player, GravityFrame frame) {
        if (store == null) return null;
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        List<ShipRecord> here = new ArrayList<>();
        List<double[]> centers = new ArrayList<>();
        for (ShipRecord raw : store.all()) {
            ShipRecord s = onTrip(raw);
            if (!s.isPad() && s.stage().equals(stageName())) {
                here.add(raw);
                centers.add(s.pos());
            }
        }
        int i = ShipBoarding.nearest(eye, look.normalize(), centers, REACH / GravityFrame.SCALE, RADIUS / GravityFrame.SCALE);
        return i < 0 ? null : here.get(i);
    }

    /**
     * Whether a right click is the ship's (boarding it, or piloting it) and so is not an item's:
     * a ship, a pad or a block in hand must not be put down by the click that boards.
     */
    public static boolean swallowsUse() {
        if (LaunchStarClient.swallowsUse()) return true;
        if (store == null) return false;
        if (boarded != null) return true;
        LocalPlayer me = Minecraft.getInstance().player;
        GravityFrame frame = GalaxyCraftClient.frame();
        return me != null && frame != null && Minecraft.getInstance().gui.screen() == null && aimedShip(me, frame) != null;
    }

    private static void board(LocalPlayer player, GravityFrame frame, ShipRecord r) {
        boarded = r.id();
        velocity = new Vector3d();
        docked = null;
        tiltRoll = tiltPitch = 0;
        // Into the seat: the player's feet where the ship puts them (the cabin's middle).
        Vector3d up = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]).transform(new Vector3d(0, 1, 0));
        Vector3d feet = new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]).fma(SEAT_HEIGHT / GravityFrame.SCALE, up);
        Vector3d mc = frame.toMc(feet);
        player.setPos(mc.x, mc.y, mc.z);
        player.setOldPosAndRot();
        player.setDeltaMovement(Vec3.ZERO);
        ShipStats stats = ShipStats.of(r.modules());
        GalaxyCraft.LOG.info("Aboard ship {} ({} seats, speed {})", r.id(), stats.seats(), stats.speed());
        syncHulls();
    }

    /**
     * Aboard: the ship hovers where the keys take it (ShipThrust), by Minecraft's own movement so the
     * ground and walls stop it; no gravity; the ship at the player's feet, turned its way.
     */
    private static void fly(LocalPlayer player, GravityFrame frame, ShipRecord r, ShipThrust.Controls c) {
        // (No flight abilities: setting them let a double tap of Space switch creative flight on in
        // the server, which saved it and closed the elytra for good in that world.)
        player.setNoGravity(true);
        player.resetFallDistance();
        if (player.isFallFlying()) player.stopFallFlying();
        ShipThrust.Controls keys = docked != null && !c.up() ? ShipThrust.Controls.NONE : c;
        if (docked != null && c.up()) docked = null; // Space lifts off
        velocity = ShipThrust.step(velocity, vec(player.getLookAngle()), keys, ShipStats.of(r.modules()));
        landingAssist(player, frame, c);
        ShipCargo hold = store.cargo(r.id(), r.modules().cabin());
        int chests = 0;
        for (int i = 0; i < ShipCargo.BAYS; i++) if (hold.bay(i) == ShipCargo.Bay.SMALL || hold.bay(i) == ShipCargo.Bay.LARGE_HEAD) chests++;
        ShipHud.show(ShipHudModel.of(new Vector3d(velocity), c.turbo(), r.modules(), chests, hold.capacity()),
                docked != null ? Component.translatable("hud.galaxycraft.ship.docked").getString() : null);
        // The move itself waits for the player's own tick (onPlayerTick), after Minecraft has
        // saved the old position: moved before that, the view would not be drawn between the two
        // and the flight would step at 20 Hz.
    }

    /** A landing pad put down at a place (tests). */
    public static String addPad(Vector3d center, Quaterniond q) {
        String id = ShipRecord.PAD_PREFIX + UUID.randomUUID();
        store.put(new ShipRecord(id, new ShipModules(0, 1, 0, 1), new double[] {center.x, center.y, center.z},
                new double[] {q.x, q.y, q.z, q.w}, stageName()));
        dirty = true;
        syncHulls();
        return id;
    }

    /** The pad id the ship is docked on, or null. */
    private static String docked;
    private static int padHint;
    /** Tilt of the ship from its acceleration, radians (drawn, not felt). */
    private static double tiltRoll, tiltPitch;
    /** For tests: the descend key held. */
    private static boolean forceDown;

    /** Tests hold the descend key with this (keys do not reach the mod through Dolphin's test control). */
    public static void forceDescend(boolean on) {
        forceDown = on;
    }

    /** The pad the ship is docked on, if any (tests). */
    public static String dockedPad() {
        return docked;
    }

    /** The pad the pilot chose (right click cycles), by id. */
    private static String padChoice;

    /** The landing pads in reach of a point, nearest first. */
    private static List<ShipRecord> padsInReach(Vector3d feetGal) {
        List<ShipRecord> out = new ArrayList<>();
        for (ShipRecord p : store.all()) {
            if (!p.isPad() || !p.stage().equals(stageName())) continue;
            Vector3d up = new Quaterniond(p.rot()[0], p.rot()[1], p.rot()[2], p.rot()[3]).transform(new Vector3d(0, 1, 0));
            Vector3d d = new Vector3d(feetGal).sub(p.pos()[0], p.pos()[1], p.pos()[2]);
            double high = d.dot(up) * GravityFrame.SCALE;
            double lateral = new Vector3d(d).fma(-d.dot(up), up).length() * GravityFrame.SCALE;
            if (high < -PAD_BELOW || high > PAD_ABOVE || lateral > PAD_REACH) continue;
            out.add(p);
        }
        out.sort(java.util.Comparator.comparingDouble(p -> new Vector3d(feetGal).distance(p.pos()[0], p.pos()[1], p.pos()[2])));
        return out;
    }

    /** Right click aboard: the next pad in reach becomes the chosen one. */
    private static void cyclePad(LocalPlayer player, GravityFrame frame) {
        List<ShipRecord> pads = padsInReach(frame.toGal(vec(player.position())));
        if (pads.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < pads.size(); i++) if (pads.get(i).id().equals(padChoice)) at = i;
        padChoice = pads.get((at + 1) % pads.size()).id();
        padHint = 0;
    }

    /**
     * Pads within a wide reach (above, below to the side, anywhere around) are offered; the nearest
     * is the choice until right click picks another. Holding the descend key flies the ship to the
     * chosen one by itself: over it first, then down, slowing as it nears, docked once it rests.
     */
    private static void landingAssist(LocalPlayer player, GravityFrame frame, ShipThrust.Controls c) {
        if (docked != null) {
            velocity.set(0, 0, 0);
            return;
        }
        Vector3d feet = frame.toGal(vec(player.position()));
        List<ShipRecord> pads = padsInReach(feet);
        if (pads.isEmpty()) {
            padChoice = null;
            return;
        }
        ShipRecord pad = pads.get(0);
        int number = 1;
        for (int i = 0; i < pads.size(); i++)
            if (pads.get(i).id().equals(padChoice)) {
                pad = pads.get(i);
                number = i + 1;
            }
        padChoice = pad.id();
        double away = new Vector3d(feet).distance(pad.pos()[0], pad.pos()[1], pad.pos()[2]) * GravityFrame.SCALE;
        if (!c.down() && !forceDown) {
            if (padHint++ % 30 == 0)
                Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("message.galaxycraft.pad.near",
                        number, pads.size(), Math.round(away)), false);
            return;
        }
        Vector3d up = new Quaterniond(pad.rot()[0], pad.rot()[1], pad.rot()[2], pad.rot()[3]).transform(new Vector3d(0, 1, 0));
        Vector3d rest = new Vector3d(pad.pos()[0], pad.pos()[1], pad.pos()[2])
                .fma((PAD_HALF + KEEL + SEAT_HEIGHT) / GravityFrame.SCALE, up);
        Vector3d toRest = new Vector3d(rest).sub(feet);
        double along = toRest.dot(up), lateral = new Vector3d(toRest).fma(-along, up).length() * GravityFrame.SCALE;
        // Far to the side: go to a point above the pad first (clear of whatever lies between), then down.
        Vector3d target = lateral > 3 ? new Vector3d(rest).fma(PAD_HOVER / GravityFrame.SCALE, up) : rest;
        Vector3d want = frame.toMc(target).sub(vec(player.position()));
        double dist = want.length();
        if (lateral <= 3 && dist < 0.12 && velocity.length() < 0.08) {
            docked = pad.id();
            velocity.set(0, 0, 0);
            Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("message.galaxycraft.pad.docked"), false);
            return;
        }
        double speed = Math.min(0.9, 0.04 + dist * 0.07);
        if (dist > 1e-6) want.mul(Math.min(speed, dist) / dist);
        velocity.lerp(want, 0.4);
    }

    /**
     * Start of the player's own tick (after its old position is saved): the aboard ship's move, by
     * Minecraft's movement so the ground and walls stop it, and the ship at the player's feet.
     */
    static void onPlayerTick(LocalPlayer player) {
        if (boarded == null || store == null) return;
        Optional<ShipRecord> r = aboard();
        GravityFrame frame = GalaxyCraftClient.frame();
        if (r.isEmpty() || frame == null) return;
        if (MapClient.courses().get(r.get().id()).isPresent()) {
            autopilot(player, frame, r.get());
            store.put(placed(r.get(), frame, player, 1f));
            dirty = true;
            return;
        }
        if (velocity.lengthSquared() > 1e-10)
            player.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(velocity.x, velocity.y, velocity.z));
        player.setDeltaMovement(Vec3.ZERO);
        // Held against a wall or the ground it does not build up speed it cannot use.
        if (player.horizontalCollision) velocity.x = velocity.z = 0;
        if (player.verticalCollision) velocity.y = 0;
        double yaw = Math.toRadians(player.getYRot());
        double f = velocity.x * -Math.sin(yaw) + velocity.z * Math.cos(yaw), sd = velocity.x * -Math.cos(yaw) + velocity.z * -Math.sin(yaw);
        double wantRoll = -Math.max(-0.38, Math.min(0.38, sd * 0.40)), wantPitch = Math.max(-0.30, Math.min(0.30, f * 0.28));
        tiltRoll += (wantRoll - tiltRoll) * 0.2;
        tiltPitch += (wantPitch - tiltPitch) * 0.2;
        ShipRecord now = placed(r.get(), frame, player, 1f);
        store.put(now);
        dirty = true;
        if (++poseTick % 2 == 0) { // ten times a second to the others
            if (mirror) MpShips.sendPose(now);
            else MpShips.hostPose(now);
        }
    }

    /** The ship's record with its place at the player's feet (partial: the tick's fraction), nose where they look. */
    private static ShipRecord placed(ShipRecord r, GravityFrame frame, LocalPlayer player, float pt) {
        Vector3d feet = frame.toGal(vec(player.getPosition(pt)), pt);
        Vector3d up = frame.upGal().normalize();
        Vector3d fwd = frame.dirToGal(LookMath.direction(player.getViewYRot(pt), player.getViewXRot(pt)), pt);
        Vector3d flat = new Vector3d(fwd).fma(-fwd.dot(up), up);
        if (flat.lengthSquared() < 1e-6) flat = new Vector3d(up).orthogonalize(new Vector3d(up.y, up.z, up.x));
        flat.normalize();
        Vector3d right = new Vector3d(up).cross(flat);
        Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(right, up, flat)).rotateZ(tiltRoll).rotateX(tiltPitch);
        Vector3d pos = feet.fma(-SEAT_HEIGHT / GravityFrame.SCALE, up);
        return new ShipRecord(r.id(), r.modules(), new double[] {pos.x, pos.y, pos.z}, new double[] {q.x, q.y, q.z, q.w}, r.stage());
    }

    private static void leaveNow(LocalPlayer player) {
        if (boarded == null) return;
        GalaxyCraft.LOG.info("Left ship {}", boarded);
        ShipHud.show(null, null);
        Optional<ShipRecord> was = aboard();
        String leftId = boarded;
        boarded = null;
        velocity = new Vector3d();
        tiltRoll = tiltPitch = 0;
        docked = null;
        if (player != null && was.isPresent()) {
            GravityFrame fr = GalaxyCraftClient.frame();
            if (fr != null && store != null) store.put(placed(was.get(), fr, player, 1f)); // parked level
            stepOut(player, was.get());
        }
        if (store != null && leftId != null) { // the others hear it is parked and free
            ShipRecord parked = store.all().stream().filter(x -> x.id().equals(leftId)).findFirst().orElse(null);
            if (mirror) {
                pilots.set(leftId, "");
                if (parked != null) MpShips.send(dev.moui.galaxycraft.net.MpPayloads.ShipDo.LEAVE, 0, leftId, parked);
            } else {
                pilots.set(leftId, "");
                MpShips.pilot(leftId, "");
                if (parked != null) MpShips.published(parked);
            }
        }
        if (player != null) {
            player.setNoGravity(false);
            if (player.isFallFlying()) player.stopFallFlying();
        }
        saveNow();
        syncHulls();
    }

    /** Beside the ship, not inside its shell: out on its right, at the height of its keel. */
    private static void stepOut(LocalPlayer player, ShipRecord r) {
        GravityFrame frame = GalaxyCraftClient.frame();
        if (frame == null) return;
        Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
        Vector3d right = q.transform(new Vector3d(1, 0, 0));
        Vector3d up = q.transform(new Vector3d(0, 1, 0));
        double out = EXIT_SIDE / GravityFrame.SCALE, down = EXIT_BELOW / GravityFrame.SCALE;
        Vector3d feet = new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]).fma(out, right).fma(-down, up);
        Vector3d mc = frame.toMc(feet);
        player.setPos(mc.x, mc.y, mc.z);
        player.setOldPosAndRot();
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** The player left the ship (tests). */
    public static void leave() {
        leaveNow(Minecraft.getInstance().player);
    }

    public static void saveNow() {
        if (store == null || file == null) return;
        try {
            store.save();
            dirty = false;
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("Could not save the ships: {}", e.toString());
        }
    }

    // ---- friends: the host's ships, mirrored ----

    /** The host sent every ship (a guest, once on entering). */
    static void mirrorList(List<ShipRecord> all) {
        if (!MpUse.guest()) return;
        leaveNow(Minecraft.getInstance().player);
        mirror = true;
        file = null;
        store = ShipStore.memory();
        store.replaceAll(all);
        pilots.clear();
        targets.clear();
        dirty = false;
    }

    /** The host put a ship down or parked it somewhere (a guest). */
    static void mirrorPut(ShipRecord r) {
        if (!mirror || store == null || r.id().equals(boarded)) return;
        targets.remove(r.id());
        store.put(r);
    }

    /** The host says who flies a ship (a guest); naming us after we asked is the yes. */
    static void mirrorPilot(String id, String who) {
        if (!mirror) return;
        pilots.set(id, who);
        var me = Minecraft.getInstance().player;
        if (!id.equals(awaitingBoard)) return;
        if (me != null && who.equals(me.getUUID().toString())) pendingBoard = id;
        else {
            awaitingBoard = null;
            if (me != null) me.sendOverlayMessage(Component.translatable("message.galaxycraft.ship.occupied"));
        }
    }

    /** Another pilot's ship is here (the host passes it on, or sends the guest's own on to us). */
    static void heardPose(String id, double[] pos, double[] rot) {
        if (store == null || id.equals(boarded)) return;
        targets.put(id, new double[][] {pos, rot});
    }

    /** The records glide toward the poses the pilots reported, so a flight is not drawn at ten steps a second. */
    private static void glide() {
        for (var it = targets.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ShipRecord cur = store.all().stream().filter(x -> x.id().equals(e.getKey())).findFirst().orElse(null);
            if (cur == null || e.getKey().equals(boarded)) {
                it.remove();
                continue;
            }
            double[] tp = e.getValue()[0], tr = e.getValue()[1];
            Vector3d d = new Vector3d(tp[0] - cur.pos()[0], tp[1] - cur.pos()[1], tp[2] - cur.pos()[2]);
            Quaterniond q = new Quaterniond(cur.rot()[0], cur.rot()[1], cur.rot()[2], cur.rot()[3]);
            Quaterniond want = new Quaterniond(tr[0], tr[1], tr[2], tr[3]).normalize();
            boolean there = d.length() * GravityFrame.SCALE < 0.02 && Math.abs(q.dot(want)) > 0.99999;
            double k = there ? 1 : 0.5;
            double[] pos = {cur.pos()[0] + d.x * k, cur.pos()[1] + d.y * k, cur.pos()[2] + d.z * k};
            q.slerp(want, k);
            store.put(new ShipRecord(cur.id(), cur.modules(), pos, new double[] {q.x, q.y, q.z, q.w}, cur.stage()));
            if (!mirror) dirty = true;
            if (there) it.remove();
        }
    }

    /** The host's game: a guest wants a ship or pad put down. */
    static void hostPlace(UUID guest, ShipOps.Request req) {
        if (store == null || mirror) {
            dev.moui.galaxycraft.net.MpServer.shipGive(guest, req);
            return;
        }
        double crowd = (req.kind() == ShipOps.PLACE_PAD ? 3.5 : 5.5) / GravityFrame.SCALE;
        var res = ShipOps.apply(store, req, () -> UUID.randomUUID().toString(), crowd);
        if (res.isEmpty()) {
            dev.moui.galaxycraft.net.MpServer.shipGive(guest, req);
            return;
        }
        dirty = true;
        syncHulls();
        MpShips.published(res.get());
        GalaxyCraft.LOG.info("[GalaxyCraft mp] a friend put the ship {} down", res.get().id());
    }

    /** The host's game: a guest asks to fly a ship. */
    static void hostBoard(UUID guest, String id) {
        if (store == null || mirror) return;
        ShipRecord r = store.all().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (r == null || r.isPad() || MapClient.courses().get(id).isPresent()) return;
        if (pilots.board(id, guest.toString())) {
            syncHulls();
            MpShips.pilot(id, guest.toString());
        } else dev.moui.galaxycraft.net.MpServer.shipPilotTo(guest, id, pilots.pilotOf(id));
    }

    /** The host's game: the pilot got out; the ship stays where they parked it. */
    static void hostLeave(UUID guest, String id, double[] pos, double[] rot) {
        if (store == null || mirror || !pilots.release(id, guest.toString())) return;
        park(id, pos, rot);
    }

    /** A guest that flew a ship left the game: the ship stays where it last was. */
    static void hostLeft(UUID guest) {
        if (store == null || mirror) return;
        for (String id : pilots.releaseAll(guest.toString())) park(id, null, null);
    }

    private static void park(String id, double[] pos, double[] rot) {
        targets.remove(id);
        ShipRecord r = store.all().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (r != null) {
            if (pos != null) r = new ShipRecord(id, r.modules(), pos, rot, r.stage());
            store.put(r);
            dirty = true;
            MpShips.published(r);
        }
        syncHulls();
        MpShips.pilot(id, "");
    }

    /** The host's game: the pilot of a ship says where it is. */
    static void hostPose(UUID guest, String id, double[] pos, double[] rot) {
        if (store == null || mirror || !guest.toString().equals(pilots.pilotOf(id))) return;
        heardPose(id, pos, rot);
        dev.moui.galaxycraft.net.MpServer.shipPoseOut(id, pos, rot, guest);
    }

    /** The host's game: a guest entered the galaxy and needs the ships and who flies them. */
    static void hostJoined(UUID guest) {
        if (store == null || mirror) return;
        dev.moui.galaxycraft.net.MpServer.shipList(guest, store.all());
        for (var e : pilots.all().entrySet()) dev.moui.galaxycraft.net.MpServer.shipPilotTo(guest, e.getKey(), e.getValue());
        for (ShipRecord r : store.all()) {
            if (r.isPad()) continue;
            ShipCargo hold = store.cargo(r.id(), r.modules().cabin());
            if (hold.usedBays() > 0) dev.moui.galaxycraft.net.MpServer.shipBays(r.id(), hold.layout(), guest);
        }
    }

    // ---- the map's remote control ----

    /** Blocks a tick per point of the speed stat on a trip sent from the map (about the pulse: 10 to 20). */
    private static final double TRIP_BLOCKS_PER_SPEED = 12;

    /** A ship on the map's trip is where the trip has it now: its record's place, replaced; others as they are. */
    private static ShipRecord onTrip(ShipRecord r) {
        if (r.isPad()) return r;
        Trip t = MapClient.courses().get(r.id()).orElse(null);
        if (t == null) return r;
        var p = t.positionAt(MapClient.now()).minus(UPos.ZERO);
        return new ShipRecord(r.id(), r.modules(), new double[] {p.x, p.y, p.z}, r.rot(), r.stage());
    }

    /**
     * The pilot's ship on a trip: it flies itself. Like the pulse the player stays where it is in
     * Minecraft and the gravity frame slides, so it holds at any speed and distance.
     */
    private static void autopilot(LocalPlayer player, GravityFrame frame, ShipRecord r) {
        player.setNoGravity(true);
        player.resetFallDistance();
        if (player.isFallFlying()) player.stopFallFlying();
        velocity = new Vector3d();
        double[] want = onTrip(r).pos(), at = placed(r, frame, player, 1f).pos();
        Vector3d d = new Vector3d(want[0] - at[0], want[1] - at[1], want[2] - at[2]);
        if (d.lengthSquared() > 0) frame.slide(d);
    }

    /** What the map sees of the ships: this stage's ships, and where a cancelled trip left one. */
    private static final MapShips MAP_SHIPS = new MapShips() {
        @Override
        public List<Ship> ships() {
            if (store == null || mirror) return List.of(); // trips from the map are the host's for now
            List<Ship> out = new ArrayList<>();
            int n = 0;
            for (ShipRecord r : store.all()) {
                if (r.isPad() || !r.stage().equals(stageName())) continue;
                double perTick = ShipStats.of(r.modules()).speed() * TRIP_BLOCKS_PER_SPEED / GravityFrame.SCALE;
                out.add(new Ship(r.id(), "Starshroom " + (++n), perTick, UPos.of(new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]))));
            }
            return out;
        }

        @Override
        public void stopped(String shipId, UPos at) {
            if (store == null) return;
            var p = at.minus(UPos.ZERO);
            for (ShipRecord r : store.all())
                if (r.id().equals(shipId)) {
                    store.put(new ShipRecord(r.id(), r.modules(), new double[] {p.x, p.y, p.z}, r.rot(), r.stage()));
                    dirty = true;
                    syncHulls();
                }
        }
    };

    // ---- solid hulls ----

    /**
     * Each ship of this stage that nobody is aboard is solid: its hull's skin goes into the
     * galaxy's collision (the way a planet's does), so the player walks on it and not through it.
     * The aboard one is not (the player is in it); ships of other stages are not either.
     */
    private static void syncHulls() {
        if (store == null) {
            solid.keySet().forEach(ShipClient::dropHull);
            solid.clear();
            return;
        }
        String stage = stageName();
        java.util.Set<String> want = new java.util.HashSet<>();
        for (ShipRecord r : store.all()) {
            if (!r.stage().equals(stage) || r.id().equals(boarded) || pilots.pilotOf(r.id()) != null
                    || MapClient.courses().get(r.id()).isPresent()) continue; // flown by someone, or on a trip: no hull left behind
            want.add(r.id());
            if (solid.get(r.id()) == r) continue;
            solid.put(r.id(), r);
            GalaxyCraft.FIELD.upsertTriangles(fieldId(r.id()), hullTris(r));
        }
        for (java.util.Iterator<String> it = solid.keySet().iterator(); it.hasNext(); ) {
            String id = it.next();
            if (!want.contains(id)) {
                dropHull(id);
                it.remove();
            }
        }
    }

    private static void dropHull(String id) {
        GalaxyCraft.FIELD.removePart(fieldId(id));
    }

    private static int fieldId(String shipId) {
        return FIELD_ID | (shipId.hashCode() & 0x00FFFFFF);
    }

    /** The hull's triangles in galaxy coordinates for a ship record. */
    private static List<dev.moui.galaxycraft.geom.Tri> hullTris(ShipRecord r) {
        double unitsPerPixel = 1 / (GravityFrame.SCALE * 16);
        Matrix4d m = new Matrix4d().translation(r.pos()[0], r.pos()[1], r.pos()[2])
                .rotate(new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3])).scale(unitsPerPixel);
        List<dev.moui.galaxycraft.geom.Tri> out = new ArrayList<>();
        for (float[] f : r.isPad() ? ShipModels.padTriangles() : ShipModels.hullTriangles(Math.max(1, r.modules().hull()))) {
            Vector3d a = m.transformPosition(new Vector3d(f[0], f[1], f[2]));
            Vector3d b = m.transformPosition(new Vector3d(f[3], f[4], f[5]));
            Vector3d c = m.transformPosition(new Vector3d(f[6], f[7], f[8]));
            out.add(dev.moui.galaxycraft.geom.Tri.of(a, b, c));
        }
        return out;
    }

    // ---- drawing ----

    /** The ghost's colours (RGBA, the alpha how much is mixed over the piece): where the item goes, or why it cannot. */
    static final int GHOST_OK = 0x40D0FF99, GHOST_NO = 0xFF4040AA;

    /** The pieces of this stage's ships (and their cushions), universe units, for EntityClient's frame. */
    static void pieces(EntityClient ec, float pt, List<EntityWire.Piece> out) {
        if (store == null) return;
        String stage = stageName();
        LocalPlayer me = Minecraft.getInstance().player;
        GravityFrame fr = GalaxyCraftClient.frame();
        for (ShipRecord raw : store.all()) {
            ShipRecord r = onTrip(raw);
            if (!r.stage().equals(stage)) continue;
            if (r.isPad()) {
                drawPad(ec, r, 0, out);
                continue;
            }
            // The aboard ship follows the player as drawn between ticks (not as the tick left it),
            // so it moves as smoothly as the view does.
            if (r.id().equals(boarded) && me != null && fr != null) r = placed(r, fr, me, pt);
            drawShip(ec, r, 0, pt, true, out);
        }
        // The item in hand, where it would go (tinted: cyan if it may go there, red if not).
        if (me != null && boarded == null && Minecraft.getInstance().gui.screen() == null) {
            var held = me.getMainHandItem();
            boolean ship = held.is(ShipBlocks.TOAD_SHIP), pad = held.is(ShipBlocks.LANDING_PAD);
            if (ship || pad) {
                Plan p = plan(me, pad);
                if (p != null && !(p.problem() != null && p.center().lengthSquared() == 0)) {
                    int tint = p.ok() ? GHOST_OK : GHOST_NO;
                    Quaterniond q = p.rot();
                    double[] pos = {p.center().x, p.center().y, p.center().z}, rot = {q.x, q.y, q.z, q.w};
                    if (pad) drawPad(ec, new ShipRecord("ghost", new ShipModules(0, 1, 0, 1), pos, rot, stage), tint, out);
                    else drawShip(ec, new ShipRecord("ghost", ToadShipItem.modules(held).orElse(new ShipModules(0, 1, 0, 1)), pos, rot, stage),
                            tint, pt, false, out);
                }
            }
        }
    }

    private static void drawPad(EntityClient ec, ShipRecord r, int overlay, List<EntityWire.Piece> out) {
        double unitsPerPixel = 1 / (GravityFrame.SCALE * 16);
        ShipModels.PartModel pad = ShipModels.pad();
        int model = ec.shipModel(pad, pad.quads());
        int skin = ec.shipSkin(pad.skin(), pad.skin().width(), pad.skin().height(), pad.skin().argb());
        if (model < 0 || skin < 0) return;
        Matrix4d pm = new Matrix4d().translation(r.pos()[0], r.pos()[1], r.pos()[2])
                .rotate(new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3])).scale(unitsPerPixel);
        out.add(new EntityWire.Piece(model, skin, overlay, 0xFFFFFFFF, rows(pm)));
    }

    private static void drawShip(EntityClient ec, ShipRecord r, int overlay, float pt, boolean cushions, List<EntityWire.Piece> out) {
        double unitsPerPixel = 1 / (GravityFrame.SCALE * 16);
        Matrix4d base = new Matrix4d().translation(r.pos()[0], r.pos()[1], r.pos()[2])
                .rotate(new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]));
        Matrix4d px = new Matrix4d(base).scale(unitsPerPixel);
        for (ShipModels.Placed pl : ShipModels.layout(r.modules())) {
            ShipModels.PartModel part = pl.part();
            int model = ec.shipModel(part, part.quads());
            int skin = ec.shipSkin(part.skin(), part.skin().width(), part.skin().height(), part.skin().argb());
            if (model < 0 || skin < 0) continue;
            Matrix4d m = new Matrix4d(px).translate(pl.offset()[0], pl.offset()[1], pl.offset()[2]);
            out.add(new EntityWire.Piece(model, skin, overlay, part.tint(), rows(m)));
        }
        // The portholes: Minecraft's glass.
        for (ShipModels.PartModel g : ShipModels.glass(r.modules().hull())) {
            int model = ec.shipModel(g, g.quads());
            int skin = ec.glassSkin();
            if (model >= 0 && skin >= 0) out.add(new EntityWire.Piece(model, skin, overlay, 0xFFFFFFFF, rows(px)));
        }
        if (!cushions) return;
        // The cabin's cushions: Minecraft's own, one per seat, the driver's under the player.
        Matrix4d blocks = new Matrix4d(base).scale(1 / GravityFrame.SCALE);
        for (float[] s : SeatLayout.seats(ShipStats.of(r.modules()).seats()))
            ec.shipCushion(new Matrix4d(blocks).translate(s[0] / 16.0, s[1] / 16.0 + 0.1, s[2] / 16.0), pt, out);
        // The hold: the chests installed, behind the seats.
        if (store != null)
            for (CargoLayout.Placed c : CargoLayout.chests(store.cargo(r.id(), r.modules().cabin())))
                ec.shipChest(new Matrix4d(blocks).translate(c.center()[0] / 16.0, c.center()[1] / 16.0, c.center()[2] / 16.0),
                        c.large(), c.yaw(), CargoLayout.SCALE, pt, out);
    }

    /** A matrix as the wire's 3x4 row-major. */
    static double[] rows(Matrix4d g) {
        return new double[] {g.m00(), g.m10(), g.m20(), g.m30(), g.m01(), g.m11(), g.m21(), g.m31(), g.m02(), g.m12(), g.m22(), g.m32()};
    }

    private static Vector3d vec(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    /** The ship item's name where the hooks need one (unused text keeps the import honest). */
    @SuppressWarnings("unused")
    private static Component name() {
        return Component.translatable("item.galaxycraft.toad_ship");
    }
}
