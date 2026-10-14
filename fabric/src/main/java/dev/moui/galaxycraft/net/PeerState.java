package dev.moui.galaxycraft.net;

/** What every player tells the others ~10 times a second. Position in universe units. */
public record PeerState(int seq, double x, double y, double z, float upX, float upY, float upZ, float yaw, float pitch,
        float vx, float vy, float vz, int mode, int flags) {

    /** False for anything that could not be a real player's state (NaN, infinities, absurd values). */
    public boolean valid() {
        double m = Protocol.MAX_COORD;
        if (!(Math.abs(x) <= m && Math.abs(y) <= m && Math.abs(z) <= m)) return false; // false for NaN too
        double up = Math.sqrt((double) upX * upX + (double) upY * upY + (double) upZ * upZ);
        if (!(up > 0.5 && up < 1.5)) return false;
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return false;
        float s = Protocol.MAX_SPEED;
        if (!(Math.abs(vx) <= s && Math.abs(vy) <= s && Math.abs(vz) <= s)) return false;
        return mode == Protocol.MODE_STEVE || mode == Protocol.MODE_MARIO;
    }
}
