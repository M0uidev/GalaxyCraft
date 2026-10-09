package dev.moui.galaxycraft.music;

/**
 * Decides when automatic music may change: the wanted value must stay different for the dwell time,
 * and a change may not come sooner than the cooldown after the last one. A change that is wanted
 * when the cooldown ends is applied then; one the player walked away from is dropped.
 */
public final class SwitchGate<T> {
    private T current, candidate;
    private double candidateSince;
    private double lastSwitch = Double.NEGATIVE_INFINITY;

    /** The value to switch to now, or null to keep what plays. */
    public T update(double now, T wanted, double dwell, double cooldown) {
        if (wanted == null) return null;
        if (current == null) {
            current = wanted;
            candidate = null;
            return wanted;
        }
        if (wanted.equals(current)) {
            candidate = null;
            return null;
        }
        if (!wanted.equals(candidate)) {
            candidate = wanted;
            candidateSince = now;
        }
        if (now - candidateSince >= dwell && now - lastSwitch >= cooldown) {
            current = wanted;
            candidate = null;
            lastSwitch = now;
            return wanted;
        }
        return null;
    }

    /** Seconds since the last switch (or the last pick), infinity if there has been none: the cooldown runs from it. */
    public double secondsSinceSwitch(double now) {
        return now - lastSwitch;
    }

    public T current() {
        return current;
    }

    /** The player chose it: it plays now and starts the cooldown. */
    public void force(T value, double now) {
        current = value;
        candidate = null;
        lastSwitch = now;
    }

    public void reset() {
        current = null;
        candidate = null;
        lastSwitch = Double.NEGATIVE_INFINITY;
    }
}
