package dev.moui.galaxycraft.music;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** A Nintendo STRM (.ast) file of 16-bit PCM: its header and an index of its blocks. */
public final class AstFile {
    static final int HEADER = 0x40, BLOCK_HEADER = 0x20;
    private static final int STRM = 0x5354524D, BLCK = 0x424C434B;

    public final Path path;
    public final int channels, rate;
    public final long frames;
    public final boolean loops;
    public final long loopStart, loopEnd;
    /** Per block: file offset of channel 0's bytes, bytes per channel, first frame. */
    final long[] blockData, blockFirst;
    final int[] blockBytes;
    final int maxBlockBytes;

    private AstFile(Path path, int channels, int rate, long frames, boolean loops, long loopStart, long loopEnd,
            long[] blockData, int[] blockBytes, long[] blockFirst) {
        this.path = path;
        this.channels = channels;
        this.rate = rate;
        this.frames = frames;
        this.loops = loops;
        this.loopStart = loopStart;
        this.loopEnd = loopEnd;
        this.blockData = blockData;
        this.blockBytes = blockBytes;
        this.blockFirst = blockFirst;
        int max = 0;
        for (int b : blockBytes) max = Math.max(max, b);
        this.maxBlockBytes = max;
    }

    public static AstFile open(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN);
            readFully(ch, h, 0);
            if (h.getInt(0) != STRM) throw new IOException(path + " is not an AST file");
            int format = h.getShort(8) & 0xFFFF, bits = h.getShort(10) & 0xFFFF;
            if (format != 1 || bits != 16) throw new IOException(path + ": AST format " + format + "/" + bits + " is not PCM16");
            int channels = h.getShort(12) & 0xFFFF;
            if (channels < 1 || channels > 8) throw new IOException(path + ": " + channels + " channels");
            boolean loops = (h.getShort(14) & 0xFFFF) == 0xFFFF;
            int rate = h.getInt(16);
            if (rate < 8000 || rate > 192000) throw new IOException(path + ": rate " + rate);
            long frames = h.getInt(20) & 0xFFFFFFFFL, ls = h.getInt(24) & 0xFFFFFFFFL, le = h.getInt(28) & 0xFFFFFFFFL;

            List<long[]> blocks = new ArrayList<>(); // {data offset, bytes per channel, first frame}
            long size = ch.size(), off = HEADER, frame = 0;
            ByteBuffer bh = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            while (off + BLOCK_HEADER <= size) {
                bh.clear();
                readFully(ch, bh, off);
                if (bh.getInt(0) != BLCK) throw new IOException(path + ": no block at " + off);
                int per = bh.getInt(4);
                if (per <= 0 || off + BLOCK_HEADER + (long) per * channels > size) break; // a cut-off last block is dropped
                blocks.add(new long[] {off + BLOCK_HEADER, per, frame});
                frame += per / 2;
                off += BLOCK_HEADER + (long) per * channels;
            }
            if (blocks.isEmpty()) throw new IOException(path + " has no audio");
            frames = Math.min(frames, frame);
            if (loops && (le <= ls || le > frames)) le = frames;
            if (loops && ls >= le) loops = false;
            long[] data = new long[blocks.size()], first = new long[blocks.size()];
            int[] bytes = new int[blocks.size()];
            for (int i = 0; i < data.length; i++) {
                data[i] = blocks.get(i)[0];
                bytes[i] = (int) blocks.get(i)[1];
                first[i] = blocks.get(i)[2];
            }
            return new AstFile(path, channels, rate, frames, loops, ls, le, data, bytes, first);
        }
    }

    static void readFully(FileChannel ch, ByteBuffer b, long at) throws IOException {
        while (b.hasRemaining()) {
            int n = ch.read(b, at + b.position());
            if (n < 0) throw new EOFException();
        }
    }
}
