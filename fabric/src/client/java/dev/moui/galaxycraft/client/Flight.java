package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.CosmicWind;
import dev.moui.galaxycraft.gravity.GravityBody;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.proto.Seqlock;
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
final class Flight {
    /** Standing on the ground this many ticks after a flight hands the player back to its mode. */
    private static final int LAND_TICKS = 10;
    /** Mario is on the ground (no take-off yet) if there is ground this far under his feet, blocks. */
    private static final double MARIO_GROUND = 0.6;

    private static boolean active;
    private static int landed;
    private static boolean inVoid;
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

    /** Up for a flight in a field: toward the gravity, at a flight's pace. */
    static Vector3d upToward(GravityFrame frame, Vector3d gravity) {
        return GravityFrame.limitTurn(frame.upGal(), new Vector3d(gravity).normalize().negate(),
                GravityFrame.FLIGHT_TURN_PER_TICK);
    }

    /**
     * End of the tick, for a player moving by its own physics: no gravity in the void, and the
     * wind far out. Returns true in the void (nothing to settle on there).
     */
    static boolean afterFrame(LocalPlayer player, Seqlock.WorldState world, GravityFrame frame) {
        boolean voidNow = !world.hasGravity();
        if (voidNow) {
            player.setNoGravity(true);
            player.resetFallDistance();
        } else if (inVoid) {
            player.setNoGravity(false);
        }
        inVoid = voidNow;
        Vector3d posBlocks = frame.toGal(vec(player.position())).mul(GravityFrame.SCALE);
        Vector3d velGal = frame.dirToGal(vec(player.getDeltaMovement()));
        Vector3d dv = CosmicWind.push(posBlocks, velGal, bodies());
        if (dv.lengthSquared() > 0) {
            Vector3d mc = frame.dirToMc(dv);
            player.setDeltaMovement(player.getDeltaMovement().add(mc.x, mc.y, mc.z));
        }
        return voidNow;
    }

    /** Leaving the galaxy (unlinked, world closed): nothing carries over. */
    static void reset() {
        active = false;
        inVoid = false;
        landed = 0;
        lastMario = null;
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
