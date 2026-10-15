package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.launchstar.FlightProfile;
import dev.moui.galaxycraft.launchstar.LaunchStarBlocks;
import dev.moui.galaxycraft.launchstar.LaunchStarHooks;
import dev.moui.galaxycraft.launchstar.LaunchStarModel;
import dev.moui.galaxycraft.launchstar.Route;
import dev.moui.galaxycraft.launchstar.StarRecord;
import dev.moui.galaxycraft.launchstar.StarStore;
import dev.moui.galaxycraft.ship.ShipBoarding;
import dev.moui.galaxycraft.ship.model.ShipModels;
import dev.moui.galaxycraft.view.EntityWire;
import dev.moui.galaxycraft.voxel.PlanetLayout;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Launch Stars: the star items put down on a planet's ground (two on two planets link), drawn as
 * SMG2's own star (tilted, spinning, glowing) with a line of small stars showing the course, and the
 * flight itself. Stand by a linked star and hold Shift: it spins up, then flings the player along a
 * course that bends around the planets in the way (Route), as a function of the tick, by sliding the
 * gravity frame like the pulse and the map's autopilot. Shift again cancels. Right click names a
 * star: stars with the same code are linked. Positions are universe units, like ships'.
 */
public final class LaunchStarClient {
    /** A star is aimed within this many blocks of the eye, by a sphere of this radius. */
    private static final double REACH = 5, RADIUS = 2.2;
    /** The star's middle this far above the ground (blocks), and its spin (radians a tick) at rest and at the end of the wind-up. */
    private static final double LIFT = 1.4, SPIN_IDLE = 0.04, SPIN_FAST = 0.5;
    /** Hold ticks before the fling. */
    private static final int WINDUP = 15;
    /** The course is shown within this many blocks of a star, and its dots are this far apart at least (blocks); never more than MAX_DOTS. */
    private static final double SHOW = 48, DOT_GAP = 24, DOT_SIZE = 0.18;
    private static final int MAX_DOTS = 200;
    /** Planets' gravity plus this many blocks is kept out of by the course. */
    private static final double MARGIN = 30;
    private static final int SAVE_TICKS = 100;

    public static final LaunchStarHooks HOOKS = LaunchStarClient::place;

    /** The course of a flight: from, to, route, speed profile, ticks flown. */
    private record Fling(String from, String to, Route route, FlightProfile profile, Vector3d upStart, Vector3d upEnd) {}

    private static StarStore store;
    private static boolean dirty;
    private static int sinceSave, age, windup;
    private static String winding;
    private static Fling flight;
    private static int flown;
    /** Shift must be let go after the fling before it can cancel the flight. */
    private static boolean sneakFree;
    /** The star the crosshair was on last tick (its hint is shown once per look). */
    private static String lastAimed;
    private static final Map<String, Route> routes = new HashMap<>();

    private LaunchStarClient() {}

    /** A world's planets folder: its stars live in its stars.json (null: no world). */
    public static void enterWorld(Path planetsDir) {
        saveNow();
        mirror = false;
        awaiting = null;
        flight = null;
        winding = null;
        windup = 0;
        routes.clear();
        store = planetsDir == null ? null : StarStore.load(planetsDir.resolve("stars.json"));
        dirty = false;
        LaunchStarModel.load(ShipClient.shipDir());
    }


    /** This game is a guest: its stars are a mirror of the host's (never saved); a placing, pickup or new code goes to the host. */
    private static boolean mirror;
    /** What a guest asked the host for and waits to hear of ("place", "code"): the hint is shown when the answer comes. */
    private static String awaiting;

    /** The host sent every star (a guest, once on entering). */
    static void mirrorList(List<StarRecord> all) {
        if (!MpUse.guest()) return;
        mirror = true;
        store = StarStore.memory();
        store.replaceAll(all);
        dirty = false;
        routes.clear();
    }

