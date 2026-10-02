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
    public static final double SCALE = 0.01; // 100 galaxy units = 1 block
    private static final double MIN_ANGLE = Math.toRadians(0.05);
    private static final double REBASE_MIN_Y = 36, REBASE_MAX_Y = 164, REBASE_Y = 100;
    private static final Vector3d UP = new Vector3d(0, 1, 0);

    /** deltaMc = r'·r⁻¹: rotate Minecraft-space velocities and look vectors by it after a re-aim. */
    public record Update(boolean rotated, Quaterniond deltaMc) {}

    private final Quaterniond r = new Quaterniond();
    private final Vector3d t = new Vector3d();

    public GravityFrame(Vector3d galStart, Vector3d mcStart, Vector3d gStart) {
        if (gStart.lengthSquared() > 1e-12) {
            r.set(minimalRotation(new Vector3d(gStart).normalize().negate(), UP));
        }
        t.set(mcStart).sub(r.transform(new Vector3d(galStart).mul(SCALE)));
    }

    private GravityFrame(GravityFrame o) {
        r.set(o.r);
        t.set(o.t);
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

    public Vector3d dirToMc(Vector3d d) {
        return r.transform(new Vector3d(d));
    }

    public Vector3d dirToGal(Vector3d d) {
        return r.transformInverse(new Vector3d(d));
    }

    /** Galaxy-space "up" (opposite the gravity the frame is currently aimed at). */
    public Vector3d upGal() {
        return dirToGal(UP);
    }

    public Update update(Vector3d gravityGal, Vector3d playerMc) {
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
