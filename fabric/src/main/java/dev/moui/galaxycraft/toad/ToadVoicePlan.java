package dev.moui.galaxycraft.toad;

/**
 * Which letters of a Toad's text make a sound and how high. The sound itself is the client's
 * (ToadVoice); this is the rule, pure so it is tested. First draft, tuned by ear.
 */
public final class ToadVoicePlan {
    public static final float MIN_PITCH = 0.8f, MAX_PITCH = 1.9f;

    private ToadVoicePlan() {}

    /** Whether the letter at this index of a text is voiced: letters and digits, every second one. */
    public static boolean blip(char c, int index) {
        return Character.isLetterOrDigit(c) && index % 2 == 0;
    }

    /** The pitch of that letter for that type: each type sits higher or lower, letters wander a little. */
    public static float pitch(ToadType type, char c) {
        float base = 1.05f + type.ordinal() * 0.1f;
        float wander = (Character.toLowerCase(c) % 11) * 0.055f;
        return Math.max(MIN_PITCH, Math.min(MAX_PITCH, base + wander));
    }
}
