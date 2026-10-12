package dev.moui.galaxycraft.client;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;

/**
 * The box a Toad talks in, drawn over the game's HUD (not a screen: nothing grabs the mouse, the
 * player goes on moving). Reads what ToadClient made ready for the frame.
 */
final class ToadDialogueHud {
    /** What to draw: filled by ToadClient each frame, null when nobody is talking. */
    record View(String name, int cap, String text, int visible, boolean typing, boolean choosing, List<String> choices, int highlight) {}

    private static volatile View view;
    private static final int MAX_W = 420, PORTRAIT = 36, LINE = 11;

    private ToadDialogueHud() {}

    static void show(View v) {
        view = v;
    }

    static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.parse("galaxycraft:toad_dialogue"),
                (g, delta) -> draw(g));
    }

    private static void draw(GuiGraphicsExtractor g) {
        View v = view;
        Minecraft mc = Minecraft.getInstance();
        if (v == null) return;
        Font font = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();
        int w = Math.min(MAX_W, sw * 3 / 4), x = (sw - w) / 2;
        int textX = x + PORTRAIT + 14, textW = w - PORTRAIT - 24;
        List<FormattedText> lines = font.splitIgnoringLanguage(FormattedText.of(v.text()), textW);
        int rows = v.choosing() ? v.choices().size() : Math.min(4, lines.size());
        int h = Math.max(PORTRAIT + 12, 22 + rows * LINE + 14);
        int y = sh - 62 - h; // above the hotbar, the health and the food bars
        g.fill(x, y, x + w, y + h, 0xD0101018);
        g.fill(x, y, x + w, y + 1, 0xFFE8E8F0);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFE8E8F0);
        g.fill(x, y, x + 1, y + h, 0xFFE8E8F0);
        g.fill(x + w - 1, y, x + w, y + h, 0xFFE8E8F0);
        // Portrait slot: the Toad's cap colour until it has a real portrait.
        int px = x + 8, py = y + 8;
        g.fill(px - 1, py - 1, px + PORTRAIT + 1, py + PORTRAIT + 1, 0xFFE8E8F0);
        g.fill(px, py, px + PORTRAIT, py + PORTRAIT, 0xFF000000 | (v.cap() >>> 8));
        g.text(font, v.name(), textX, y + 6, 0xFFFFE680);
        if (v.choosing()) {
            for (int i = 0; i < v.choices().size(); i++) {
                boolean on = i == v.highlight();
                g.text(font, (on ? "> " : "  ") + (i + 1) + ". " + v.choices().get(i), textX, y + 20 + i * LINE, on ? 0xFFFFFFFF : 0xFFB8B8C8);
            }
            g.text(font, Component.translatable("toad.hud.pick"), textX, y + h - 12, 0xFF7C7C90);
            return;
        }
        int left = v.visible();
        for (int i = 0; i < rows && left > 0; i++) {
            String line = lines.get(i).getString();
            int n = Math.min(left, line.length());
            g.text(font, line.substring(0, n), textX, y + 20 + i * LINE, 0xFFFFFFFF);
            left -= line.length();
        }
        if (!v.typing()) g.text(font, Component.translatable("toad.hud.next"), x + w - 8 - font.width(Component.translatable("toad.hud.next")),
                y + h - 12, 0xFF7C7C90);
    }
}
