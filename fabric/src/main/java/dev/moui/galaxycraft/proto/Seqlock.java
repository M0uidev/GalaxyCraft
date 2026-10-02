package dev.moui.galaxycraft.proto;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Optional;
import org.joml.Vector3d;

/** Latest-value slots guarded by a sequence counter (see protocol header). */
public final class Seqlock {
    static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final VarHandle INT_VH = INT.varHandle();

    public record WorldState(int sceneId, long frameId, Vector3d gravity, Vector3d queryPos, int flags) {
        /** The host has not seen a fresh PlayerState yet: queryPos is where the player is. */
        public boolean anchor() {
            return (flags & Layout.WORLD_ANCHOR) != 0;
        }
    }

    public record PlayerOut(long frameId, Vector3d pos, Vector3d look, Vector3d up, float fovY, float eye,
            boolean onGround) {}

    private Seqlock() {}

    static int getAcquire(MemorySegment s, long off) {
        return (int) INT_VH.getAcquire(s, off);
    }

    static void setRelease(MemorySegment s, long off, int v) {
        INT_VH.setRelease(s, off, v);
    }

    public static Optional<WorldState> readWorld(MemorySegment s) {
        long o = Layout.OFF_WORLD;
        for (int attempt = 0; attempt < 100; attempt++) {
            int s1 = getAcquire(s, o);
            if (s1 == 0) return Optional.empty();
            if ((s1 & 1) != 0) continue;
            var w = new WorldState(s.get(INT, o + 4), s.get(LONG, o + 8), vec(s, o + 16), vec(s, o + 28), s.get(INT, o + 40));
            VarHandle.acquireFence();
            if (getAcquire(s, o) == s1) return Optional.of(w);
        }
        return Optional.empty();
    }

    public static void writePlayer(MemorySegment s, PlayerOut p) {
        long o = Layout.OFF_PLAYER;
        int seq = s.get(INT, o);
        s.set(INT, o, seq + 1);
        VarHandle.releaseFence();
        s.set(INT, o + 4, p.onGround() ? Layout.PLAYER_ON_GROUND : 0);
        s.set(LONG, o + 8, p.frameId());
        putVec(s, o + 16, p.pos());
        putVec(s, o + 28, p.look());
        putVec(s, o + 40, p.up());
        s.set(FLOAT, o + 52, p.fovY());
        s.set(FLOAT, o + 56, p.eye());
        setRelease(s, o, seq + 2);
    }

    private static Vector3d vec(MemorySegment s, long o) {
        return new Vector3d(s.get(FLOAT, o), s.get(FLOAT, o + 4), s.get(FLOAT, o + 8));
    }

    private static void putVec(MemorySegment s, long o, Vector3d v) {
        s.set(FLOAT, o, (float) v.x);
        s.set(FLOAT, o + 4, (float) v.y);
        s.set(FLOAT, o + 8, (float) v.z);
    }
}
