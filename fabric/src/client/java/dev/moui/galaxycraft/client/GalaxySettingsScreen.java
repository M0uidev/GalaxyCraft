package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.settings.Setting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * GalaxyCraft's settings, laid out as Minecraft's own options screens are: two columns of
 * 150-wide buttons under the title, Done at the bottom. Each setting in {@link GalaxyOptions}
 * gets the widget of its kind (a button that cycles, a slider, a text field a row wide), then
 * each action a button.
 */
public final class GalaxySettingsScreen extends Screen {
    private static final int W = 150, GAP = 10, ROW = 24, TOP = 40, WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0;
    private final Screen parent;
    private final List<Label> labels = new ArrayList<>();
    /** The settings and actions, which scroll when the screen is too short for them (large GUI scale). */
    private final List<AbstractWidget> scrolling = new ArrayList<>();
    private final List<Integer> baseY = new ArrayList<>();
    private int contentBottom, scroll;

    public GalaxySettingsScreen(Screen parent) {
        super(Component.literal("Super Minecraft Galaxy Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        labels.clear();
        scrolling.clear();
        baseY.clear();
        int left = width / 2 - W - GAP / 2, right = width / 2 + GAP / 2;
        int col = 0, y = TOP;
        for (Setting<?> s : GalaxyOptions.SETTINGS.all()) {
            if (s instanceof Setting.Text t) { // a row of its own
                if (col == 1) y += ROW;
                text(t, left, y);
                col = 0;
                y += ROW;
                continue;
            }
            int x = col == 0 ? left : right;
            AbstractWidget w = switch (s) {
                case Setting.Toggle t -> cycle(t, x, y, t::flip);
                case Setting.Choice<?> c -> cycle(c, x, y, c::cycle);
                case Setting.Range r -> new Slider(r, x, y);
                case Setting.Text t -> throw new IllegalStateException(t.key());
            };
            scrollAdd(w);
            if (!s.tooltip().isEmpty()) w.setTooltip(Tooltip.create(Component.literal(s.tooltip())));
            col ^= 1;
            if (col == 0) y += ROW;
        }
        if (col == 1) y += ROW;
        y += ROW / 2;
        col = 0;
        for (GalaxyOptions.Action a : GalaxyOptions.ACTIONS) {
            Button b = scrollAdd(Button.builder(Component.literal(a.label().get()), btn -> {
                a.run().run();
                if (minecraft.gui.screen() == this) rebuildWidgets(); // labels and states may have changed
            }).bounds(col == 0 ? left : right, y, W, 20).build());
            b.active = a.active().getAsBoolean();
            if (!a.tooltip().isEmpty()) b.setTooltip(Tooltip.create(Component.literal(a.tooltip())));
            col ^= 1;
            if (col == 0) y += ROW;
        }
        contentBottom = y;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 27, 200, 20).build());
        applyScroll();
    }

    private <T extends AbstractWidget> T scrollAdd(T w) {
        scrolling.add(w);
        baseY.add(w.getY());
        return addRenderableWidget(w);
    }

    private int viewBottom() {
        return height - 32;
    }

    /** Moves the scrolling widgets by the scroll, hiding the ones that would reach the title or Done. */
    private void applyScroll() {
        scroll = Math.max(0, Math.min(scroll, contentBottom - viewBottom()));
        for (int i = 0; i < scrolling.size(); i++) {
            AbstractWidget w = scrolling.get(i);
            w.setY(baseY.get(i) - scroll);
            w.visible = w.getY() >= TOP - 4 && w.getY() + w.getHeight() <= viewBottom();
        }
        for (Label l : labels) l.dy = l.baseY - scroll;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY) * ROW;
        applyScroll();
        return true;
    }

    /** A button showing "Label: value", which changes it when clicked. */
    private Button cycle(Setting<?> s, int x, int y, Runnable next) {
        return Button.builder(Component.literal(s.label() + ": " + s.display()), b -> {
            next.run();
            b.setMessage(Component.literal(s.label() + ": " + s.display()));
        }).bounds(x, y, W, 20).build();
    }

    /** A text setting: its label, the field, and Apply (it is set then, not at every letter). */
    private void text(Setting.Text t, int x, int y) {
        int labelW = 50, applyW = 50, boxW = 2 * W + GAP - labelW - applyW - 8;
        EditBox box = new EditBox(font, x + labelW, y, boxW, 20, Component.literal(t.label()));
        box.setMaxLength(t.maxLength());
        box.setValue(t.get());
        if (!t.tooltip().isEmpty()) box.setTooltip(Tooltip.create(Component.literal(t.tooltip())));
        scrollAdd(box);
        scrollAdd(Button.builder(Component.literal("Apply"), b -> t.set(box.getValue()))
                .bounds(x + labelW + boxW + 8, y, applyW, 20).build());
        labels.add(new Label(t.label(), x, y + 6));
    }

    private static final class Label {
        final String text;
        final int x, baseY;
        int dy;

        Label(String text, int x, int y) {
            this.text = text;
            this.x = x;
            this.baseY = y;
            this.dy = y;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title.getString(), width / 2, 15, WHITE);
        for (Label l : labels)
            if (l.dy >= TOP && l.dy + 8 <= viewBottom()) g.text(font, l.text, l.x, l.dy, GRAY);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /** A Range setting on a slider. */
    private static final class Slider extends AbstractSliderButton {
        private final Setting.Range range;

        Slider(Setting.Range range, int x, int y) {
            super(x, y, W, 20, Component.empty(), range.fraction());
            this.range = range;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(range.label() + ": " + range.display()));
        }

        @Override
        protected void applyValue() {
            range.setFraction(value);
            value = range.fraction();
        }
    }
}
