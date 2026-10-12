package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.ship.ShipBoarding;
import dev.moui.galaxycraft.ship.model.BckReader;
import dev.moui.galaxycraft.ship.model.ShipModels;
import dev.moui.galaxycraft.toad.ToadLook;
import dev.moui.galaxycraft.toad.ToadModels;
import dev.moui.galaxycraft.toad.ToadPose;
import dev.moui.galaxycraft.toad.ToadRecord;
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

    private ToadClient() {}

    /** A world's planets folder: its Toads live in its toads.json (null: no world). */
    public static void enterWorld(Path planetsDir) {
        saveNow();
        file = planetsDir == null ? null : planetsDir.resolve("toads.json");
        store = file == null ? null : ToadStore.load(file);
        dirty = false;
        ToadModels.load(ShipClient.shipDir());
    }

    /** The Toads (tests). */
    public static List<ToadRecord> toads() {
        return store == null ? List.of() : store.all();
    }

    private static void saveNow() {
        if (store == null || !dirty) return;
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

    /** The Toad of this stage the crosshair is on (within reach), or null. */
    private static ToadRecord aimed(Player player, GravityFrame frame) {
        if (store == null) return null;
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        List<ToadRecord> here = new ArrayList<>();
        List<double[]> centers = new ArrayList<>();
        for (ToadRecord t : store.all())
            if (t.stage().equals(stageName())) {
                here.add(t);
                Vector3d c = centreOf(t);
                centers.add(new double[] {c.x, c.y, c.z});
            }
        int i = ShipBoarding.nearest(eye, look.normalize(), centers, REACH / GravityFrame.SCALE, RADIUS / GravityFrame.SCALE);
        return i < 0 ? null : here.get(i);
    }

    /** Client tick: a right click on a Toad talks to it. True if the click was the Toad's. */
    static boolean tick(LocalPlayer player, GravityFrame frame, boolean rightClick) {
        if (!rightClick || store == null || player == null || frame == null) return false;
        ToadRecord t = aimed(player, frame);
        if (t == null) return false;
        talks++;
        talked(t);
        ToadTalk.talk(player, t);
        return true;
    }

    /** Whether a right click is a Toad's, and so no item's (a block in hand must not go down). */
    public static boolean swallowsUse() {
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
        if (store == null) return;
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
        for (ToadRecord r : store.all()) store.remove(r.id());
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
    private static final double LOOK_RANGE = 8, TURN = 4;
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

    /** The pieces of this stage's Toads, universe units, for EntityClient's frame. */
    static void pieces(EntityClient ec, List<EntityWire.Piece> out) {
        if (store == null) return;
        String stage = stageName();
        double unitsPerPixel = 1 / (GravityFrame.SCALE * 16);
        long now = System.nanoTime();
        LocalPlayer me = Minecraft.getInstance().player;
        GravityFrame fr = GalaxyCraftClient.frame();
        Vector3d eye = me != null && fr != null ? fr.toGal(vec(me.getEyePosition())) : null;
        ToadModels.Loaded model = ToadModels.get();
        float[][] srt = new float[model.rest().length][];
        float[] sample = new float[9];
        for (ToadRecord r : store.all()) {
            if (!r.stage().equals(stage)) continue;
            ToadLook look = ToadLook.of(r.type());
            State st = STATES.computeIfAbsent(r.id(), k -> new State());
            double dt = st.last == 0 ? 0 : Math.min(0.1, (now - st.last) / 1e9);
            st.last = now;
            Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
            Vector3d pos = new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]);
            // Turn to the player when near (about its up, from the way it was put down), else back.
            double target = 0;
            if (eye != null) {
                Vector3d to = new Vector3d(eye).sub(pos);
                double x = to.dot(new Vector3d(1, 0, 0).rotate(q)), z = to.dot(new Vector3d(0, 0, 1).rotate(q));
                if (to.length() * GravityFrame.SCALE < LOOK_RANGE && x * x + z * z > 1e-12) target = Math.atan2(x, z);
            }
            double diff = wrap(target - st.yaw), step = TURN * dt;
            st.yaw = wrap(st.yaw + Math.max(-step, Math.min(step, diff)));
            Matrix4d base = new Matrix4d().translation(pos.x, pos.y, pos.z).rotate(q).rotateY(st.yaw).scale(unitsPerPixel);
            // The pose: the animation's frame on every joint it has, the rest pose for the others.
            boolean talking = now < st.talkUntil;
            BckReader.Anim anim = model.anims().get(talking ? look.talk() : look.idle());
            if (anim == null) anim = model.anims().get(talking ? "Talk" : "Wait");
            double frame = (now - EPOCH) / 1e9 * FPS + (anim == null ? 0 : st.phase * anim.duration());
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
                out.add(new EntityWire.Piece(mdl, skn, 0, tint, ShipClient.rows(m)));
            }
            // What it carries, on its joint.
            if (look.joint() < world.length)
                for (ShipModels.PartModel g : ToadModels.goods(look.goods())) {
                    int mdl = ec.shipModel(g, g.quads());
                    int skn = ec.shipSkin(g.skin(), g.skin().width(), g.skin().height(), g.skin().argb());
                    if (mdl < 0 || skn < 0) continue;
                    Matrix4d m = new Matrix4d(base).mul(new Matrix4d(ToadModels.pieceMatrix(model, world[look.joint()])));
                    out.add(new EntityWire.Piece(mdl, skn, 0, g.tint(), ShipClient.rows(m)));
                }
        }
    }
}
