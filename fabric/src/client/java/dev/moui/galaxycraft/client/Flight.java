package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.CosmicWind;
import dev.moui.galaxycraft.universe.OriginPolicy;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;
import dev.moui.galaxycraft.gravity.GravityBody;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.voxel.PlanetLayout;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Elytra between planets. While the elytra are open, and until the player stands on a planet
 * again, the player moves by Minecraft's own physics in every movement mode (vanilla gliding,
 * rockets, durability) and Mario is seated at its feet. Out of every gravity (the void) there is
 * no gravity and up stays as it was; far out, the cosmic wind brings the player back.
 */
public final class Flight {
    /** Standing on the ground this many ticks after a flight hands the player back to its mode. */
    private static final int LAND_TICKS = 10;
    /** Mario is on the ground (no take-off yet) if there is ground this far under his feet, blocks. */
    private static final double MARIO_GROUND = 0.6;
    /**
     * Space: past every planet's gravity with no ground of the stage this far below (blocks).
     * Some stages pull everywhere (the prologue); over their ground that pull stays, out in space
     * it is ignored, as in Mario Galaxy.
     */
    private static final double STAGE_GROUND = 64;

    private static boolean active;
    private static int landed;
    private static boolean inVoid;
    /** Came in from space and has not touched ground yet: no fall damage for it (as in Mario Galaxy). */
    private static boolean fromSpace;
    /** Mario's position last tick (galaxy units): his speed becomes the glide's at take-off. */
    private static Vector3d lastMario;
    private static Vector3d marioStep = new Vector3d();

    private Flight() {}

    /** The player moves by its own physics for a flight: from take-off until it has landed. */
    static boolean active() {
        return active;
    }

    /** Out of every gravity right now. */
    static boolean inVoid() {
        return inVoid;
    }

    /** Space where the player is: no gravity, or past every planet's with no stage ground below. */
    static boolean space(LocalPlayer player, Seqlock.WorldState world, GravityFrame frame) {
        if (!world.hasGravity()) return true;
        Vector3d pos = frame.toGal(vec(player.position())).mul(GravityFrame.SCALE);
        for (GravityBody b : bodies()) if (b.outside(pos) <= 0) return false;
        Vec3 feet = player.position();
        return !GalaxyCraft.FIELD.hasGroundBelow(new double[] {feet.x, feet.y, feet.z}, STAGE_GROUND);
    }

    /**
     * Start of the tick, before the frame turns. following: Mario's mode carries the player (it
     * has not taken off). Returns true when a flight started this tick.
     */
    static boolean beforeFrame(LocalPlayer player, Seqlock.WorldState world, GravityFrame frame, boolean following) {
        Vector3d mario = new Vector3d(world.queryPos());
        if (lastMario != null) marioStep = mario.sub(lastMario, new Vector3d());
        lastMario = mario;
        if (GalaxyCraftClient.flying()) {
            if (player.isFallFlying()) player.stopFallFlying();
            active = false;
            return false;
        }
        boolean started = false;
        if (player.isFallFlying()) {
            started = !active;
            active = true;
            landed = 0;
        } else if (active) {
            boolean down = player.onGround() && world.hasGravity();
            landed = down ? landed + 1 : 0;
            if (landed >= LAND_TICKS) active = false;
        }
        if (started && following) {
            // Carried by Mario until now: the glide goes on with his speed.
            Vector3d v = frame.dirToMc(marioStep).mul(GravityFrame.SCALE);
            player.setDeltaMovement(v.x, v.y, v.z);
        }
        if (!active && following) {
            // Vanilla opens the elytra on a jump press off the ground; the carried player never
            // touches Minecraft's ground, so say where Mario stands (his own jump stays his).
            Vec3 feet = player.position();
            player.setOnGround(GalaxyCraft.FIELD.hasGroundBelow(new double[] {feet.x, feet.y, feet.z}, MARIO_GROUND));
        }
        return started;
    }

    /** A flight's turn of up toward the pull: gentle in, gentle out. */
    private static final dev.moui.galaxycraft.gravity.UpTurn turn = new dev.moui.galaxycraft.gravity.UpTurn();

    /** Up for a flight in a field: toward the gravity, eased (UpTurn). */
    static Vector3d upToward(GravityFrame frame, Vector3d gravity) {
        return turn.step(frame.upGal(), new Vector3d(gravity).normalize().negate());
    }

