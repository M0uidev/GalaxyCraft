package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.ship.ShipHudModel;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The flight panel of a boarded Toad ship, over the game's HUD (not a screen: nothing grabs the mouse): a red title
 * bar like a Toad's cap, the speed gauge with the top-speed mark and the turbo's range, the modules as pips, and the
 * keys. Dressed like the Toad dialogue box. ShipClient hands it the frame's {@link ShipHudModel}.
 */
public final class ShipHud {
    private static final int W = 184, BORDER = 0xFFE8E8F0, BACK = 0xD0101018, RED = 0xFFD8282F, DIM = 0xFFB8B8C8, WHITE = 0xFFFFFFFF,
            GOLD = 0xFFFFE680, GAUGE_BACK = 0xFF2A2A38, GAUGE = 0xFFE8E8F0, TURBO = 0xFFFFA030, LINE = 11;
    private static volatile ShipHudModel view;
    private static volatile String status;

    private ShipHud() {}

    /** The frame's model, or null when nobody is piloting; `dockStatus` is a line such as "Docked" or null. */
    public static void show(ShipHudModel model, String dockStatus) {
        view = model;
        status = dockStatus;
    }

    static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.parse("galaxycraft:ship_hud"), (g, delta) -> draw(g));
    }

    private static void draw(GuiGraphicsExtractor g) {
        ShipHudModel v = view;
        Minecraft mc = Minecraft.getInstance();
        if (v == null) return;
        Font font = mc.font;
        String dock = status;
        int h = 16 + 30 + 4 * LINE + 6 + (dock != null ? LINE : 0) + 2 * LINE;
        int x = g.guiWidth() - W - 8, y = g.guiHeight() - h - 40;
        g.fill(x, y, x + W, y + h, BACK);
        g.outline(x, y, W, h, BORDER);
        // Title bar: the cap, with its spots.
        g.fill(x + 1, y + 1, x + W - 1, y + 14, RED);
        g.fill(x + 6, y + 4, x + 12, y + 10, BORDER); // the cap's spots, at the ends so the name stays clear
        g.fill(x + W - 12, y + 4, x + W - 6, y + 10, BORDER);
        g.text(font, Component.translatable("hud.galaxycraft.ship.title"), x + 6 + (W - 12 - font.width(Component.translatable("hud.galaxycraft.ship.title"))) / 2, y + 4, WHITE);
        // Speed: the number, then the gauge (the mark is the top speed; past it, the turbo).
        int gy = y + 18;
        String speed = String.format("%.1f", v.speed());
        g.text(font, speed, x + 6, gy, v.boosting() ? TURBO : WHITE);
        g.text(font, Component.translatable("hud.galaxycraft.ship.unit"), x + 8 + font.width(speed), gy, DIM);
        if (v.boosting()) g.text(font, Component.translatable("hud.galaxycraft.ship.turbo"), x + W - 6 - font.width(Component.translatable("hud.galaxycraft.ship.turbo")), gy, TURBO);
        int bx = x + 6, bw = W - 12, by = gy + 12;
        g.fill(bx, by, bx + bw, by + 7, GAUGE_BACK);
        int filled = (int) Math.round(bw * v.fraction()), mark = (int) Math.round(bw * v.topMark());
        g.fill(bx, by, bx + Math.min(filled, mark), by + 7, GAUGE);
        if (filled > mark) g.fill(bx + mark, by, bx + filled, by + 7, TURBO);
        g.fill(bx + mark, by - 2, bx + mark + 1, by + 9, GOLD);
        // Modules as pips (tier of three), then the numbers that follow from them.
        int ry = by + 14;
        row(g, font, x, ry, "hud.galaxycraft.ship.engine", v.modules().engine());
        row(g, font, x, ry + LINE, "hud.galaxycraft.ship.rudder", v.modules().rudder());
        row(g, font, x, ry + 2 * LINE, "hud.galaxycraft.ship.hull", v.modules().hull());
        row(g, font, x, ry + 3 * LINE, "hud.galaxycraft.ship.cabin", v.modules().cabin());
        int iy = ry + 4 * LINE + 4;
        g.fill(x + 6, iy - 3, x + W - 6, iy - 2, 0xFF404058);
        String info = Component.translatable("hud.galaxycraft.ship.info", v.seats(), v.durability(), v.chests(), v.bays()).getString();
        g.text(font, info, x + 6, iy, DIM);
        int ky = iy + LINE;
        if (dock != null) {
            g.text(font, dock, x + 6, ky, GOLD);
            ky += LINE;
        }
        g.text(font, Component.translatable("hud.galaxycraft.ship.keys"), x + 6, ky, 0xFF8888A0);
    }

    private static void row(GuiGraphicsExtractor g, Font font, int x, int y, String key, int tier) {
        g.text(font, Component.translatable(key), x + 6, y, DIM);
        for (int i = 0; i < 3; i++) {
            int px = x + W - 6 - 3 * 10 + i * 10;
            g.fill(px, y + 1, px + 8, y + 8, i < tier ? RED : GAUGE_BACK);
            if (i < tier) g.fill(px, y + 1, px + 8, y + 2, 0xFFFF7078);
        }
    }
}
