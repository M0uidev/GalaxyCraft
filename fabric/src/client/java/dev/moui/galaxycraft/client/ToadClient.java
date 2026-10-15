package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.ship.ShipBoarding;
import dev.moui.galaxycraft.ship.model.BckReader;
import dev.moui.galaxycraft.ship.model.ShipModels;
import dev.moui.galaxycraft.shadow.ShadowToads;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.toad.ToadContext;
import dev.moui.galaxycraft.toad.ToadConversation;
import dev.moui.galaxycraft.toad.ToadLook;
import dev.moui.galaxycraft.toad.ToadModels;
import dev.moui.galaxycraft.toad.ToadPose;
import dev.moui.galaxycraft.toad.ToadRecord;
import dev.moui.galaxycraft.toad.ToadScript;
import dev.moui.galaxycraft.toad.ToadStore;
import dev.moui.galaxycraft.toad.ToadType;
import dev.moui.galaxycraft.view.EntityWire;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The Toads of the galaxy: records in toads.json next to the planets (like ShipClient's ships), drawn
 * by the game from SMG2's Kinopio through EntityClient, talked to with a right click. Not Minecraft
 * entities.
 */
public final class ToadClient {
    /** Blocks: how far a click reaches and the sphere a Toad is hit in. */
    static final double REACH = 6, RADIUS = 1.1;
    private static final double AHEAD = 3, DOWN_REACH = 14;

    private static ToadStore store;
    private static Path file;
    private static boolean dirty;
    private static int talks;
    /** Outposts made on worker threads, waiting for the client thread to put them (planet key, its Toads). */
    private record Outposts(String planetKey, List<ToadRecord> toads) {}
    private static final java.util.concurrent.ConcurrentLinkedQueue<Outposts> OUTPOSTS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private ToadClient() {}

    /** A world's planets folder: its Toads live in its toads.json (null: no world). */
    public static void enterWorld(Path planetsDir) {
        saveNow();
        file = planetsDir == null ? null : planetsDir.resolve("toads.json");
        store = file == null ? null : ToadStore.load(file);
        dirty = false;
        OUTPOSTS.clear();
        ToadModels.load(ShipClient.shipDir());
    }

    /** The Toads (tests). */
    public static List<ToadRecord> toads() {
        drainOutposts();
        return store == null ? List.of() : store.all();
    }

    /**
     * A planet's outposts, made with its cells (any thread): put on the next frame, once, whatever
     * happens to them later (a planet made again offers the same ones).
     */
    public static void offerOutposts(String planetKey, List<ToadRecord> toads) {
        if (!toads.isEmpty()) OUTPOSTS.add(new Outposts(planetKey, List.copyOf(toads)));
    }

    /**
     * A station's Technician, a few blocks from its core on the deck, facing the core (put again,
     * with the same id, when the station is unfolded somewhere else). station: its id, center in
     * galaxy units, rotation as its grid's.
     */
    public static void putTechnician(PlanetSession session, String stationId, Vector3d center, Quaterniond rotation) {
        if (store == null || MpUse.guest()) return;
        Vector3d up = new Vector3d(0, 1, 0).rotate(rotation), side = new Vector3d(1, 0, 0).rotate(rotation);
        Vector3d from = new Vector3d(center).fma(2.5 / GravityFrame.SCALE, side).fma(6 / GravityFrame.SCALE, up);
        Vector3d[] hit = session.surfaceAlong(from, new Vector3d(up).negate(), 12);
        if (hit == null) {
            GalaxyCraft.LOG.info("No deck for the Technician of station {}", stationId);
            return;
        }
        Vector3d fwd = new Vector3d(side).negate(), n = hit[1];
        fwd.fma(-fwd.dot(n), n).normalize();
        Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(new Vector3d(n).cross(fwd), n, fwd));
        store.put(new ToadRecord("toad-station-" + stationId, ToadType.TECHNICIAN, new double[] {hit[0].x, hit[0].y, hit[0].z},
                new double[] {q.x, q.y, q.z, q.w}, stageName()));
        dirty = true;
        saveNow();
    }

    /** A station packed: its Technician goes with it. */
    public static void removeTechnician(String stationId) {
        if (store == null || MpUse.guest()) return;
        STATES.remove("toad-station-" + stationId);
        if (store.remove("toad-station-" + stationId).isPresent()) {
            dirty = true;
            saveNow();
        }
    }

    private static int homeWait;

    /**
     * The home planet's Guide: once, when the player first stands near the world's first planet, a
     * few blocks ahead on its ground (marked placed under "home", so it never comes back).
     */
    private static void homeGuide(LocalPlayer me, GravityFrame fr) {
        if (store == null || MpUse.guest() || me == null || fr == null || store.wasPlaced("home") || ++homeWait < 30) return;
        homeWait = 0;
        PlanetSession home = PlanetClient.sessionOf(0);
        if (home == null || !home.active() || home.planet() == null || home.station() != null) return;
        double blocks = fr.toGal(vec(me.getEyePosition())).distance(home.center()) * GravityFrame.SCALE;
        if (blocks > home.planet().surface() + 24) return;
        if (place(me, ToadType.GUIDE) != null) return; // no ground in reach yet: tried again
        store.markPlaced("home");
        dirty = true;
        saveNow();
    }

    private static void drainOutposts() {
        Outposts o;
        while ((o = OUTPOSTS.poll()) != null) {
            if (store == null || MpUse.guest() || store.wasPlaced(o.planetKey())) continue; // a guest gets the host's Toads
            for (ToadRecord r : o.toads()) store.put(r);
            store.markPlaced(o.planetKey());
            dirty = true;
        }
        saveNow();
    }

    private static void saveNow() {
        if (store == null || !dirty || MpUse.guest()) return; // a guest's Toads are the host's: a mirror, never saved
        try {
            store.save();
            dirty = false;
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("The Toads could not be saved ({})", e.getMessage());
        }
    }

    private static String stageName() {
        String s = PlanetClient.stage();
        return s == null ? "" : s;
    }

    private static Vector3d vec(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    private static Vector3d centreOf(ToadRecord r) {
        Vector3d up = new Vector3d(0, 1, 0).rotate(new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]));
        double half = ToadModels.HEIGHT_PX / 16.0 / 2 / GravityFrame.SCALE; // the model stands from its feet up
        return new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]).fma(half, up);
    }

    /** The Toad of this stage the crosshair is on (within reach), or null: its entity's box, else (not running) a sphere on its record. */
    private static ToadRecord aimed(Player player, GravityFrame frame) {
        if (store == null) return null;
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot())).normalize();
        Entity e = PlanetClient.aimedEntity(eye, look, REACH);
        if (e != null && ShadowToads.isToad(e)) return recordOf(ShadowToads.idOf(e));
        java.util.Set<String> running = ShadowToads.running();
        List<ToadRecord> here = new ArrayList<>();
        List<double[]> centers = new ArrayList<>();
        for (ToadRecord t : store.all())
            if (t.stage().equals(stageName()) && !running.contains(t.id())) {
                here.add(t);
                Vector3d c = centreOf(t);
                centers.add(new double[] {c.x, c.y, c.z});
            }
        int i = ShipBoarding.nearest(eye, look, centers, REACH / GravityFrame.SCALE, RADIUS / GravityFrame.SCALE);
        return i < 0 ? null : here.get(i);
    }

    /** Client tick: a right click on a Toad talks to it. True if the click was the Toad's. */
    /**
     * A tick: the right click's press, and the keys the host sees (the keyboard reaches the game
     * through Dolphin as well as Minecraft's window): Enter, Up, Down and 1 to 4 count with
     * Minecraft's own.
     */
    static boolean tick(LocalPlayer player, GravityFrame frame, boolean rightClick, boolean enter, boolean up, boolean down,
            boolean one, boolean two, boolean three, boolean four) {
        hostKeys[0] = enter;
        hostKeys[1] = up;
        hostKeys[2] = down;
        hostKeys[3] = one;
        hostKeys[4] = two;
        hostKeys[5] = three;
        hostKeys[6] = four;
        return tick(player, frame, rightClick);
    }

    private static final boolean[] hostKeys = new boolean[7];

    static boolean tick(LocalPlayer player, GravityFrame frame, boolean rightClick) {
        if (store == null || player == null || frame == null) {
            endConversation();
            return false;
        }
        if (conv != null && !stillTalking(player, frame)) endConversation();
        if (conv != null) {
            dialogueKeys(player);
            if (conv == null) return rightClick; // a key ended it (Bye, Trade): the click was its too
            if (rightClick) {
                ToadRecord other = aimed(player, frame);
                if (other == null || other.id().equals(with.id())) {
                    if (inputReady()) outcome(player, conv.choosing() ? conv.confirm() : advanceNow());
                    return true;
                }
            } else return false;
        }
        if (!rightClick) return false;
        ToadRecord t = aimed(player, frame);
        if (t == null) return false;
        talks++;
        talked(t);
        ToadTalk.talk(player, t);
        return true;
    }

    // ---- the dialogue box: one conversation at a time, driven by the pure ToadConversation ----

    private static ToadConversation conv;
    private static ToadRecord with;
    private static ToadContext convContext;
    private static int savedSlot, shownPage = -1, blipped;
    private static String pageText = "";
    private static long lastFrameNs;
    private static boolean[] keysWas = new boolean[7];
    /** Blocks: farther than this from the Toad and the box closes. */
    static final double WALK_AWAY = 8;

    /** The talk begun (ToadTalk). */
    static void begin(LocalPlayer player, ToadRecord toad, ToadConversation c, ToadContext ctx) {
        endConversation();
        conv = c;
        with = toad;
        stand(toad.id(), true);
        convContext = ctx;
        savedSlot = player.getInventory().getSelectedSlot();
        shownPage = -1;
        blipped = 0;
        lastFrameNs = 0;
        lastInputNs = System.nanoTime(); // the click that opened it is not the first "go on"
        java.util.Arrays.fill(keysWas, true); // a key held when it opens is not a press
        refreshPage();
    }

    /** The page's text, resolved (a page change or a new talk), so a click always has its length. */
    private static void refreshPage() {
        if (conv == null || conv.pageNumber() == shownPage) return;
        shownPage = conv.pageNumber();
        blipped = 0;
        ToadConversation.Page p = conv.page();
        pageText = Component.translatable(p.key(), p.args().toArray()).getString();
    }

    private static long lastInputNs;
    /** A "go on" or choice closer than this to the last one is ignored (a held or bouncing click). */
    private static final long INPUT_GAP_NS = 150_000_000L;

    private static boolean inputReady() {
        long now = System.nanoTime();
        if (now - lastInputNs < INPUT_GAP_NS) return false;
        lastInputNs = now;
        return true;
    }

    /** The conversation going on (tests), or null. */
    public static ToadConversation conversation() {
        return conv;
    }

    /** The Toad stops to listen (or walks again): in the host's shadow, or by asking the host. */
    private static void stand(String id, boolean on) {
        if (MpUse.guest()) MpToads.stand(id, on);
        else ShadowWorld.standToad(id, on);
    }

    /** A guest's mirror of the host's Toads: all of them (full) or what changed; never saved. */
    static void mirrorApply(boolean full, List<ToadRecord> puts, List<String> gone) {
        if (!MpUse.guest()) return;
        if (store == null || full) store = ToadStore.load(java.nio.file.Path.of("toads-mirror-never-saved.json"));
        if (full) for (ToadRecord r : store.all()) store.remove(r.id());
        for (ToadRecord r : puts) store.put(r);
        for (String id : gone) {
            store.remove(id);
            STATES.remove(id);
            if (with != null && with.id().equals(id)) endConversation();
        }
        dirty = false;
    }

    /** The Toad being talked to (tests), or null. */
    public static ToadRecord talkingTo() {
        return with;
    }

    /** Choice i of the box taken, as its number key does (tests, and a way in for other input). */
    public static void choose(LocalPlayer player, int i) {
        if (conv != null) outcome(player, conv.choose(i));
    }

    /** Ends the talk (the box goes). */
    public static void endConversation() {
        if (with != null) stand(with.id(), false);
        conv = null;
        with = null;
        ToadDialogueHud.show(null);
    }

    private static boolean stillTalking(LocalPlayer player, GravityFrame frame) {
        if (Minecraft.getInstance().gui.screen() != null) return false; // any screen (Esc's menu, a chest): the talk is over
        ToadRecord now = null;
        for (ToadRecord r : store.all()) if (r.id().equals(with.id())) now = r;
        if (now == null) return false;
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        return eye.distance(centreOf(now)) * GravityFrame.SCALE <= WALK_AWAY;
    }

    private static ToadConversation.Outcome advanceNow() {
        refreshPage();
        conv.advance(pageText.length());
        refreshPage();
        return conv.outcome();
    }

    /** Enter, Up/Down and the keys 1 to 4 (edges), and the hotbar kept where it was. */
    private static void dialogueKeys(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        boolean[] now = new boolean[7];
        now[0] = ToadKeys.ADVANCE.isDown() || hostKeys[0];
        now[1] = ToadKeys.UP.isDown() || hostKeys[1];
        now[2] = ToadKeys.DOWN.isDown() || hostKeys[2];
        for (int i = 0; i < 4; i++) now[3 + i] = mc.options.keyHotbarSlots[i].isDown() || hostKeys[3 + i];
        ToadConversation.Outcome o = ToadConversation.Outcome.NONE;
        if (now[1] && !keysWas[1]) conv.move(-1);
        if (now[2] && !keysWas[2]) conv.move(1);
        if (now[0] && !keysWas[0] && inputReady()) o = conv.choosing() ? conv.confirm() : advanceNow();
        for (int i = 0; i < 4 && o == ToadConversation.Outcome.NONE; i++)
            if (now[3 + i] && !keysWas[3 + i]) o = conv.choose(i);
        keysWas = now;
        player.getInventory().setSelectedSlot(savedSlot);
        outcome(player, o);
    }

    /** What a choice leads to: the merchant screen, the end, or more to say. */
    private static void outcome(LocalPlayer player, ToadConversation.Outcome o) {
        if (conv == null) return;
        ToadRecord toad = with;
        ToadContext ctx = convContext;
        if (o == ToadConversation.Outcome.TRADE) {
            endConversation();
            ToadTalk.trade(player, toad, ctx);
        } else if (o == ToadConversation.Outcome.CLOSED) {
            endConversation();
        } else if (conv.choosing() == false && conv.lastAsked() != null && pendingAsk != conv.lastAsked()) {
            pendingAsk = conv.lastAsked();
            conv.add(pendingAsk == ToadConversation.Choice.PLACE ? ToadScript.place(toad.type(), ctx) : ToadScript.advice(toad.type(), ctx));
            refreshPage();
        }
        if (conv != null && conv.choosing()) pendingAsk = null;
    }

    private static ToadConversation.Choice pendingAsk;

    /** Each frame: letters typed with their sound, the Toad kept talking, the box's view made ready. */
    private static void dialogueFrame(long now) {
        if (conv == null || with == null) {
            if (conv == null) ToadDialogueHud.show(null);
            return;
        }
        if (Minecraft.getInstance().gui.screen() != null) { // a screen opened between ticks
            endConversation();
            return;
        }
        double dt = lastFrameNs == 0 ? 0 : Math.min(0.1, (now - lastFrameNs) / 1e9);
        lastFrameNs = now;
        refreshPage();
        conv.tick(dt);
        int visible = conv.visibleChars(pageText.length());
        for (; blipped < visible; blipped++) ToadVoice.speak(with.type(), pageText.charAt(blipped), blipped);
        boolean typing = visible < pageText.length();
        STATES.computeIfAbsent(with.id(), k -> new State()).talkUntil = now + (typing ? TALK_NS / 2 : 0);
        List<String> choices = new ArrayList<>();
        for (ToadConversation.Choice c : conv.choices())
            choices.add(Component.translatable("toad.choice." + c.name().toLowerCase()).getString());
        ToadDialogueHud.show(new ToadDialogueHud.View(Component.translatable("toad.name." + with.type().id()).getString(),
                ToadLook.of(with.type()).cap(), pageText, visible, typing, conv.choosing(), choices, conv.highlight()));
    }

    /** Whether a right click is a Toad's, and so no item's (a block in hand must not go down). */
    public static boolean swallowsUse() {
        if (conv != null) return true; // talking: the click goes on or chooses, whatever the crosshair is on
        if (store == null || store.all().isEmpty()) return false;
        LocalPlayer me = Minecraft.getInstance().player;
        GravityFrame frame = GalaxyCraftClient.frame();
        return me != null && frame != null && Minecraft.getInstance().gui.screen() == null && aimed(me, frame) != null;
    }

    /**
     * Puts a Toad down on the ground ahead, turned to face the player (the crosshair's ground, else
     * the ground below a point ahead). Returns the lang key of the problem, or null if it is down.
     */
    public static String place(Player player, ToadType type) {
        GravityFrame frame = GalaxyCraftClient.frame();
        if (frame == null || store == null) return "message.galaxycraft.toad.no_world";
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
        if (hit == null) {
            Vector3d fup = frame.upGal().normalize();
            Vector3d flat = new Vector3d(look).fma(-look.dot(fup), fup);
            if (flat.lengthSquared() < 1e-6) return "message.galaxycraft.toad.no_ground";
            flat.normalize();
            Vector3d from = new Vector3d(eye).fma(AHEAD / GravityFrame.SCALE, flat);
            best = Double.MAX_VALUE;
            for (PlanetSession s : PlanetClient.bodies()) {
                if (!s.active()) continue;
                Vector3d[] h = s.surfaceAlong(from, new Vector3d(fup).negate(), DOWN_REACH);
                if (h != null && h[0].distance(from) < best) {
                    best = h[0].distance(from);
                    hit = h;
                }
            }
        }
        if (hit == null) return "message.galaxycraft.toad.no_ground";
        Vector3d up = hit[1];
        // Facing the player: the way back along the look, flat on the ground.
        Vector3d fwd = new Vector3d(look).negate().fma(look.dot(up), up);
        if (fwd.lengthSquared() < 1e-6) fwd = new Vector3d(up).orthogonalize(new Vector3d(up.y, up.z, up.x));
        fwd.normalize();
        Vector3d right = new Vector3d(up).cross(fwd);
        Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(right, up, fwd));
        put(type, hit[0], q);
        return null;
    }

    /** Puts a Toad down with its feet at a galaxy point, turned by q (tests; place() and the world's placing). */
    public static void put(ToadType type, Vector3d feet, Quaterniond q) {
        if (store == null || MpUse.guest()) return;
        store.put(new ToadRecord(UUID.randomUUID().toString(), type, new double[] {feet.x, feet.y, feet.z},
                new double[] {q.x, q.y, q.z, q.w}, stageName()));
        dirty = true;
        saveNow();
    }

    /** How many times a Toad has been talked to this session (tests). */
    public static int talks() {
        return talks;
    }

    /** Takes every Toad out of the world (tests). */
    public static void clearAll() {
        if (store == null) return;
        for (ToadRecord r : store.all()) {
            store.remove(r.id());
            STATES.remove(r.id());
        }
        dirty = true;
        saveNow();
    }

    // ---- drawing: each Toad posed from its animation, turned to the player ----

    /** What a Toad does between frames (never saved): the way it has turned from its spot, when it stops talking. */
    private static final class State {
        double yaw;
        long talkUntil, last;
        final double phase = Math.random();
    }

    private static final java.util.Map<String, State> STATES = new java.util.HashMap<>();
    /** Blocks within which a Toad turns to the player, radians a second it turns at, how long (ns) a talk animates. */
    private static final double LOOK_RANGE = 8, TURN = 4, WALK_RANGE = 64;
    private static final long TALK_NS = 4_000_000_000L;
    private static final double FPS = 30;
    private static final long EPOCH = System.nanoTime();
    private static final java.util.Map<Integer, ShipModels.Skin> FLAT = new java.util.HashMap<>();

    /** A Toad was talked to: it animates its Talk for a few seconds. */
    static void talked(ToadRecord r) {
        STATES.computeIfAbsent(r.id(), k -> new State()).talkUntil = System.nanoTime() + TALK_NS;
    }

    private static ShipModels.Skin flat(int rgb) {
        return FLAT.computeIfAbsent(rgb, c -> {
            int[] px = new int[16];
            java.util.Arrays.fill(px, 0xFF000000 | c);
            return new ShipModels.Skin(4, 4, px);
        });
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** The animation for what the Toad is doing: Kinopio's own, or the one with the pickel for a miner. */
    private static String animName(String walk, ToadLook look) {
        if (walk == null || walk.equals("Wait")) return look.idle();
        String pickel = look.idle().endsWith("Pickel") ? walk + "Pickel" : walk;
        return ToadModels.get().anims().containsKey(pickel) ? pickel : walk;
    }

    private static double lastSaved, clockSec;
    private static long lastPieces;

    /** Toads' clock for animations (seconds): it stands still while the game is paused. */
    static double clock() {
        return clockSec;
    }

    private static void tickClock(long now) {
        double gdt = lastPieces == 0 || Minecraft.getInstance().isPaused() ? 0 : Math.min(0.1, (now - lastPieces) / 1e9);
        lastPieces = now;
        clockSec += gdt;
    }

    /** The record of the Toad with this id, or null. */
    static ToadRecord recordOf(String id) {
        if (store == null) return null;
        for (ToadRecord r : store.all()) if (r.id().equals(id)) return r;
        return null;
    }

    /** Kinopio's animation for what a Toad entity is doing. */
    static String animFor(Entity e, boolean talking) {
        ToadRecord r = recordOf(ShadowToads.idOf(e));
        ToadLook look = ToadLook.of(r == null ? ToadType.GUIDE : r.type());
        if (talking) return look.talk();
        if (e instanceof net.minecraft.world.entity.LivingEntity l && l.isSleeping()) return animName("Sleep", look);
        double vx = e.getDeltaMovement().x, vz = e.getDeltaMovement().z;
        return animName(vx * vx + vz * vz > 0.0025 ? "Walk" : "Wait", look);
    }

    /** A save at most every few seconds. */
    private static void saveSoon() {
        double t = System.nanoTime() / 1e9;
        if (dirty && t - lastSaved > 5) {
            lastSaved = t;
            saveNow();
        }
    }

    /** Once a tick: tells the shadow which Toads of this planet to run, takes their positions back, drops the dead. */
    static void syncShadow(PlanetSession s) {
        if (store == null || MpUse.guest() || !s.active() || s.planet() == null) return;
        List<ShadowToads.Wanted> want = new ArrayList<>();
        for (ToadRecord r : store.all()) {
            // The Technician stays where it was put: a station is a flat slab with edges to fall off.
            if (!r.stage().equals(stageName()) || r.type() == ToadType.TECHNICIAN) continue;
            Vector3d gal = new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]);
            if (s.cellAt(gal) < 0) continue;
            Vector3d local = s.localOf(gal);
            Vector3d f = new Vector3d(0, 0, 1).rotate(new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]));
            want.add(new ShadowToads.Wanted(r.id(), new double[] {local.x, local.y, local.z}, (float) Math.toDegrees(Math.atan2(-f.x, f.z))));
        }
        ShadowWorld.wantToads(s.planet(), want);
        for (String dead; (dead = ShadowToads.pollDeath()) != null; ) {
            store.remove(dead);
            STATES.remove(dead);
            if (with != null && with.id().equals(dead)) endConversation();
            dirty = true;
        }
        for (var en : ShadowToads.positions().entrySet()) {
            ToadRecord r = recordOf(en.getKey());
            if (r == null) continue;
            Vector3d g = s.galOf(new Vector3d(en.getValue()[0], en.getValue()[1], en.getValue()[2]));
            r.pos()[0] = g.x;
            r.pos()[1] = g.y;
            r.pos()[2] = g.z;
            dirty = true;
        }
        saveSoon();
    }

    /** The pieces of this stage's Toads that no entity draws (their planet is not running), universe units. */
    static void pieces(EntityClient ec, List<EntityWire.Piece> out) {
        drainOutposts();
        dialogueFrame(System.nanoTime());
        if (store == null) return;
        long now = System.nanoTime();
        tickClock(now);
        LocalPlayer me = Minecraft.getInstance().player;
        GravityFrame fr = GalaxyCraftClient.frame();
        homeGuide(me, fr);
        Vector3d eye = me != null && fr != null ? fr.toGal(vec(me.getEyePosition())) : null;
        double unitsPerPixel = 1 / (GravityFrame.SCALE * 16);
        java.util.Set<String> running = ShadowToads.running();
        java.util.Set<String> heard = MpMobs.toadIds(); // a guest: the Toads the host's planet runs, drawn from its shadow
        for (ToadRecord r : store.all()) {
            if (!r.stage().equals(stageName()) || running.contains(r.id()) || heard.contains(r.id())) continue;
            State st = STATES.computeIfAbsent(r.id(), k -> new State());
            boolean talking = now < st.talkUntil;
            Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
            Vector3d pos = new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]);
            // Turn to the player when near (about its up, from the way it was put down).
            double target = 0;
            if (eye != null) {
                Vector3d to = new Vector3d(eye).sub(pos);
                double x = to.dot(new Vector3d(1, 0, 0).rotate(q)), z = to.dot(new Vector3d(0, 0, 1).rotate(q));
                if (to.length() * GravityFrame.SCALE < LOOK_RANGE && x * x + z * z > 1e-12) target = Math.atan2(x, z);
            }
            double dt = Math.min(0.1, (now - (st.last == 0 ? now : st.last)) / 1e9);
            st.last = now;
            double diff = wrap(target - st.yaw), step = TURN * dt;
            st.yaw = wrap(st.yaw + Math.max(-step, Math.min(step, diff)));
            Matrix4d base = new Matrix4d().translation(pos.x, pos.y, pos.z).rotate(q).rotateY(st.yaw).scale(unitsPerPixel);
            ToadLook look = ToadLook.of(r.type());
            draw(ec, r, base, ShipClient::rows, talking ? look.talk() : look.idle(), out);
        }
    }

    /**
     * One Toad's pieces: Kinopio posed by the named animation, its cap and vest coloured, what it
     * carries on its joint. base maps model pixels to wherever rows turns a matrix into the wire's.
     */
    static void draw(EntityClient ec, ToadRecord r, Matrix4d base, java.util.function.Function<Matrix4d, double[]> rows, String animName,
            List<EntityWire.Piece> out) {
        draw(ec, r, base, rows, animName, 0, out);
    }

    /** As above, overlay the game's tint over every piece (EntityClient.HURT: red, as a hurt mob). */
    static void draw(EntityClient ec, ToadRecord r, Matrix4d base, java.util.function.Function<Matrix4d, double[]> rows, String animName,
            int overlay, List<EntityWire.Piece> out) {
        ToadModels.Loaded model = ToadModels.get();
        ToadLook look = ToadLook.of(r.type());
        State st = STATES.computeIfAbsent(r.id(), k -> new State());
        BckReader.Anim anim = model.anims().get(animName);
        if (anim == null) anim = model.anims().get("Wait");
        double frame = clockSec * FPS + (anim == null ? 0 : st.phase * anim.duration());
        float[][] srt = new float[model.rest().length][];
        float[] sample = new float[9];
        for (int j = 0; j < srt.length; j++) {
            if (anim != null && anim.joints() == srt.length) {
                anim.sample(j, (float) frame, sample);
                srt[j] = sample.clone();
            } else srt[j] = model.rest()[j];
        }
        org.joml.Matrix4f[] world = ToadPose.worlds(model.parent(), srt);
        for (ToadModels.RigPart rp : model.parts()) {
            ShipModels.PartModel part = rp.part();
            ShipModels.Skin skin = part.skin();
            int tint = part.tint();
            if (rp.texture() == ToadModels.CAP_TEXTURE && rp.joint() == ToadModels.HEAD_JOINT) tint = look.cap();
            for (int vt : ToadModels.VEST_TEXTURES) if (rp.texture() == vt) skin = flat(look.vest());
            int mdl = ec.shipModel(part, part.quads());
            int skn = ec.shipSkin(skin == part.skin() ? skin : "toad-flat-" + look.vest(), skin.width(), skin.height(), skin.argb());
            if (mdl < 0 || skn < 0) continue;
            Matrix4d m = new Matrix4d(base).mul(new Matrix4d(ToadModels.pieceMatrix(model, world[rp.joint()])));
            out.add(new EntityWire.Piece(mdl, skn, overlay, tint, rows.apply(m)));
        }
        if (look.joint() < world.length)
            for (ShipModels.PartModel g : ToadModels.goods(look.goods())) {
                int mdl = ec.shipModel(g, g.quads());
                int skn = ec.shipSkin(g.skin(), g.skin().width(), g.skin().height(), g.skin().argb());
                if (mdl < 0 || skn < 0) continue;
                Matrix4d m = new Matrix4d(base).mul(new Matrix4d(ToadModels.pieceMatrix(model, world[look.joint()])));
                out.add(new EntityWire.Piece(mdl, skn, overlay, g.tint(), rows.apply(m)));
            }
    }
}
