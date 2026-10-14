package dev.moui.galaxycraft.net;

/** Token bucket: perSecond sustained, burst at once. Time is passed in so it is testable. */
public final class RateLimiter {
    private final double perSecond;
    private final double burst;
    private double tokens;
    private long last;
    private boolean started;

    public RateLimiter(double perSecond, int burst) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.tokens = burst;
    }

    public boolean allow(long nowNanos) {
        if (!started) {
            started = true;
            last = nowNanos;
        }
        tokens = Math.min(burst, tokens + (nowNanos - last) / 1e9 * perSecond);
        last = nowNanos;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
