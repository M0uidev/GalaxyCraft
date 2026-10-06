package dev.moui.galaxycraft.gravity;

import java.util.Optional;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Rigid map between galaxy space (SMG2 units) and Minecraft space, re-aimed every tick so the
 * galaxy's gravity at the player always points to Minecraft's -Y. Minecraft physics then runs
 * unchanged: instead of bending gravity, the world is rotated around the player's feet.
 *
 * mc = r · (SCALE · gal) + t
 */
public final class GravityFrame {
    /**
     * Galaxy units per Minecraft block. Measured on SB4E (docs/PHASE4.md): Mario stands about
     * 145 units tall, and Steve is 1.8 blocks, so 1 block = 80 units. Override with
     * -Dgalaxycraft.unitsPerBlock.
     */
    public static final double DEFAULT_UNITS_PER_BLOCK = 80;
    public static final double SCALE = 1.0 / unitsPerBlock(System.getProperty("galaxycraft.unitsPerBlock"));
    private static final double MIN_ANGLE = Math.toRadians(0.05);
    private static final double REBASE_MIN_Y = 36, REBASE_MAX_Y = 164, REBASE_Y = 100;
    private static final Vector3d UP = new Vector3d(0, 1, 0);
    /** Flying into another body's gravity, up turns this much a tick at most: a flip in 1.5 s. */
    public static final double FLIGHT_TURN_PER_TICK = Math.PI / 30;

    /** An alignGrid: the turn about Minecraft's Y (radians) and the shift it made, Minecraft space. */
    public record Align(double yaw, Vector3d shift) {}

    /** deltaMc = r'·r⁻¹: rotate Minecraft-space velocities and look vectors by it after a re-aim. */
    public record Update(boolean rotated, Quaterniond deltaMc) {}

    private final Quaterniond r = new Quaterniond();
    /** r as it was before this tick's update: drawing between ticks turns smoothly from it. */
    private final Quaterniond rPrev = new Quaterniond();
    private final Vector3d t = new Vector3d();
    /** How far slide moved the player this tick (galaxy units): drawing between ticks moves it gradually. */
    private final Vector3d slid = new Vector3d();

    public GravityFrame(Vector3d galStart, Vector3d mcStart, Vector3d gStart) {
        if (gStart.lengthSquared() > 1e-12) {
            r.set(minimalRotation(new Vector3d(gStart).normalize().negate(), UP));
        }
        t.set(mcStart).sub(r.transform(new Vector3d(galStart).mul(SCALE)));
        rPrev.set(r);
    }

    /** Parses a units-per-block setting; anything missing or absurd gives the default. */
    public static double unitsPerBlock(String value) {
        if (value == null) return DEFAULT_UNITS_PER_BLOCK;
        try {
            double u = Double.parseDouble(value.strip());
            return u >= 1 && u <= 10_000 ? u : DEFAULT_UNITS_PER_BLOCK;
        } catch (NumberFormatException e) {
            return DEFAULT_UNITS_PER_BLOCK;
        }
    }

    private GravityFrame(GravityFrame o) {
        r.set(o.r);
        rPrev.set(o.rPrev);
        t.set(o.t);
        slid.set(o.slid);
    }

    public GravityFrame copy() {
        return new GravityFrame(this);
    }

    public Vector3d toMc(Vector3d gal) {
        return r.transform(new Vector3d(gal).mul(SCALE)).add(t);
    }

    public Vector3d toGal(Vector3d mc) {
        return r.transformInverse(new Vector3d(mc).sub(t)).div(SCALE);
    }

    /**
     * Start of every tick, before any update: what the frame is now is what drawing between ticks
     * starts from. A tick that does not turn the frame (the void) then draws still, instead of
     * swinging back to the last turn's start every tick.
     */
    public void startTick() {
        rPrev.set(r);
        slid.zero();
    }

    /**
     * Moves the player by d in the galaxy (galaxy units) while it stays where it is in Minecraft:
     * the pulse, faster than Minecraft lets a player move (its server would pull it back, and it would
     * fill the void overworld with chunks).
     */
    public void slide(Vector3d galUnits) {
        t.sub(r.transform(new Vector3d(galUnits).mul(SCALE)));
        slid.add(galUnits);
    }

    /** toGal for drawing between ticks (partial 0: the last tick, 1: this one): a slide comes in gradually. */
    public Vector3d toGal(Vector3d mc, double partial) {
        return toGal(mc).sub(new Vector3d(slid).mul(1 - partial));
    }

    public Vector3d dirToMc(Vector3d d) {
        return r.transform(new Vector3d(d));
    }

