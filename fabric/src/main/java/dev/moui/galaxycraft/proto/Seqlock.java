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
    static final ValueLayout.OfInt INT_U = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public record WorldState(int sceneId, long frameId, Vector3d gravity, Vector3d queryPos, int flags) {
        /** The host has not seen a fresh PlayerState yet: queryPos is where the player is. */
        public boolean anchor() {
            return (flags & Layout.WORLD_ANCHOR) != 0;
        }

        /** Minecraft mode: the player follows Mario at queryPos; SMG2 owns the movement. */
        public boolean follow() {
            return (flags & Layout.WORLD_FOLLOW) != 0;
        }

        /** Real gravity to stand by (menus and SMG2's title screen have none). */
        public boolean hasGravity() {
            return gravity.lengthSquared() > 0.25; // the game publishes unit vectors or zero
        }
    }

    /** Keys as an SDL-scancode-indexed bitmap; mouse and wheel are accumulated since the host started. */
    public record InputState(int buttons, double mouseX, double mouseY, double wheel, byte[] keys) {}

    /** look/up are the camera's; camOffset is the camera minus pos (galaxy units); view is Layout.VIEW_*. */
    /**
     * itemActive: the clicks break and place blocks; screenOpen: the keyboard types in Minecraft;
     * flying: /fly, the player flies off on its own (camOffset is then from Mario's feet).
     */
    public record PlayerOut(long frameId, Vector3d pos, Vector3d look, Vector3d up, float fovY, float eye,
            boolean onGround, Vector3d camOffset, int view, int sceneId, boolean itemActive, boolean screenOpen,
            boolean flying, boolean hitboxes) {
        public PlayerOut(long frameId, Vector3d pos, Vector3d look, Vector3d up, float fovY, float eye,
                boolean onGround, Vector3d camOffset, int view, int sceneId) {
            this(frameId, pos, look, up, fovY, eye, onGround, camOffset, view, sceneId, false, false, false, false);
        }
    }

    /** count of characters ever typed; character i is codepoints[i % TEXT_RING]. */
    public record TextState(int count, int[] codepoints) {}

    /** SMG2's camera and Mario from one game frame (galaxy space). */
    public record GameCamera(int flags, long frameId, Vector3d camPos, Vector3d camDir, Vector3d camUp, float fovY,
            Vector3d marioPos, Vector3d marioFront) {
        public boolean valid() {
            return (flags & Layout.GAMECAM_VALID) != 0;
        }

        /** A cutscene shows Mario himself. */
        public boolean demo() {
            return (flags & Layout.GAMECAM_DEMO) != 0;
        }
    }

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

    public static Optional<GameCamera> readGameCamera(MemorySegment s) {
        long o = Layout.OFF_GAMECAM;
        for (int attempt = 0; attempt < 100; attempt++) {
            int s1 = getAcquire(s, o);
            if (s1 == 0) return Optional.empty();
            if ((s1 & 1) != 0) continue;
            var c = new GameCamera(s.get(INT, o + 4), s.get(LONG, o + 8), vec(s, o + 16), vec(s, o + 28),
                    vec(s, o + 40), s.get(FLOAT, o + 52), vec(s, o + 56), vec(s, o + 68));
            VarHandle.acquireFence();
            if (getAcquire(s, o) == s1) return Optional.of(c);
        }
        return Optional.empty();
    }

    public static Optional<InputState> readInput(MemorySegment s) {
        long o = Layout.OFF_INPUT;
        for (int attempt = 0; attempt < 100; attempt++) {
            int s1 = getAcquire(s, o);
            if (s1 == 0) return Optional.empty();
            if ((s1 & 1) != 0) continue;
            byte[] keys = new byte[64];
            MemorySegment.copy(s, ValueLayout.JAVA_BYTE, o + 32, keys, 0, 64);
            var in = new InputState(s.get(INT_U, o + 4), s.get(DOUBLE, o + 8), s.get(DOUBLE, o + 16),
                    s.get(DOUBLE, o + 24), keys);
            VarHandle.acquireFence();
            if (getAcquire(s, o) == s1) return Optional.of(in);
        }
        return Optional.empty();
    }

    public static Optional<TextState> readText(MemorySegment s) {
        long o = Layout.OFF_TEXT;
        for (int attempt = 0; attempt < 100; attempt++) {
            int s1 = getAcquire(s, o);
            if ((s1 & 1) != 0) continue;
            int[] cps = new int[Layout.TEXT_RING];
            for (int i = 0; i < cps.length; i++) cps[i] = s.get(INT_U, o + 8 + 4L * i);
            var t = new TextState(s.get(INT_U, o + 4), cps);
            VarHandle.acquireFence();
            if (getAcquire(s, o) == s1) return Optional.of(t);
        }
        return Optional.empty();
    }

    public static void writePlayer(MemorySegment s, PlayerOut p) {
        long o = Layout.OFF_PLAYER;
        int seq = s.get(INT, o);
        s.set(INT, o, seq + 1);
        VarHandle.releaseFence();
        s.set(INT, o + 4, (p.onGround() ? Layout.PLAYER_ON_GROUND : 0) | (p.itemActive() ? Layout.PLAYER_ITEM_ACTIVE : 0)
                | (p.screenOpen() ? Layout.PLAYER_SCREEN : 0) | (p.flying() ? Layout.PLAYER_FLYING : 0)
                | (p.hitboxes() ? Layout.PLAYER_HITBOXES : 0));
        s.set(LONG, o + 8, p.frameId());
        putVec(s, o + 16, p.pos());
        putVec(s, o + 28, p.look());
        putVec(s, o + 40, p.up());
        s.set(FLOAT, o + 52, p.fovY());
        s.set(FLOAT, o + 56, p.eye());
        putVec(s, o + 60, p.camOffset());
        s.set(INT, o + 72, p.view());
        s.set(INT, o + 76, p.sceneId());
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
