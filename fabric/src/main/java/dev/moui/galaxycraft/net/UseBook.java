package dev.moui.galaxycraft.net;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A guest's right clicks on blocks that wait for the host's answer, by number. The host says whether the
 * click was used (a door opened) or passed (nothing took it: the guest places its block). An answer that never
 * comes is dropped, not guessed at. Pure logic, one thread (the client's).
 */
public final class UseBook {
    /** Client ticks (10 s) after which a click without an answer is forgotten. */
    public static final int TIMEOUT_TICKS = 200;
    /** Clicks waiting at most: more are refused (the guest then places locally, as before). */
    public static final int MAX_PENDING = 16;

    private record Pending(Runnable onPass, Runnable onUsed, int sentAt) {}

    private final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private int nonce;

    /** Waits for an answer; returns the number to send, or -1 if too many clicks are waiting. */
    public int add(Runnable onPass, Runnable onUsed, int now) {
        if (pending.size() >= MAX_PENDING) return -1;
        int n = ++nonce;
        pending.put(n, new Pending(onPass, onUsed, now));
        return n;
    }

    /** The host's answer: runs what waited (false: nothing waited under that number). */
    public boolean settle(int n, boolean passed) {
        Pending p = pending.remove(n);
        if (p == null) return false;
        (passed ? p.onPass() : p.onUsed()).run();
        return true;
    }

    /** Forgets the clicks unanswered for TIMEOUT_TICKS; how many. */
    public int expire(int now) {
        int out = 0;
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); )
            if (now - it.next().sentAt() >= TIMEOUT_TICKS) {
                it.remove();
                out++;
            }
        return out;
    }

    public int size() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
    }
}
