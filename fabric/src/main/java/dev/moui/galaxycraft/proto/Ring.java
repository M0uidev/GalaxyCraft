package dev.moui.galaxycraft.proto;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Optional;

/** Single-producer single-consumer byte ring (see GxcRingHeader in the protocol header). */
public final class Ring {
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public record Msg(int type, byte[] payload) {}

    private final MemorySegment seg;
    private final long off;
    private final long data;
    private final int cap;

    public Ring(MemorySegment seg, long offset) {
        this.seg = seg;
        this.off = offset;
        this.data = offset + 16;
        this.cap = seg.get(Seqlock.INT, offset + 8);
    }

    static void init(MemorySegment seg, long offset, int capacity) {
        seg.set(Seqlock.INT, offset, 0);
        seg.set(Seqlock.INT, offset + 4, 0);
        seg.set(Seqlock.INT, offset + 8, capacity);
    }

    private static int align8(int n) {
        return (n + 7) & ~7;
    }

    public boolean push(int type, byte[] payload) {
        int need = 8 + align8(payload.length);
        int head = Seqlock.getAcquire(seg, off);
        int tail = Seqlock.getAcquire(seg, off + 4);
        long free = cap - Integer.toUnsignedLong(head - tail);
        int pos = (int) Integer.remainderUnsigned(head, cap);
        int rem = cap - pos;
        int skip = need > rem ? rem : 0;
        if ((long) skip + need > free) return false;
        if (skip != 0) {
            if (rem >= 8) writeHeader(pos, Layout.MSG_PAD, rem - 8);
            pos = 0;
        }
        writeHeader(pos, type, payload.length);
        MemorySegment.copy(payload, 0, seg, ValueLayout.JAVA_BYTE, data + pos + 8, payload.length);
        Seqlock.setRelease(seg, off, head + skip + need);
        return true;
    }

    public Optional<Msg> pop() {
        while (true) {
            int head = Seqlock.getAcquire(seg, off);
            int tail = Seqlock.getAcquire(seg, off + 4);
            if (head == tail) return Optional.empty();
            int pos = (int) Integer.remainderUnsigned(tail, cap);
            int rem = cap - pos;
            if (rem < 8) {
                Seqlock.setRelease(seg, off + 4, tail + rem);
                continue;
            }
            int type = Short.toUnsignedInt(seg.get(SHORT, data + pos));
            int length = seg.get(Seqlock.INT, data + pos + 4);
            if (type == Layout.MSG_PAD) {
                Seqlock.setRelease(seg, off + 4, tail + rem);
                continue;
            }
            byte[] payload = new byte[length];
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, data + pos + 8, payload, 0, length);
            Seqlock.setRelease(seg, off + 4, tail + 8 + align8(length));
            return Optional.of(new Msg(type, payload));
        }
    }

    private void writeHeader(int pos, int type, int length) {
        seg.set(SHORT, data + pos, (short) type);
        seg.set(SHORT, data + pos + 2, (short) 0);
        seg.set(Seqlock.INT, data + pos + 4, length);
    }
}
