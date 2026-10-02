package dev.moui.galaxycraft.proto;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** The shared memory file mapped read/write for the lifetime of the process. */
public final class Shm implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment seg;

    private Shm(Arena arena, MemorySegment seg) {
        this.arena = arena;
        this.seg = seg;
    }

    public MemorySegment seg() {
        return seg;
    }

    /** Maps an existing, fully sized file; empty if it is missing or the wrong size. */
    public static Optional<Shm> open(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != Layout.TOTAL_SIZE) return Optional.empty();
            return Optional.of(map(path, StandardOpenOption.READ, StandardOpenOption.WRITE));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Creates (or resets the ring headers of) the file, as a host would. */
    public static Shm create(Path path) throws IOException {
        Shm shm = map(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        Ring.init(shm.seg, Layout.OFF_RING_S2M, Layout.RING_S2M_CAP);
        Ring.init(shm.seg, Layout.OFF_RING_M2S, Layout.RING_M2S_CAP);
        shm.seg.set(Seqlock.INT, Layout.OFF_OVERLAY, -1);
        return shm;
    }

    private static Shm map(Path path, StandardOpenOption... opts) throws IOException {
        try (FileChannel ch = FileChannel.open(path, opts)) {
            if (ch.size() < Layout.TOTAL_SIZE) ch.write(ByteBuffer.allocate(1), Layout.TOTAL_SIZE - 1); // grow sparse
            Arena arena = Arena.ofShared();
            return new Shm(arena, ch.map(FileChannel.MapMode.READ_WRITE, 0, Layout.TOTAL_SIZE, arena));
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
