package dev.moui.galaxycraft.net;

/**
 * One guest's allowance of messages to the host, over every message type together (each type also has its own limits):
 * a legitimate guest sends about 25 a second (state, position, ship pose, actions); a flood over the internet is
 * dropped, and a guest that keeps flooding is told to leave. Pure: time is passed in.
 */
public final class InboundGuard {
    public enum Verdict { OK, DROP, KICK }

    /** Sustained messages per second, and the burst on top (a join sends a good many at once). */
    public static final double PER_SECOND = 120;
    public static final int BURST = 300;
    /** Refused messages (net of good ones) after which the guest is kicked: about a second of flooding at 1000 a second. */
    public static final int KICK_AFTER = 600;

    private final RateLimiter limit = new RateLimiter(PER_SECOND, BURST);
    private int refused;

    public Verdict admit(long nowNanos) {
        if (limit.allow(nowNanos)) {
            if (refused > 0) refused--;
            return Verdict.OK;
        }
        return ++refused >= KICK_AFTER ? Verdict.KICK : Verdict.DROP;
    }
}
