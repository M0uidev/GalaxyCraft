package dev.moui.galaxycraft.net;

/** What the host accepts of a guest's block use before it runs anything: a bad peer cannot aim the shadow at nonsense. */
public final class UseCheck {
    private UseCheck() {}

    /** hit: where on the cell the click landed, in the cell's own space (a little slack for the face's edge). */
    public static boolean valid(int cell, int cellCount, int face, int hand, float hx, float hy, float hz) {
        return cell >= 0 && cell < cellCount && face >= 0 && face < 6 && (hand == 0 || hand == 1)
                && inside(hx) && inside(hy) && inside(hz);
    }

    private static boolean inside(float v) {
        return v >= -0.5f && v <= 1.5f; // false for NaN too
    }
}
