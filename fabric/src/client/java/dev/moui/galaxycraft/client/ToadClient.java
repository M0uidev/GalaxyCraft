package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.ship.ShipBoarding;
import dev.moui.galaxycraft.ship.model.ShipModels;
import dev.moui.galaxycraft.toad.ToadModels;
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
        double half = ToadModels.HEIGHT_PX / 16.0 / 2 / GravityFrame.SCALE;
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
        store.put(new ToadRecord(UUID.randomUUID().toString(), type, new double[] {hit[0].x, hit[0].y, hit[0].z},
                new double[] {q.x, q.y, q.z, q.w}, stageName()));
        dirty = true;
        saveNow();
        return null;
    }

    /** The pieces of this stage's Toads, universe units, for EntityClient's frame. */
    static void pieces(EntityClient ec, List<EntityWire.Piece> out) {
        if (store == null) return;
        String stage = stageName();
        double unitsPerPixel = 1 / (GravityFrame.SCALE * 16);
        for (ToadRecord r : store.all()) {
            if (!r.stage().equals(stage)) continue;
            Matrix4d m = new Matrix4d().translation(r.pos()[0], r.pos()[1], r.pos()[2])
                    .rotate(new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]))
                    .scale(unitsPerPixel).translate(0, ToadModels.HEIGHT_PX / 2, 0);
            for (ShipModels.PartModel part : ToadModels.parts()) {
                int model = ec.shipModel(part, part.quads());
                int skin = ec.shipSkin(part.skin(), part.skin().width(), part.skin().height(), part.skin().argb());
                if (model < 0 || skin < 0) continue;
                out.add(new EntityWire.Piece(model, skin, 0, part.tint(), ShipClient.rows(m)));
            }
        }
    }
}