    /**
     * End of the tick, for a player moving by its own physics: no gravity in the void, and the
     * wind far out. Returns true in the void (nothing to settle on there).
     */
    static boolean afterFrame(LocalPlayer player, Seqlock.WorldState world, GravityFrame frame) {
        boolean voidNow = space(player, world, frame);
        if (voidNow) {
            player.setNoGravity(true);
            player.resetFallDistance();
        } else if (inVoid) {
            player.setNoGravity(false);
        }
        inVoid = voidNow;
        if (voidNow || player.onGround()) turn.reset(); // the next turn into a pull starts gently
        if (voidNow) fromSpace = true;
        else if (player.onGround() || player.isFallFlying()) fromSpace = false;
        if (fromSpace) player.resetFallDistance();
        Vector3d posBlocks = frame.toGal(vec(player.position())).mul(GravityFrame.SCALE);
        Vector3d velGal = frame.dirToGal(vec(player.getDeltaMovement()));
        boolean stranded = voidNow && !player.isFallFlying();
        pulse(player, frame, voidNow, posBlocks);
        Vector3d dv = pulse > 0 ? new Vector3d() // no wind while pulsing
                : CosmicWind.push(stranded, posBlocks, velGal, windBodies(posBlocks, stranded));
        if (dv.lengthSquared() > 0) {
            Vector3d mc = frame.dirToMc(dv);
            player.setDeltaMovement(player.getDeltaMovement().add(mc.x, mc.y, mc.z));
        }
        return voidNow;
    }

    /**
     * The pulse (No Man's Sky's): gliding out in the void with sprint held, the player speeds up to
     * PULSE_MAX blocks a tick where it looks, and slows down again near any planet's gravity (it never
     * enters one at that speed) or when sprint is let go. It slides the gravity frame: the player stays
     * where it is in Minecraft (its server would pull back such a speed), only its place in the galaxy
     * moves. Only with the endless universe.
     */
    private static void pulse(LocalPlayer player, GravityFrame frame, boolean voidNow, Vector3d posBlocks) {
        boolean want = voidNow && player.isFallFlying() && PlanetClient.ENDLESS && UniverseClient.universe() != null
                && net.minecraft.client.Minecraft.getInstance().options.keySprint.isDown();
        // Room ahead: the nearest gravity's edge, a tenth of it a tick at most (it eases in).
        double room = Double.MAX_VALUE;
        Vector3d posUnits = new Vector3d(posBlocks).div(GravityFrame.SCALE);
        for (PlanetLayout.Sphere s : PlanetClient.streamedSpheres())
            room = Math.min(room, (s.center().distance(posUnits) - s.gravity()) * GravityFrame.SCALE);
        double cap = Math.max(0, Math.min(PULSE_MAX, (room - PULSE_MARGIN) / 10));
        pulse = want ? Math.min(pulse + PULSE_ACCEL, cap) : Math.min(pulse * 0.85, cap);
        if (pulse < 0.05) pulse = 0;
        if (pulse == 0) return;
        Vector3d look = frame.dirToGal(dev.moui.galaxycraft.gravity.LookMath.direction(player.getYRot(), player.getXRot()));
        frame.slide(look.normalize().mul(pulse / GravityFrame.SCALE));
    }

    /** Pulse: blocks a tick at most (400 blocks/s), gained per tick (top speed in 3 s), kept off gravity by. */
    static final double PULSE_MAX = 20, PULSE_ACCEL = 20 / 60.0, PULSE_MARGIN = 64;
    private static double pulse;

    /** The pulse's speed now, blocks a tick (0: none). */
    public static double pulseSpeed() {
        return pulse;
    }

    /** Leaving the galaxy (unlinked, world closed): nothing carries over. */
    static void reset() {
        pulse = 0;
        active = false;
        inVoid = false;
        fromSpace = false;
        landed = 0;
        lastMario = null;
        turn.reset();
    }

    /**
     * What the wind pulls toward: the planets, inside a system. Out between systems (endless
     * universe) a glide is free, and a stranded player drifts to the nearest system's planets
     * (its whole reach as one body).
     */
    static List<GravityBody> windBodies(Vector3d posBlocks, boolean stranded) {
        Universe u = UniverseClient.universe();
        if (u == null || !PlanetClient.ENDLESS) return bodies();
        UPos at = UPos.of(new Vector3d(posBlocks).div(GravityFrame.SCALE));
        if (u.systemAt(at, OriginPolicy.SYSTEM_MARGIN).isPresent()) return bodies();
        if (!stranded) return List.of();
        return u.around(at, 1).stream().findFirst()
                .<List<GravityBody>>map(s -> List.of(new GravityBody.Sphere(s.center().minus(UPos.ZERO).mul(GravityFrame.SCALE), u.reach(s))))
                .orElse(List.of());
    }

    /** Every planet's gravity, blocks. */
    static List<GravityBody> bodies() {
        List<GravityBody> out = new ArrayList<>();
        for (PlanetSession s : PlanetClient.planets()) {
            if (s == null || !s.active() || s.planet() == null) continue;
            out.add(new GravityBody.Sphere(new Vector3d(s.center()).mul(GravityFrame.SCALE),
                    s.gravityUnits() * GravityFrame.SCALE));
        }
        return out;
    }

    private static Vector3d vec(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
