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
    /** Planets a guest may have asked for and not got yet: the guest keeps under this (MAX_ASKED) and asks for the rest later. */
    public static final int MAX_OUTSTANDING = 16;
    public static final int MAX_ASKED = 8;

    /**
     * What one tick may send a guest: `base`, less when the connection is backed up. A connection that takes less than
     * `writable` more bytes is a slow link: send at most that (nothing while it is not writable; it drains and the next tick goes on).
     */
    public static int sendBudget(int base, long writable) {
        if (writable >= base) return base;
        return (int) Math.max(0, writable);
    }

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
    /** seq: the planet's edit sequence when the bytes were taken (every edit up to it is in them). */
    public record Offer(byte[] data, long etag, int seq) {}

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

    /**
     * A guest's send queue: payloads let out a little each tick (at least one), so a big planet cannot swamp the connection.
     * Payloads belong to a lane (a planet's index): one lane is served to the end before the next starts (a planet's head and
     * chunks must stay in order), and the lane the guest stands on goes first, so what it needs now arrives before far views.
     */
    public static final class Outbox<T> {
        private record Item<T>(T payload, int size) {}

        public static final int NO_LANE = -1;

        private final java.util.LinkedHashMap<Integer, ArrayDeque<Item<T>>> lanes = new java.util.LinkedHashMap<>();
        private long queued;

        public void add(T payload, int size) {
            add(payload, size, NO_LANE);
        }

        public void add(T payload, int size, int lane) {
            lanes.computeIfAbsent(lane, k -> new ArrayDeque<>()).add(new Item<>(payload, size));
            queued += size;
        }

        /** Sends from the front while the budget lasts (the first always goes, however big). */
        public void drain(int budget, Consumer<T> send) {
            drain(budget, send, NO_LANE);
        }

        /** As {@link #drain(int, Consumer)}, the lane `first` (if it has anything) before the others. */
        public void drain(int budget, Consumer<T> send, int first) {
            while (budget > 0 && !lanes.isEmpty()) {
                ArrayDeque<Item<T>> q = lanes.get(NO_LANE); // loose payloads (nobody's planet) never wait
                if (q == null || q.isEmpty()) q = first != NO_LANE ? lanes.get(first) : null;
                if (q == null || q.isEmpty()) q = lanes.values().iterator().next();
                Item<T> i = q.poll();
                if (i == null) {
                    lanes.values().removeIf(ArrayDeque::isEmpty);
                    continue;
                }
                queued -= i.size;
                budget -= i.size;
                send.accept(i.payload);
                if (q.isEmpty()) lanes.values().removeIf(ArrayDeque::isEmpty);
            }
        }

        public boolean isEmpty() {
            return lanes.isEmpty();
        }

        public long bytes() {
            return queued;
        }

        public void clear() {
            lanes.clear();
            queued = 0;
        }
    }
}
