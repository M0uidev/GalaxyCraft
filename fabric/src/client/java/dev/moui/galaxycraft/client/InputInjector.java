package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.input.InputDiff;
import dev.moui.galaxycraft.proto.Seqlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Feeds the host's keyboard and mouse (Dolphin's window) into Minecraft's own input handlers. */
final class InputInjector {
    private static final int PRESS = 1, RELEASE = 0;
    private Seqlock.InputState prev;

    void apply(Minecraft mc, Seqlock.InputState cur) {
        if (prev == null) { // first snapshot is the baseline: nothing has changed yet
            prev = cur;
            return;
        }
        long window = mc.getWindow().handle();
        for (InputDiff.KeyEvent e : InputDiff.keys(prev.keys(), cur.keys())) {
            mc.keyboardHandler.keyPress(window, e.down() ? PRESS : RELEASE, new KeyEvent(e.code(), 0, 0));
        }
        for (InputDiff.KeyEvent e : InputDiff.buttons(prev.buttons(), cur.buttons())) {
            mc.mouseHandler.onButton(window, new MouseButtonInfo(e.code(), 0), e.down() ? PRESS : RELEASE);
        }
        double wheel = cur.wheel() - prev.wheel();
        if (wheel != 0) mc.mouseHandler.onScroll(window, 0, wheel);
        double dx = cur.mouseX() - prev.mouseX(), dy = cur.mouseY() - prev.mouseY();
        if (dx != 0 || dy != 0) {
            mc.mouseHandler.onMove(window, mc.mouseHandler.xpos() + dx, mc.mouseHandler.ypos() + dy, dx, dy);
        }
        prev = cur;
    }

    void reset() {
        prev = null;
    }
}