    static void mirrorPut(StarRecord r) {
        if (!mirror || store == null) return;
        store.putExact(r);
        routes.clear();
        String what = awaiting;
        awaiting = null;
        var me = Minecraft.getInstance().player;
        if (what != null && me != null)
            me.sendOverlayMessage(Component.translatable(store.partnerOf(r.id()).isPresent() ? "message.galaxycraft.star.linked"
                    : what.equals("place") ? "message.galaxycraft.star.placed" : "message.galaxycraft.star.named", r.code()));
    }

    static void mirrorGone(String id) {
        if (!mirror || store == null) return;
        store.remove(id);
        routes.clear();
    }

    /** The host's game: a guest's request, applied to the truth, and everyone told. */
    static void hostApply(UUID guest, dev.moui.galaxycraft.launchstar.StarOps.Request req) {
        if (store == null || mirror) {
            if (req.kind() == dev.moui.galaxycraft.launchstar.StarOps.PLACE) dev.moui.galaxycraft.net.MpServer.starGive(guest);
            return;
        }
        var res = dev.moui.galaxycraft.launchstar.StarOps.apply(store, req, () -> UUID.randomUUID().toString(), 6 / GravityFrame.SCALE);
        switch (res.kind()) {
            case PUT -> {
                dirty = true;
                routes.clear();
                MpStars.published(res.star());
            }
            case GONE -> {
                dirty = true;
                routes.clear();
                MpStars.gone(res.star().id());
                dev.moui.galaxycraft.net.MpServer.starGive(guest);
            }
            case REJECT -> {
                if (req.kind() == dev.moui.galaxycraft.launchstar.StarOps.PLACE) dev.moui.galaxycraft.net.MpServer.starGive(guest);
            }
        }
    }

    public static void saveNow() {
        if (store == null || !dirty || mirror) return;
        try {
            store.save();
            dirty = false;
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("The Launch Stars could not be saved ({})", e.getMessage());
        }
    }

    /** The stars (tests). */
    public static List<StarRecord> stars() {
        return store == null ? List.of() : store.all();
    }

    /**
     * The player is held by a star (winding up or flying): Minecraft's physics carry them, so Mario
     * goes where the player is instead of the player following Mario.
     */
    public static boolean busy() {
        return flight != null || winding != null;
    }

    /** Up for the frame during a flight: it stays through the first half, then turns smoothly to the arrival star's up. */
    static Vector3d upToward(GravityFrame frame) {
        Vector3d up = frame.upGal();
        Fling f = flight;
        if (f == null) return up;
        double p = Math.min(1, flown / (double) f.profile().ticks());
        double x = Math.max(0, Math.min(1, (p - 0.45) / 0.55));
        double k = x * x * (3 - 2 * x); // smoothstep
        Vector3d target = new Quaterniond().slerp(new Quaterniond().rotationTo(f.upStart(), f.upEnd()), k).transform(new Vector3d(f.upStart()));
        double angle = up.angle(target);
        // a share of what is left, so it eases out, but never faster than 5 degrees a tick
        double step = Math.min(Math.toRadians(5), angle * 0.07 + Math.toRadians(0.05));
        return GravityFrame.limitTurn(up, target, step);
    }

    /** How far the third-person camera pulls back at full speed (blocks), over the usual distance. */
    private static final double PULL_BACK = 9;
    /** Ticks to reach (and leave) full speed for the camera's purposes. */
    private static final double PULL_TICKS = 30;

    /**
     * The third-person camera's distance during a flight (blocks), or null when not flying: it pulls
     * back as the star gains speed and closes in again before the landing, so the player is seen to travel.
     */
    static Double cameraTarget(double usual) {
        Fling f = flight;
        if (f == null) return null;
        double share = Math.max(0, Math.min(1, Math.min(flown, f.profile().ticks() - flown) / PULL_TICKS));
        share = share * share * (3 - 2 * share);
        return usual + PULL_BACK * share;
    }

    /** The player is being flung right now. */
    public static boolean flying() {
        return flight != null;
    }

