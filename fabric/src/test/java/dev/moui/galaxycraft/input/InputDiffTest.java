package dev.moui.galaxycraft.input;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class InputDiffTest {
    static byte[] keys(int... down) {
        byte[] k = new byte[64];
        for (int d : down) k[d / 8] |= (byte) (1 << (d % 8));
        return k;
    }

    @Test void pressAndRelease() {
        assertEquals(List.of(new InputDiff.KeyEvent(69, true)), InputDiff.keys(keys(), keys(69)));
        assertEquals(List.of(new InputDiff.KeyEvent(69, false)), InputDiff.keys(keys(69), keys()));
    }

    @Test void highKeysAndSeveralAtOnce() {
        var events = InputDiff.keys(keys(32, 340), keys(87, 340, 511));
        assertEquals(List.of(new InputDiff.KeyEvent(32, false), new InputDiff.KeyEvent(87, true),
                new InputDiff.KeyEvent(511, true)), events);
    }

    @Test void buttons() {
        assertEquals(List.of(new InputDiff.KeyEvent(0, true), new InputDiff.KeyEvent(1, false)),
                InputDiff.buttons(0b10, 0b01));
    }

    @Test void modifiersFromHeldKeys() {
        assertEquals(0, InputDiff.modifiers(keys(4, 42)));
        assertEquals(0x1 | 0x80, InputDiff.modifiers(keys(225, 228)));  // left Shift, right Ctrl
        assertEquals(0x2 | 0x40 | 0x100 | 0x800, InputDiff.modifiers(keys(229, 224, 226, 231)));
    }
}
