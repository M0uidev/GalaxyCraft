package dev.moui.galaxycraft.net;

/**
 * One remote player's recent states, and where to draw them: 100 ms behind the newest (so there are
 * two states to blend), then up to 250 ms along the sent velocity, then held; hidden after 5 s.
 * Not thread safe: call it from one thread (the client's).
 */
public final class PeerTrack {
    public static final long DELAY_NANOS = 100_000_000L, EXTRAPOLATE_NANOS = 250_000_000L, HIDE_NANOS = 5_000_000_000L;

    public record Pose(double x, double y, double z, double upX, double upY, double upZ, float yaw, float pitch, int mode, int flags) {}

    private PeerState older, newest;
    private long olderAt, newestAt;

    public void push(PeerState s, long arrivalNanos) {
        if (!s.valid()) return;
        if (newest != null && s.seq() - newest.seq() <= 0) return; // wrap-safe: newer is a positive difference
        older = newest;
        olderAt = newestAt;
        newest = s;
        newestAt = arrivalNanos;
    }

    /** Where to draw the player at nowNanos; null when there is nothing to show (no state, or silent for 5 s). */
    public Pose sample(long nowNanos) {
        if (newest == null || nowNanos - newestAt > HIDE_NANOS) return null;
        long target = nowNanos - DELAY_NANOS;
        if (older == null || target <= olderAt) return pose(older == null ? newest : older);
        if (target >= newestAt) {
            double dt = Math.min(target - newestAt, EXTRAPOLATE_NANOS) / 1e9;
            return new Pose(newest.x() + newest.vx() * dt, newest.y() + newest.vy() * dt, newest.z() + newest.vz() * dt,
                    newest.upX(), newest.upY(), newest.upZ(), newest.yaw(), newest.pitch(), newest.mode(), newest.flags());
        }
        return blend(older, newest, (double) (target - olderAt) / (newestAt - olderAt));
    }

    private static Pose pose(PeerState s) {
        return new Pose(s.x(), s.y(), s.z(), s.upX(), s.upY(), s.upZ(), s.yaw(), s.pitch(), s.mode(), s.flags());
    }

    private static Pose blend(PeerState a, PeerState b, double f) {
        double[] up = slerp(a.upX(), a.upY(), a.upZ(), b.upX(), b.upY(), b.upZ(), f);
        float dyaw = (float) ((((b.yaw() - a.yaw()) % 360) + 540) % 360 - 180); // the short way round
        return new Pose(a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f, a.z() + (b.z() - a.z()) * f,
                up[0], up[1], up[2], a.yaw() + dyaw * (float) f, a.pitch() + (b.pitch() - a.pitch()) * (float) f, b.mode(), b.flags());
    }

    /** Unit vectors turning the short way; same or opposite inputs fall back to the older one. */
    private static double[] slerp(double ax, double ay, double az, double bx, double by, double bz, double f) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
        ax /= la; ay /= la; az /= la;
        bx /= lb; by /= lb; bz /= lb;
        double dot = Math.max(-1, Math.min(1, ax * bx + ay * by + az * bz));
        double theta = Math.acos(dot), sin = Math.sin(theta);
        double w0 = 1 - f, w1 = f;
        if (sin >= 1e-6) {
            w0 = Math.sin((1 - f) * theta) / sin;
            w1 = Math.sin(f * theta) / sin;
        }
        double x = ax * w0 + bx * w1, y = ay * w0 + by * w1, z = az * w0 + bz * w1;
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-9) return new double[] {ax, ay, az}; // exactly opposite: no short way, keep the older
        return new double[] {x / len, y / len, z / len};
    }
}
