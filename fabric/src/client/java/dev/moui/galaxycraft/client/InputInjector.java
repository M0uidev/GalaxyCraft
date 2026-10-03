package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.input.InputDiff;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Seqlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Feeds the host's keyboard and mouse (Dolphin's window) into Minecraft's own input handlers. */
final class InputInjector {
    private static final int PRESS = 1, RELEASE = 0;
    private Seqlock.InputState prev;
    private int textSeen = -1;

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

    /** Characters typed since last time, after the keys (as GLFW's char callback follows its key one). */
    void applyText(Minecraft mc, Seqlock.TextState text) {
        if (textSeen < 0) { // baseline, as for the keys
            textSeen = text.count();
            return;
        }
        int from = Math.max(textSeen, text.count() - Layout.TEXT_RING);
        for (int i = from; i - text.count() < 0; i++) {
            int cp = text.codepoints()[Integer.remainderUnsigned(i, Layout.TEXT_RING)];
            mc.keyboardHandler.charTyped(mc.getWindow().handle(), new CharacterEvent(cp));
        }
        textSeen = text.count();
    }

    /** No key state from the host (it publishes none until a key or the mouse moves). */
    void reset() {
        prev = null;
    }

    /** No text from the host: the next text seen is the baseline. */
    void resetText() {
        textSeen = -1;
    }
}
