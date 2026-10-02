package dev.moui.galaxycraft.bridge;

import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Ring;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.proto.Shm;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Mod side of the shared memory link. Call {@link #poll()} once per tick: it (re)opens the
 * file, heartbeats, drains host events into the {@link PartListener} and caches WorldState.
 */
public final class BridgeClient {
    public interface PartListener {
        /** A part's KCL is complete, or its matrix changed (same kcl array). mtx is 3x4 row-major. */
        void onUpsert(int partId, double[] mtx, byte[] kcl);

        void onRemove(int partId);

        void onScene(int sceneId);
    }

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final long REOPEN_INTERVAL_MS = 1000;
    private static final int MAX_MESSAGES_PER_POLL = 512;

    private static final class Part {
        final int size;
        final byte[] data;
        double[] mtx;
        int received;
        boolean complete;

        Part(int size, double[] mtx) {
            this.size = size;
            this.data = new byte[size];
            this.mtx = mtx;
        }
    }

    private final Path path;
    private final LongSupplier clockMs;
    private final PartListener listener;
    private final Map<Integer, Part> parts = new HashMap<>();
    private Shm shm;
    private Ring s2m;
    private Ring m2s;
    private long lastOpenAttempt = Long.MIN_VALUE;
    private int hostPid;
    private long now;
    private Optional<Seqlock.WorldState> world = Optional.empty();

    public BridgeClient(Path path, LongSupplier clockMs, PartListener listener) {
        this.path = path;
        this.clockMs = clockMs;
        this.listener = listener;
    }

    public void poll() {
        now = clockMs.getAsLong();
        if (shm == null && !tryOpen()) return;
        MemorySegment s = shm.seg();
        if (s.get(INT, Layout.H_MAGIC) != Layout.MAGIC || s.get(INT, Layout.H_VERSION) != Layout.VERSION) {
            world = Optional.empty();
            return;
        }
        int pid = s.get(INT, Layout.H_HOST_PID);
        if (pid != hostPid) { // host (re)started: ask it for the scene again
            hostPid = pid;
            parts.clear();
            m2s.push(Layout.MSG_HELLO, le(Layout.MOD_VERSION));
        }
        s.set(INT, Layout.H_MOD_PID, (int) ProcessHandle.current().pid());
        s.set(LONG, Layout.H_MOD_HEARTBEAT, now);
        for (int i = 0; i < MAX_MESSAGES_PER_POLL; i++) {
            Optional<Ring.Msg> m = s2m.pop();
            if (m.isEmpty()) break;
            handle(m.get());
        }
        world = Seqlock.readWorld(s);
    }

    public boolean linked() {
        if (shm == null) return false;
        long hb = shm.seg().get(LONG, Layout.H_HOST_HEARTBEAT);
        return hb > 0 && now - hb < Layout.HEARTBEAT_TIMEOUT_MS
                && shm.seg().get(INT, Layout.H_MAGIC) == Layout.MAGIC;
    }

    /** Linked and the host hands the game over (Dolphin's link toggle is on). */
    public boolean gameLinked() {
        return linked() && (shm.seg().get(INT, Layout.H_HOST_FLAGS) & 1) != 0;
    }

    public Optional<Seqlock.WorldState> world() {
        return linked() ? world : Optional.empty();
    }

    /** The mapped shared memory, while the host is linked. */
    public Optional<MemorySegment> segment() {
        return linked() ? Optional.of(shm.seg()) : Optional.empty();
    }

    /** The host's keyboard and mouse state, if it publishes any (Dolphin does, the stub does not). */
    public Optional<Seqlock.InputState> input() {
        return linked() ? Seqlock.readInput(shm.seg()) : Optional.empty();
    }

    public void sendPlayer(Seqlock.PlayerOut p) {
        if (shm != null) Seqlock.writePlayer(shm.seg(), p);
    }

    private boolean tryOpen() {
        if (lastOpenAttempt != Long.MIN_VALUE && now - lastOpenAttempt < REOPEN_INTERVAL_MS) return false;
        lastOpenAttempt = now;
        Optional<Shm> opened = Shm.open(path);
        if (opened.isEmpty()) return false;
        shm = opened.get();
        s2m = new Ring(shm.seg(), Layout.OFF_RING_S2M);
        m2s = new Ring(shm.seg(), Layout.OFF_RING_M2S);
        hostPid = 0;
        return true;
    }

    private void handle(Ring.Msg m) {
        ByteBuffer b = ByteBuffer.wrap(m.payload()).order(ByteOrder.LITTLE_ENDIAN);
        switch (m.type()) {
            case Layout.MSG_SCENE_CHANGE -> {
                parts.clear();
                listener.onScene(b.getInt());
            }
            case Layout.MSG_PART_UPSERT -> {
                int id = b.getInt();
                int size = b.getInt();
                double[] mtx = new double[12];
                for (int i = 0; i < 12; i++) mtx[i] = b.getFloat();
                Part p = parts.get(id);
                if (p != null && p.complete && p.size == size) {
                    p.mtx = mtx;
                    listener.onUpsert(id, mtx, p.data);
                } else {
                    p = new Part(size, mtx);
                    parts.put(id, p);
                    if (size == 0) complete(id, p);
                }
            }
            case Layout.MSG_KCL_CHUNK -> {
                int id = b.getInt();
                int offset = b.getInt();
                int total = b.getInt();
                Part p = parts.get(id);
                int len = b.remaining();
                if (p == null || p.complete || p.size != total || offset < 0 || offset + len > total) return;
                b.get(p.data, offset, len);
                p.received += len;
                if (p.received >= p.size) complete(id, p);
            }
            case Layout.MSG_PART_REMOVE -> {
                int id = b.getInt();
                parts.remove(id);
                listener.onRemove(id);
            }
            default -> { } // unknown messages are skipped for forward compatibility
        }
    }

    private void complete(int id, Part p) {
        p.complete = true;
        listener.onUpsert(id, p.mtx, p.data);
    }

    private static byte[] le(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }
}
