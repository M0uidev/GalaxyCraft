package dev.moui.galaxycraft.input;

import java.util.ArrayList;
import java.util.List;

/** Turns two InputState snapshots into the key and mouse-button events between them. */
public final class InputDiff {
    public record KeyEvent(int code, boolean down) {}

    private InputDiff() {}

    /** keys are bitmaps indexed by SDL scancode; events come out in ascending key order. */
    public static List<KeyEvent> keys(byte[] prev, byte[] cur) {
        List<KeyEvent> out = new ArrayList<>();
        for (int i = 0; i < Math.min(prev.length, cur.length); i++) {
            int changed = (prev[i] ^ cur[i]) & 0xFF;
            for (int bit = 0; changed != 0 && bit < 8; bit++) {
                if ((changed & (1 << bit)) != 0) out.add(new KeyEvent(i * 8 + bit, (cur[i] & (1 << bit)) != 0));
            }
        }
        return out;
    }

    /** Button masks: bit n = SDL mouse button n (1 left, 2 middle, 3 right). */
    public static List<KeyEvent> buttons(int prev, int cur) {
        List<KeyEvent> out = new ArrayList<>();
        for (int b = 0; b < 8; b++) {
            if (((prev ^ cur) & (1 << b)) != 0) out.add(new KeyEvent(b, (cur & (1 << b)) != 0));
        }
        return out;
    }
}
