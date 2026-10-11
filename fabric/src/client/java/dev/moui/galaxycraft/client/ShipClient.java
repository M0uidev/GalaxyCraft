package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.play.PlayConfig;
import dev.moui.galaxycraft.ship.SeatLayout;
import dev.moui.galaxycraft.ship.ShipBlocks;
import dev.moui.galaxycraft.ship.ShipBoarding;
import dev.moui.galaxycraft.ship.ShipFlightState;
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
 * left with Shift. Aboard, the player glides as with elytra (PlayerGlideMixin) and the ship's
 * stats drive it (ShipThrust); the ship goes where the player is. Drawn by EntityClient.
 */
public final class ShipClient {
    /** A ship is clicked within this many blocks of the eye, by a sphere of this radius. */
    static final double REACH = 8, RADIUS = 2.5;
    /** A ship is put down this far ahead of the player and this far below its feet, blocks. */
    private static final double AHEAD = 6, BELOW = 1;
    /** Where on the ship the aboard player's feet are, blocks above its middle (the cabin). */
    private static final double SEAT_HEIGHT = 1;
    private static final int SAVE_TICKS = 100;
    private static final double LIFT = 0.45;

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

    /**
     * Client tick: boarding and leaving, and the aboard player's flight. rightClick: the button went
     * down this tick; sneak and forward: Shift and W held. Returns true if the click was the ship's.
     */
    static boolean tick(LocalPlayer player, GravityFrame frame, boolean rightClick, boolean sneak, boolean forward) {
        boolean sneakEdge = sneak && !sneakWas;
        sneakWas = sneak;
        if (store == null || player == null || frame == null) return false;
        if (++sinceSave >= SAVE_TICKS) {
            sinceSave = 0;
            if (dirty) saveNow();
        }
        if (boarded != null) {
            Optional<ShipRecord> r = aboard();
            if (r.isEmpty()) {
                leaveNow(player);
                return false;
            }
            if (sneakEdge) {
                leaveNow(player);
                return false;
            }
            fly(player, frame, r.get(), forward);
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
        ShipStats stats = ShipStats.of(r.modules());
        ShipFlightState.set(stats);
        // Off the ground the glide opens (as vanilla's jump press in the air does): a small lift.
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x, Math.max(v.y, LIFT), v.z);
        player.setOnGround(false);
        GalaxyCraft.LOG.info("Aboard ship {} ({} seats, speed {})", r.id(), stats.seats(), stats.speed());
    }

    /** Aboard: the glide opened, thrust and turn from the stats, the ship at the player's feet and turned its way. */
    private static void fly(LocalPlayer player, GravityFrame frame, ShipRecord r, boolean forward) {
        if (!player.isFallFlying() && !player.onGround() && player.tryToStartFallFlying()) {
            var conn = Minecraft.getInstance().getConnection();
            if (conn != null) conn.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player,
                    net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
        ShipStats stats = ShipStats.of(r.modules());
        if (player.isFallFlying()) {
            Vec3 look = player.getLookAngle();
            Vector3d v = ShipThrust.step(vec(player.getDeltaMovement()), vec(look), forward, stats);
            player.setDeltaMovement(v.x, v.y, v.z);
        }
        // The ship goes where the player is, nose where it looks, up the galaxy's up.
        Vector3d feet = frame.toGal(vec(player.position()));
        Vector3d up = frame.upGal().normalize();
        Vector3d fwd = frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        Vector3d flat = new Vector3d(fwd).fma(-fwd.dot(up), up);
        if (flat.lengthSquared() < 1e-6) flat = new Vector3d(up).orthogonalize(new Vector3d(up.y, up.z, up.x));
        flat.normalize();
        Vector3d right = new Vector3d(up).cross(flat);
        Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(right, up, flat));
        Vector3d pos = feet.fma(-SEAT_HEIGHT / GravityFrame.SCALE, up);
        store.put(new ShipRecord(r.id(), r.modules(), new double[] {pos.x, pos.y, pos.z}, new double[] {q.x, q.y, q.z, q.w}, r.stage()));
        dirty = true;
    }

    private static void leaveNow(LocalPlayer player) {
        if (boarded == null) return;
        GalaxyCraft.LOG.info("Left ship {}", boarded);
        boarded = null;
        ShipFlightState.set(null);
        if (player != null && player.isFallFlying()) player.stopFallFlying();
        saveNow();
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

    // ---- drawing ----

    /** The pieces of this stage's ships (and their cushions), universe units, for EntityClient's frame. */
    static void pieces(EntityClient ec, float pt, List<EntityWire.Piece> out) {
        if (store == null) return;
        String stage = stageName();
        double unitsPerPixel = 1 / (GravityFrame.SCALE * 16);
        for (ShipRecord r : store.all()) {
            if (!r.stage().equals(stage)) continue;
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
