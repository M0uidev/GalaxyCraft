package dev.moui.galaxycraft.net;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * A planet on its way from the host to a guest: the bytes of its save (already gzip), cut in
 * chunks, put together again and checked against an etag. Pure: no Minecraft.
 */
public final class PlanetXfer {
    /** One chunk: well under the 1 MiB a server may send in one packet. */
    public static final int CHUNK = 48 * 1024;
    /** The biggest planet a guest takes (its save, gzip): a radius 200 planet is about 30 MB. */
    public static final int MAX_BYTES = 96 * 1024 * 1024;
    /** What the host's send queue lets out each server tick to one guest: 1.3 MB/s, so the poses and chat keep flowing. */
    public static final int BUDGET_PER_TICK = 64 * 1024;
    /** Planets a guest may have asked for and not got yet. */
    public static final int MAX_OUTSTANDING = 16;

    public static final int STATE_DATA = 0, STATE_SAME = 1, STATE_FAIL = 2;

    private PlanetXfer() {}

    /** What identifies a planet's bytes: their CRC32 over their length, so a guest can say what it already has. */
    public static long etag(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue() << 32 | data.length & 0xFFFFFFFFL;
    }

    public static int chunks(int total) {
        return (total + CHUNK - 1) / CHUNK;
    }

    /** Chunk seq of data (the last one is the rest). */
    public static byte[] slice(byte[] data, int seq) {
        int from = seq * CHUNK;
        return java.util.Arrays.copyOfRange(data, from, Math.min(data.length, from + CHUNK));
    }

    /** What the host has for a guest: the bytes and their etag. */
    public record Offer(byte[] data, long etag) {}

    /** The guest's end: chunks in order, bounded by what the head promised. */
    public static final class Assembler {
        private final byte[] data;
        private final long etag;
        private int next;

        public Assembler(int total, long etag) {
            if (total <= 0 || total > MAX_BYTES) throw new IllegalArgumentException("a planet of " + total + " bytes");
            this.data = new byte[total];
            this.etag = etag;
        }

        /** Takes the next chunk; false when it is not the one expected (or the wrong size): the transfer is bad. */
        public boolean accept(int seq, byte[] part) {
            if (seq != next || done()) return false;
            int from = seq * CHUNK;
            if (part.length != Math.min(CHUNK, data.length - from)) return false;
            System.arraycopy(part, 0, data, from, part.length);
            next++;
            return true;
        }

        public boolean done() {
            return next == chunks(data.length);
        }

        public int received() {
            return Math.min(data.length, next * CHUNK);
        }

        /** The planet's bytes if it is all here and is what the head said (null: not whole or corrupt). */
        public byte[] result() {
            return done() && etag(data) == etag ? data : null;
        }
    }

    /** A guest's send queue: payloads let out a little each tick (at least one), so a big planet cannot swamp the connection. */
    public static final class Outbox<T> {
        private record Item<T>(T payload, int size) {}

        private final ArrayDeque<Item<T>> queue = new ArrayDeque<>();
        private long queued;

        public void add(T payload, int size) {
            queue.add(new Item<>(payload, size));
            queued += size;
        }

        /** Sends from the front while the budget lasts (the first always goes, however big). */
        public void drain(int budget, Consumer<T> send) {
            while (!queue.isEmpty() && (budget > 0)) {
                Item<T> i = queue.poll();
                queued -= i.size;
                budget -= i.size;
                send.accept(i.payload);
            }
        }

        public boolean isEmpty() {
            return queue.isEmpty();
        }

        public long bytes() {
            return queued;
        }

        public void clear() {
            queue.clear();
            queued = 0;
        }
    }
}
