package dev.moui.galaxycraft.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bytes this process wrote for each multiplayer message type (the payload body, before the connection's compression),
 * so "how much does a guest cost" is a measurement. Pure: time is passed in. Counts what THIS side sends: read the
 * host's log for the host's traffic and the guest's for the guest's.
 */
public final class ByteMeter {
    /** The one meter every MpPayloads codec reports to. */
    public static final ByteMeter GLOBAL = new ByteMeter();

    private final Map<String, LongAdder> bytes = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> count = new ConcurrentHashMap<>();
    private long last;
    private boolean started;

    public void add(String type, int n) {
        bytes.computeIfAbsent(type, k -> new LongAdder()).add(n);
        count.computeIfAbsent(type, k -> new LongAdder()).increment();
    }

    /** One line per type that sent anything since the last report, biggest first, with KB/s over the period; null before the period is over. */
    public synchronized List<String> report(long nowNanos, double periodSeconds) {
        if (!started) {
            started = true;
            last = nowNanos;
            return null;
        }
        double seconds = (nowNanos - last) / 1e9;
        if (seconds < periodSeconds) return null;
        last = nowNanos;
        record Row(String type, long bytes, long count) {}
        List<Row> rows = new ArrayList<>();
        for (var e : bytes.entrySet()) {
            long b = e.getValue().sumThenReset();
            long c = count.get(e.getKey()).sumThenReset();
            if (b > 0) rows.add(new Row(e.getKey(), b, c));
        }
        rows.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        List<String> out = new ArrayList<>();
        long total = 0;
        for (Row r : rows) total += r.bytes;
        out.add(String.format("[mp bytes] %.1f KB/s sent in %.0f s", total / 1024.0 / seconds, seconds));
        for (Row r : rows)
            out.add(String.format("[mp bytes]   %-12s %8.2f KB/s %6d msgs %6d B/msg", r.type, r.bytes / 1024.0 / seconds, r.count, r.bytes / Math.max(1, r.count)));
        return out;
    }
}