    public Vector3d dirToGal(Vector3d d) {
        return r.transformInverse(new Vector3d(d));
    }

    /**
     * dirToGal between the previous tick's frame (partial 0) and this one (1): what the renderer
     * reports, so a frame turning once per tick reads as a smooth turn at the display rate.
     */
    public Vector3d dirToGal(Vector3d d, double partial) {
        return new Quaterniond(rPrev).slerp(r, partial).transformInverse(new Vector3d(d));
    }

    /**
     * fromUp turned towards toUp by at most maxRad (both unit): sudden gravity changes (the edge
     * of a box planet) become a short turn, as SMG2's own camera does.
     */
    public static Vector3d limitTurn(Vector3d fromUp, Vector3d toUp, double maxRad) {
        double angle = fromUp.angle(toUp);
        if (angle <= maxRad) return new Vector3d(toUp);
        return minimalRotation(fromUp, toUp).slerp(new Quaterniond(), 1 - maxRad / angle)
                .transform(new Vector3d(fromUp)).normalize();
    }

    /** Galaxy-space "up" (opposite the gravity the frame is currently aimed at). */
    public Vector3d upGal() {
        return dirToGal(UP);
    }

    public Update update(Vector3d gravityGal, Vector3d playerMc) {
        rPrev.set(r);
        if (gravityGal.lengthSquared() < 1e-12) return new Update(false, new Quaterniond());
        Vector3d uNew = new Vector3d(gravityGal).normalize().negate();
        Vector3d uOld = upGal();
        if (uOld.angle(uNew) < MIN_ANGLE) return new Update(false, new Quaterniond());

        Vector3d gal = toGal(playerMc);
        Quaterniond q = minimalRotation(uOld, uNew);
        Quaterniond rNew = new Quaterniond(r).mul(q.invert());
        Quaterniond delta = new Quaterniond(rNew).mul(new Quaterniond(r).invert());
        r.set(rNew).normalize();
        t.set(playerMc).sub(r.transform(gal.mul(SCALE)));
        return new Update(true, delta);
    }

    /** Moves the frame so the player sits at y=100 if they drifted out of [36, 164]. */
    public Optional<Vector3d> rebase(Vector3d playerMc) {
        if (playerMc.y >= REBASE_MIN_Y && playerMc.y <= REBASE_MAX_Y) return Optional.empty();
        Vector3d gal = toGal(playerMc);
        Vector3d np = new Vector3d(playerMc.x, REBASE_Y, playerMc.z);
        t.set(np).sub(r.transform(gal.mul(SCALE)));
        return Optional.of(np);
    }

    /**
     * Lines Minecraft's axes up with a planet's block grid at the player: turns about Minecraft's
     * Y (the least turn that takes axisGal, a grid edge, onto X or Z), then shifts so cornerGal, a
     * grid corner, lands on whole blocks. The player's galaxy position stays where it was until
     * the caller moves it by the shift; looks and velocities fixed in the galaxy turn by
     * yaw, as Ry(yaw) turns a vector at angle φ from +X (toward +Z) to φ - yaw.
     */
    public Align alignGrid(Vector3d axisGal, Vector3d cornerGal, Vector3d playerMc) {
        Vector3d a = dirToMc(axisGal);
        if (a.x * a.x + a.z * a.z < 1e-12) return new Align(0, new Vector3d());
        double phi = Math.atan2(a.z, a.x);
        double yaw = phi - Math.round(phi / (Math.PI / 2)) * (Math.PI / 2);
        Vector3d gal = toGal(playerMc);
        Quaterniond q = new Quaterniond().rotationY(yaw);
        r.premul(q).normalize();
        rPrev.premul(q).normalize();
        t.set(playerMc).sub(r.transform(gal.mul(SCALE)));
        Vector3d c = toMc(cornerGal);
        Vector3d shift = new Vector3d(Math.round(c.x) - c.x, Math.round(c.y) - c.y, Math.round(c.z) - c.z);
        t.add(shift);
        return new Align(yaw, shift);
    }

    /** Shortest rotation taking unit vector from onto unit vector to (180° about a stable axis). */
    static Quaterniond minimalRotation(Vector3d from, Vector3d to) {
        double d = from.dot(to);
        if (d < -0.9999999) {
            Vector3d axis = new Vector3d(from).cross(1, 0, 0);
            if (axis.lengthSquared() < 1e-6) axis = new Vector3d(from).cross(0, 0, 1);
            return new Quaterniond().fromAxisAngleRad(axis.normalize(), Math.PI);
        }
        return new Quaterniond().rotationTo(from, to);
    }
}
