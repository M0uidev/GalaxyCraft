package dev.moui.galaxycraft.net;

/** When a player sends its state, and how it packs what it sends. */
public final class StateSampler {
    private final int everyTicks;
    private int count;

    public StateSampler(int everyTicks) {
        this.everyTicks = Math.max(1, everyTicks);
    }

    /** Called once per client tick: true on the first call and then every everyTicks-th. */
    public boolean due() {
        boolean now = count == 0;
        count = (count + 1) % everyTicks;
        return now;
    }

    /** The next call to {@link #due()} is true (a new connection starts with a state at once). */
    public void restart() {
        count = 0;
    }

    public static int flags(boolean sneak, boolean sprint, boolean swim, boolean glide, boolean ground, boolean ship) {
        return (sneak ? Protocol.F_SNEAK : 0) | (sprint ? Protocol.F_SPRINT : 0) | (swim ? Protocol.F_SWIM : 0)
                | (glide ? Protocol.F_GLIDE : 0) | (ground ? Protocol.F_GROUND : 0) | (ship ? Protocol.F_SHIP : 0);
    }

    /**
     * Units per second for one axis: delta units over ticks client ticks (20 a second). Clamped to
     * half of what peers accept, so a warp (a jump of many units) is not sent as an absurd speed
     * that would make every peer drop the state.
     */
    public static float speed(double delta, int ticks) {
        if (ticks <= 0 || Double.isNaN(delta)) return 0f;
        double v = delta / (ticks / 20.0);
        double cap = Protocol.MAX_SPEED / 2.0;
        return (float) Math.max(-cap, Math.min(cap, v));
    }
}
