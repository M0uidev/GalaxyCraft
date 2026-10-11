package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.play.PlayConfig;
import dev.moui.galaxycraft.ship.SeatLayout;
import dev.moui.galaxycraft.ship.ShipBlocks;
import dev.moui.galaxycraft.ship.ShipBoarding;
import dev.moui.galaxycraft.ship.ShipHooks;
import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.ShipRecord;
import dev.moui.galaxycraft.ship.ShipStats;
import dev.moui.galaxycraft.ship.ShipStore;
import dev.moui.galaxycraft.ship.ShipThrust;
import dev.moui.galaxycraft.ship.model.ShipModels;
import dev.moui.galaxycraft.view.EntityWire;
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
    private static final double SEAT_HEIGHT = 1;
    private static final int SAVE_TICKS = 100;
    /** Where a player who leaves stands: this far out to the side and this far below the ship's middle, blocks. */
    private static final double EXIT_SIDE = 3.8, EXIT_BELOW = 1.6;

    public static final ShipHooks HOOKS = ShipClient::place;

    private static ShipStore store;
    private static Path file;
    private static boolean dirty;
    private static int sinceSave;
    private static String boarded;
    private static boolean sneakWas;

    private ShipClient() {}

    /** A world's planets folder: its ships live in its ships.json (null: no world). */
    public static void enterWorld(Path planetsDir) {
        saveNow();
        leaveNow(null);
        file = planetsDir == null ? null : planetsDir.resolve("ships.json");
        store = file == null ? null : ShipStore.load(file);
        dirty = false;
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

    /** A Toad Ship item used: it goes down ahead of the player, true if it did. */
    static boolean place(Player player, InteractionHand hand, ShipModules modules) {
        if (store == null) return false;
        StationClient.Spot at = StationClient.spot(player);
        if (at == null) return false;
        Vector3d up = new Vector3d(0, 1, 0).rotate(at.rotation());
        // The spot is a station's: this far ahead and 2 blocks below; a ship floats 1 below the feet.
        Vector3d center = new Vector3d(at.center()).fma((2 - BELOW) / GravityFrame.SCALE, up);
        Quaterniond q = at.rotation();
        store.put(new ShipRecord(UUID.randomUUID().toString(), modules, new double[] {center.x, center.y, center.z},
                new double[] {q.x, q.y, q.z, q.w}, stageName()));
        dirty = true;
        StationClient.useUp(player, hand, ShipBlocks.TOAD_SHIP);
        GalaxyCraft.LOG.info("A ship {} is down at {}", modules, center);
        return true;
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
        syncHulls();
        if (boarded != null) {
            Optional<ShipRecord> r = aboard();
            if (r.isEmpty() || sneakEdge) {
                leaveNow(player);
                return false;
            }
            fly(player, frame, r.get(), c);
            return false;
        }
        if (!rightClick) return false;
        Vector3d eye = frame.toGal(vec(player.getEyePosition()));
        Vector3d look = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        List<ShipRecord> here = new ArrayList<>();
        List<double[]> centers = new ArrayList<>();
        for (ShipRecord s : store.all())
            if (s.stage().equals(stageName())) {
                here.add(s);
                centers.add(s.pos());
            }
        int i = ShipBoarding.nearest(eye, look.normalize(), centers, REACH / GravityFrame.SCALE, RADIUS / GravityFrame.SCALE);
        if (i < 0) return false;
        board(player, here.get(i));
        return true;
    }

    private static void board(LocalPlayer player, ShipRecord r) {
        boarded = r.id();
        velocity = new Vector3d();
        ShipStats stats = ShipStats.of(r.modules());
        GalaxyCraft.LOG.info("Aboard ship {} ({} seats, speed {})", r.id(), stats.seats(), stats.speed());
        syncHulls();
    }

    /**
     * Aboard: the ship hovers where the keys take it (ShipThrust), by Minecraft's own movement so the
     * ground and walls stop it; no gravity; the ship at the player's feet, turned its way.
     */
    private static void fly(LocalPlayer player, GravityFrame frame, ShipRecord r, ShipThrust.Controls c) {
        var abilities = player.getAbilities();
        abilities.mayfly = true; // the server's "floating" check: it is not a hack
        abilities.flying = false;
        player.setNoGravity(true);
        player.resetFallDistance();
        if (player.isFallFlying()) player.stopFallFlying();
        velocity = ShipThrust.step(velocity, vec(player.getLookAngle()), c, ShipStats.of(r.modules()));
        // The move itself waits for the player's own tick (onPlayerTick), after Minecraft has
        // saved the old position: moved before that, the view would not be drawn between the two
        // and the flight would step at 20 Hz.
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
        if (velocity.lengthSquared() > 1e-10)
            player.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(velocity.x, velocity.y, velocity.z));
        player.setDeltaMovement(Vec3.ZERO);
        // Held against a wall or the ground it does not build up speed it cannot use.
        if (player.horizontalCollision) velocity.x = velocity.z = 0;
        if (player.verticalCollision) velocity.y = 0;
        store.put(placed(r.get(), frame, player, 1f));
        dirty = true;
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
        Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(right, up, flat));
        Vector3d pos = feet.fma(-SEAT_HEIGHT / GravityFrame.SCALE, up);
        return new ShipRecord(r.id(), r.modules(), new double[] {pos.x, pos.y, pos.z}, new double[] {q.x, q.y, q.z, q.w}, r.stage());
    }

    private static void leaveNow(LocalPlayer player) {
        if (boarded == null) return;
        GalaxyCraft.LOG.info("Left ship {}", boarded);
        Optional<ShipRecord> was = aboard();
        boarded = null;
        velocity = new Vector3d();
        if (player != null && was.isPresent()) stepOut(player, was.get());
        if (player != null) {
            player.setNoGravity(false);
            player.getAbilities().mayfly = player.getAbilities().instabuild;
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
            if (!r.stage().equals(stage) || r.id().equals(boarded)) continue;
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
        for (float[] f : ShipModels.hullTriangles(Math.max(1, r.modules().hull()))) {
            Vector3d a = m.transformPosition(new Vector3d(f[0], f[1], f[2]));
            Vector3d b = m.transformPosition(new Vector3d(f[3], f[4], f[5]));
            Vector3d c = m.transformPosition(new Vector3d(f[6], f[7], f[8]));
            out.add(dev.moui.galaxycraft.geom.Tri.of(a, b, c));
        }
        return out;
    }

    // ---- drawing ----

    /** The pieces of this stage's ships (and their cushions), universe units, for EntityClient's frame. */
    static void pieces(EntityClient ec, float pt, List<EntityWire.Piece> out) {
        if (store == null) return;
        String stage = stageName();
        double unitsPerPixel = 1 / (GravityFrame.SCALE * 16);
        for (ShipRecord r : store.all()) {
            if (!r.stage().equals(stage)) continue;
            // The aboard ship follows the player as drawn between ticks (not as the tick left it),
            // so it moves as smoothly as the view does.
            LocalPlayer me = Minecraft.getInstance().player;
            GravityFrame fr = GalaxyCraftClient.frame();
            if (r.id().equals(boarded) && me != null && fr != null) r = placed(r, fr, me, pt);
            Matrix4d base = new Matrix4d().translation(r.pos()[0], r.pos()[1], r.pos()[2])
                    .rotate(new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]));
            Matrix4d px = new Matrix4d(base).scale(unitsPerPixel);
            for (ShipModels.Placed pl : ShipModels.layout(r.modules())) {
                ShipModels.PartModel part = pl.part();
                int model = ec.shipModel(part, part.quads());
                int skin = ec.shipSkin(part.skin(), part.skin().width(), part.skin().height(), part.skin().argb());
                if (model < 0 || skin < 0) continue;
                Matrix4d m = new Matrix4d(px).translate(pl.offset()[0], pl.offset()[1], pl.offset()[2]);
                out.add(new EntityWire.Piece(model, skin, 0, part.tint(), rows(m)));
            }
            // The portholes: Minecraft's glass.
            for (ShipModels.PartModel g : ShipModels.glass(r.modules().hull())) {
                int model = ec.shipModel(g, g.quads());
                int skin = ec.glassSkin();
                if (model >= 0 && skin >= 0) out.add(new EntityWire.Piece(model, skin, 0, 0xFFFFFFFF, rows(px)));
            }
            // The cabin's cushions: Minecraft's own, one per seat, the driver's under the player.
            Matrix4d blocks = new Matrix4d(base).scale(1 / GravityFrame.SCALE);
            for (float[] s : SeatLayout.seats(ShipStats.of(r.modules()).seats()))
                ec.shipCushion(new Matrix4d(blocks).translate(s[0] / 16.0, s[1] / 16.0 + 0.1, s[2] / 16.0), pt, out);
        }
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