    /** Tests: a star down at pos (universe units) with that up, named code (stars with the same code link); its id. */
    public static String placeForTest(Vector3d pos, Vector3d up, String code) {
        StarRecord r = store.place(new StarRecord(UUID.randomUUID().toString(), new double[] {pos.x, pos.y, pos.z},
                new double[] {up.x, up.y, up.z}, stage(), bodyOf(pos), null));
        r = store.setCode(r.id(), code);
        dirty = true;
        routes.clear();
        return r.id();
    }

    /** Tests: the flight from that star to its partner starts at once (no wind-up); false when it has none. */
    public static boolean launchForTest(String fromId) {
        LocalPlayer me = Minecraft.getInstance().player;
        GravityFrame frame = GalaxyCraftClient.frame();
        StarRecord from = store == null ? null : store.get(fromId), to = from == null ? null : store.partnerOf(fromId).orElse(null);
        if (me == null || frame == null || to == null) return false;
        launch(me, frame, from, to);
        return true;
    }

    /** The ticks the current flight has run and its length (tests); -1: none. */
    public static int[] progress() {
        return flight == null ? new int[] {-1, -1} : new int[] {flown, flight.profile().ticks()};
    }

    private static String stage() {
        String s = PlanetClient.stage();
        return s == null ? "" : s;
    }

