package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.ship.ShipHudModel;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The flight panel of a boarded Toad ship, over the game's HUD (not a screen: nothing grabs the mouse). Kept small:
 * a plate in the bottom right corner with the speed and its gauge (the gold mark is the top speed, past it the turbo).
 * Holding the "full panel" key (I) opens a second plate above it with the modules as pips and the numbers. Sprites
 * from textures/gui/sprites/ship (tools/ship_ui_textures.py), drawn 1:1.
 */
public final class ShipHud {
    private static final int W = 92, H = 22, DW = 100, DH = 68, MARGIN = 4;
    private static final int WHITE = 0xFFFFFFFF, DIM = 0xFFA8B0CC, GOLD = 0xFFFFD666, TURBO = 0xFFFFA030, GAUGE = 0xFFD6DCEE;
    private static volatile ShipHudModel view;
    private static volatile String status;
    /** Tests: show the full panel without the key held. */
    public static volatile boolean fullForTest;

    private ShipHud() {}

    /** The frame's model, or null when nobody is piloting; `dockStatus` is a word such as "DOCKED" or null. */
    public static void show(ShipHudModel model, String dockStatus) {
        view = model;
        status = dockStatus;
    }

    static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.parse("galaxycraft:ship_hud"), (g, delta) -> draw(g));
    }

    private static void sprite(GuiGraphicsExtractor g, String name, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("galaxycraft", "ship/" + name), x, y, w, h);
    }

    private static void draw(GuiGraphicsExtractor g) {
        ShipHudModel v = view;
        if (v == null) return;
        Font font = Minecraft.getInstance().font;
        int x = g.guiWidth() - W - MARGIN, y = g.guiHeight() - H - MARGIN;
        sprite(g, "hud_plate", x, y, W, H);
        String speed = String.format("%.1f", v.speed());
        g.text(font, speed, x + 9, y + 3, v.boosting() ? TURBO : WHITE, false);
        g.text(font, Component.translatable("hud.galaxycraft.ship.unit"), x + 11 + font.width(speed), y + 3, DIM, false);
        String tag = v.boosting() ? Component.translatable("hud.galaxycraft.ship.turbo").getString() : status;
        if (tag != null) g.text(font, tag, x + W - 5 - font.width(tag), y + 3, v.boosting() ? TURBO : GOLD, false);
        // The gauge: inside the plate's slot (76 x 4).
        int gx = x + 10, gy = y + 14, gw = 76;
        int filled = (int) Math.round(gw * v.fraction()), mark = (int) Math.round(gw * v.topMark());
        g.fill(gx, gy, gx + Math.min(filled, mark), gy + 4, GAUGE);
        if (filled > mark) g.fill(gx + mark, gy, gx + filled, gy + 4, TURBO);
        g.fill(gx + mark, gy - 1, gx + mark + 1, gy + 5, GOLD);
        if (fullForTest || ModKeys.SHIP_INFO.isDown()) detail(g, font, v, x + W - DW, y - DH - 2);
    }

    private static void detail(GuiGraphicsExtractor g, Font font, ShipHudModel v, int x, int y) {
        sprite(g, "hud_detail", x, y, DW, DH);
        String[] keys = {"engine", "rudder", "hull", "cabin"};
        int[] tiers = {v.modules().engine(), v.modules().rudder(), v.modules().hull(), v.modules().cabin()};
        for (int r = 0; r < 4; r++) {
            int ry = y + 5 + r * 10;
            g.text(font, Component.translatable("hud.galaxycraft.ship." + keys[r]), x + 10, ry, DIM, false);
            for (int k = 0; k < 3; k++) sprite(g, k < tiers[r] ? "hud_pip_on" : "hud_pip_off", x + DW - 7 - 20 + k * 7, ry + 1, 6, 6);
        }
        g.text(font, Component.translatable("hud.galaxycraft.ship.seats", v.seats(), v.durability()), x + 10, y + 47, WHITE, false);
        g.text(font, Component.translatable("hud.galaxycraft.ship.hold", v.chests(), v.bays()), x + 10, y + 57, WHITE, false);
    }
}