    private static Vector3d vec(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    private static Vector3d upOf(double[] u) {
        return new Vector3d(u[0], u[1], u[2]).normalize();
    }

    private static Vector3d posOf(StarRecord r) {
        return new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]);
    }

    // ---- placing ----

    private static String bodyOf(Vector3d at) {
        PlanetSession best = null;
        double bd = Double.MAX_VALUE;
        for (PlanetSession s : PlanetClient.bodies()) {
            if (!s.active()) continue;
            double d = s.center().distance(at) - s.gravityUnits();
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        if (best == null) return "";
        Vector3d c = best.center();
        return Math.round(c.x) + "," + Math.round(c.y) + "," + Math.round(c.z);
    }

    private static boolean crowded(Vector3d at, double blocks) {
        for (StarRecord o : store.all())
            if (o.stage().equals(stage()) && posOf(o).distance(at) * GravityFrame.SCALE < blocks) return true;
        return false;
    }

    /** Where a star would go: on the ground ahead, its middle LIFT above it; null when there is no ground to point at. */
    private static ShipClient.Plan plan(Player player) {
        ShipClient.Plan p = ShipClient.plan(player, true);
        if (p == null || p.problem() != null && p.center().lengthSquared() == 0) return p == null ? null : new ShipClient.Plan(p.center(), p.rot(), "message.galaxycraft.star.ground");
        Vector3d up = new Vector3d(0, 1, 0).rotate(p.rot());
        Vector3d c = new Vector3d(p.center()).fma(LIFT / GravityFrame.SCALE, up);
        String problem = p.problem() != null ? p.problem() : crowded(c, 6) ? "message.galaxycraft.star.crowded" : null;
        return new ShipClient.Plan(c, p.rot(), problem);
    }

    private static boolean place(Player player, InteractionHand hand) {
        if (store == null || flight != null) return false;
        ShipClient.Plan p = plan(player);
        if (p == null) return false;
        if (!p.ok()) {
            player.sendOverlayMessage(Component.translatable(p.problem()));
            return false;
        }
        Vector3d up = new Vector3d(0, 1, 0).rotate(p.rot());
        if (mirror) { // a guest: the host places it, checks and uses up the item, and answers with a StarPut
            awaiting = "place";
            MpStars.send(dev.moui.galaxycraft.launchstar.StarOps.PLACE, hand == InteractionHand.MAIN_HAND ? 0 : 1, "",
                    new double[] {p.center().x, p.center().y, p.center().z}, new double[] {up.x, up.y, up.z}, stage(), bodyOf(p.center()), "");
            return true;
        }
        StarRecord stored = store.place(new StarRecord(UUID.randomUUID().toString(), new double[] {p.center().x, p.center().y, p.center().z},
                new double[] {up.x, up.y, up.z}, stage(), bodyOf(p.center()), null));
        dirty = true;
        routes.clear();
        MpStars.published(stored);
        StationClient.useUp(player, hand, LaunchStarBlocks.LAUNCH_STAR);
        player.sendOverlayMessage(Component.translatable(store.partnerOf(stored.id()).isPresent() ? "message.galaxycraft.star.linked"
                : "message.galaxycraft.star.placed", stored.code()));
        GalaxyCraft.LOG.info("A Launch Star {} is down at {} (code {})", stored.id(), p.center(), stored.code());
        return true;
    }

    /** Takes a star away and gives its item back. */
    private static void pickUp(Player player, StarRecord r) {
        if (mirror) { // the host removes it and gives the item to whoever got there first
            MpStars.send(dev.moui.galaxycraft.launchstar.StarOps.PICKUP, 0, r.id(), null, null, null, null, null);
            return;
        }
        store.remove(r.id());
        dirty = true;
        routes.clear();
        MpStars.gone(r.id());
        if (player.getAbilities().instabuild) return;
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        UUID id = player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp != null) {
                ItemStack star = new ItemStack(LaunchStarBlocks.LAUNCH_STAR);
                if (!sp.getInventory().add(star)) sp.spawnAtLocation((net.minecraft.server.level.ServerLevel) sp.level(), star); // a full inventory drops it at the feet
            }
        });
    }

    // ---- aiming ----

    private static StarRecord aimed(Player player, GravityFrame frame) {
        if (store == null) return null;
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot())).normalize();
        List<StarRecord> here = new ArrayList<>();
        List<double[]> centers = new ArrayList<>();
        for (StarRecord s : store.all())
            if (s.stage().equals(stage())) {
                here.add(s);
                centers.add(s.pos());
            }
        int i = ShipBoarding.nearest(eye, look, centers, REACH / GravityFrame.SCALE, RADIUS / GravityFrame.SCALE);
        return i < 0 ? null : here.get(i);
    }

    /** A right click is the star's (it winds up or flies), not the item's. */
    public static boolean swallowsUse() {
        if (store == null) return false;
        if (flight != null) return true;
        LocalPlayer me = Minecraft.getInstance().player;
        GravityFrame frame = GalaxyCraftClient.frame();
        return me != null && frame != null && Minecraft.getInstance().gui.screen() == null && aimed(me, frame) != null;
    }

    // ---- each tick ----

    /**
     * Client tick. rightDown: right click went down this tick; attack: left click went down; sneak:
     * Shift is down. Returns the mouse buttons the star took (so the world does not also act on them).
     */
    static int tick(LocalPlayer player, GravityFrame frame, boolean rightDown, boolean attack, boolean sneak, int rightMask, int leftMask) {
        age++;
        if (store == null || player == null || frame == null) return 0;
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            saveNow();
        }
        if (flight != null) {
            if (!sneak) sneakFree = true;
            fly(player, frame, sneak && sneakFree);
            return rightMask | leftMask;
        }
        StarRecord a = aimed(player, frame);
        if (a == null) {
            lastAimed = null;
            if (winding != null) stopWinding(player);
            return 0;
        }
        StarRecord to = store.partnerOf(a.id()).orElse(null);
        if (!a.id().equals(lastAimed)) {
            lastAimed = a.id();
            player.sendOverlayMessage(Component.translatable(to != null ? "message.galaxycraft.star.hint" : "message.galaxycraft.star.alone", a.code()));
        }
        if (attack) {
            pickUp(player, a);
            return leftMask;
        }
        if (rightDown) {
            if (Minecraft.getInstance().gui.screen() == null) {
                StarRecord shown = a;
                Minecraft.getInstance().execute(() -> StarCodeScreen.open(shown));
            }
            return rightMask;
        }
        if (to == null || !sneak) {
            if (winding != null) stopWinding(player);
            return rightMask;
        }
        if (winding == null) windup = 0;
        winding = a.id();
        windup++;
        player.setNoGravity(true);
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        Vector3d d = posOf(a).sub(frame.toGal(vec(player.position())));
        frame.slide(d.mul(0.3)); // pulled onto the star
        if (windup >= WINDUP) launch(player, frame, a, to);
        return rightMask;
    }

    /** A tick with a screen open (inventory, chest) and no flight: the stars keep turning, nothing else. */
    static void idle() {
        age++;
    }

    /** The code screen's Done: the star's new code (stars with the same code are linked). */
    static void setCode(String id, String code) {
        if (store == null) return;
        if (mirror) {
            if (StarStore.clean(code).isEmpty()) return;
            awaiting = "code";
            MpStars.send(dev.moui.galaxycraft.launchstar.StarOps.CODE, 0, id, null, null, null, null, code);
            return;
        }
        StarRecord r = store.setCode(id, code);
        if (r == null) return;
        dirty = true;
        routes.clear();
        MpStars.published(r);
        var me = Minecraft.getInstance().player;
        if (me != null)
            me.sendOverlayMessage(Component.translatable(store.partnerOf(id).isPresent() ? "message.galaxycraft.star.linked"
                    : "message.galaxycraft.star.named", r.code()));
    }

    private static void stopWinding(LocalPlayer player) {
        winding = null;
        windup = 0;
        player.setNoGravity(false);
    }

    private static List<Route.Sphere> obstacles() {
        List<Route.Sphere> out = new ArrayList<>();
        for (PlanetLayout.Sphere s : PlanetClient.streamedSpheres()) out.add(new Route.Sphere(s.center(), s.gravity() + MARGIN / GravityFrame.SCALE));
        return out;
    }

    private static Route routeOf(StarRecord from, StarRecord to) {
        String key = from.id() + ">" + to.id();
        Route r = routes.get(key);
        if (r == null) {
            r = Route.plan(posOf(from), upOf(from.up()), posOf(to), upOf(to.up()), obstacles());
            routes.put(key, r);
        }
        return r;
    }

    private static void launch(LocalPlayer player, GravityFrame frame, StarRecord from, StarRecord to) {
        routes.remove(from.id() + ">" + to.id());
        Route r = routeOf(from, to);
        flight = new Fling(from.id(), to.id(), r, FlightProfile.of(r.length() * GravityFrame.SCALE), upOf(from.up()), upOf(to.up()));
        flown = 0;
        sneakFree = false;
        winding = null;
        windup = 0;
        GalaxyCraft.LOG.info("Launch Star flight: {} blocks, {} ticks", Math.round(r.length() * GravityFrame.SCALE), flight.profile().ticks());
    }

    private static void fly(LocalPlayer player, GravityFrame frame, boolean sneak) {
        flown++;
        player.setNoGravity(true);
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        if (player.isFallFlying()) player.stopFallFlying();
        double blocks = flight.profile().arcAt(flown);
        Vector3d want = flight.route().at(blocks / GravityFrame.SCALE);
        Vector3d d = want.sub(frame.toGal(vec(player.position())));
        if (d.lengthSquared() > 0) frame.slide(d);
        if (flown >= flight.profile().ticks() || sneak && flown > 10) {
            GalaxyCraft.LOG.info("Launch Star flight over after {} ticks ({})", flown, sneak && flown < flight.profile().ticks() ? "cancelled" : "landed");
            flight = null;
            Flight.end(player);
        }
    }

    // ---- drawing ----

    private static double spinOf(StarRecord r, float pt) {
        double base = (age + pt) * SPIN_IDLE;
        if (winding != null && winding.equals(r.id())) return base + Math.min(windup, WINDUP) * (SPIN_FAST - SPIN_IDLE) * (age + pt) / 8;
        return base;
    }

    /** The star's lean from upright (radians) and how far it wobbles, and how high it bobs (blocks). */
    private static final double TILT = 0.5, WOBBLE = 0.12, BOB = 0.12;

    /**
     * One star at pos: leaning and wobbling as it turns, so it is not a flat disc; glow: drawn at
     * full brightness (not dimmed by the planet's light).
     */
    private static void drawStar(EntityClient ec, Vector3d pos, Vector3d up, double spin, double scale, int overlay, int tint, boolean glow,
            List<EntityWire.Piece> out) {
        double unitsPerPixel = scale / (GravityFrame.SCALE * 16);
        double t = age * 0.03;
        Vector3d at = new Vector3d(pos).fma(Math.sin(t * 1.7) * BOB / GravityFrame.SCALE, up);
        Matrix4d m = new Matrix4d().translation(at.x, at.y, at.z)
                .rotate(new Quaterniond().rotationTo(new Vector3d(0, 1, 0), up))
                .rotateY(t * 0.7) // the lean turns slowly around
                .rotateX(TILT + Math.sin(t * 2.3) * WOBBLE)
                .rotateY(spin).scale(unitsPerPixel);
        for (ShipModels.PartModel part : LaunchStarModel.parts()) {
            int model = ec.shipModel(part, part.quads());
            int skin = ec.shipSkin(part.skin(), part.skin().width(), part.skin().height(), part.skin().argb());
            if (model < 0 || skin < 0) continue;
            if (glow) ec.glowing.add(out.size());
            out.add(new EntityWire.Piece(model, skin, overlay, tint, ShipClient.rows(m)));
        }
    }

    /**
     * The direction the player is flying in the galaxy (unit), between ticks, or null when not flying:
     * the drawn player lies along it, in the elytra's flying pose.
     */
    static Vector3d heading(float pt) {
        Fling f = flight;
        if (f == null) return null;
        return f.route().tangent(f.profile().arcAt(flown + pt) / GravityFrame.SCALE);
    }

    /** The pieces of this stage's stars (and the course near one, and the ghost of the item in hand), universe units. */
    static void pieces(EntityClient ec, float pt, List<EntityWire.Piece> out) {
        if (store == null) return;
        LocalPlayer me = Minecraft.getInstance().player;
        GravityFrame fr = GalaxyCraftClient.frame();
        Vector3d eye = me != null && fr != null ? fr.toGal(vec(me.getEyePosition())) : null;
        for (StarRecord r : store.all()) {
            if (!r.stage().equals(stage())) continue;
            StarRecord to = store.partnerOf(r.id()).orElse(null);
            boolean linked = to != null;
            drawStar(ec, posOf(r), upOf(r.up()), spinOf(r, pt), 1, 0, linked ? 0xFFFFFFFF : 0xA0A0A0FF, true, out);
            if (linked) // a soft halo: a bigger, see-through copy turning the other way
                drawStar(ec, posOf(r), upOf(r.up()), -spinOf(r, pt) * 0.6, 1.3, 0, 0xFFD04040, true, out);
            if (linked && eye != null && flight == null && posOf(r).distance(eye) * GravityFrame.SCALE < SHOW)
                dots(ec, r, to, eye, pt, out);
        }
        if (me != null && flight == null && Minecraft.getInstance().gui.screen() == null && me.getMainHandItem().is(LaunchStarBlocks.LAUNCH_STAR)) {
            ShipClient.Plan p = plan(me);
            if (p != null && !(p.problem() != null && p.center().lengthSquared() == 0)) {
                Vector3d up = new Vector3d(0, 1, 0).rotate(p.rot());
                drawStar(ec, p.center(), up, (age + pt) * SPIN_IDLE, 1, p.ok() ? ShipClient.GHOST_OK : ShipClient.GHOST_NO, 0xFFFFFFFF, false, out);
            }
        }
    }

    /** The course as small stars, evenly along it. */
    private static void dots(EntityClient ec, StarRecord from, StarRecord to, Vector3d eye, float pt, List<EntityWire.Piece> out) {
        Route r = routeOf(from, to);
        double lenBlocks = r.length() * GravityFrame.SCALE;
        int n = (int) Math.min(MAX_DOTS, Math.max(2, lenBlocks / DOT_GAP));
        double phase = ((age + pt) * 0.02) % 1.0; // the dots drift toward the other star
        for (int i = 1; i < n; i++) {
            double s = (i + phase) / n * r.length();
            Vector3d p = r.at(Math.min(s, r.length()));
            drawStar(ec, p, r.tangent(s), (age + pt) * 0.1 + i, DOT_SIZE, 0, 0xFFE070FF, true, out);
        }
    }
}
