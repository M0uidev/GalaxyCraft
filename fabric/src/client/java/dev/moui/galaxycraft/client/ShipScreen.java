package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.ship.ShipCargo;
import dev.moui.galaxycraft.ship.ShipMenu;
import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.ShipStats;
import dev.moui.galaxycraft.ship.ShipThrust;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * A ship's own screen, drawn from the sprites in textures/gui/sprites/ship (tools/ship_ui_textures.py), one texture
 * pixel to one screen pixel. Two tabs over one frame: the parts (four sockets for the hull, engine, rudder and cabin
 * modules, with what they make the ship do) and the hold (the bays chests go in, the chest being looked at, three
 * rows a page). The buttons are the screen's own: sprites with hit boxes, no widgets.
 */
public final class ShipScreen extends AbstractContainerScreen<ShipMenu> {
    private static final int LIGHT = 0xFFD6DCEE, LABEL = 0xFFA8B0CC, VALUE = 0xFFFFD666, DIM = 0xFF7A82A0;
    private static final int TAB_W = 78, TAB_H = 14, TAB_Y = 15, PLATE_W = 26, PLATE_H = 12;
    /** The page button sits between the grid and the inventory, on the groove. */
    private static final int PAGE_X = 142, PAGE_Y = 125, PAGE_H = 10;

    public ShipScreen(ShipMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, ShipMenu.IMAGE_WIDTH, ShipMenu.IMAGE_HEIGHT);
    }

    private static Identifier sprite(String name) {
        return Identifier.fromNamespaceAndPath("galaxycraft", "ship/" + name);
    }

    private void blit(GuiGraphicsExtractor g, String name, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(name), x, y, w, h);
    }

    // ---- the screen's buttons: where they are, in panel pixels ----

    private int tabX(int tab) {
        return 6 + tab * (TAB_W + 8);
    }

    private int bayX(int i) {
        return ShipMenu.ROW_X + i * ShipMenu.ROW_STEP;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** What the pair of bays whose head is `head` offers: 1 join, -1 split, 0 nothing. */
    private int pairAction(int head) {
        if (menu.bay(head) == ShipCargo.Bay.LARGE_HEAD) return -1;
        return menu.bay(head) == ShipCargo.Bay.SMALL && menu.bay(head + 1) == ShipCargo.Bay.SMALL ? 1 : 0;
    }

    private boolean viewable(int i) {
        ShipCargo.Bay b = menu.bay(i);
        return b == ShipCargo.Bay.SMALL || b == ShipCargo.Bay.LARGE_HEAD;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            double mx = event.x() - leftPos, my = event.y() - topPos;
            for (int t = 0; t < 2; t++)
                if (in(mx, my, tabX(t), TAB_Y, TAB_W, TAB_H) && menu.tab() != t) return press(ShipMenu.B_TAB + t);
            if (menu.tab() == ShipMenu.TAB_HOLD) {
                for (int i = 0; i < ShipCargo.BAYS; i++)
                    if (viewable(i) && in(mx, my, bayX(i) - 4, ShipMenu.ROW_Y + 20, PLATE_W, PLATE_H)) return press(ShipMenu.B_SELECT + i);
                for (int pair = 0; pair < 2; pair++) {
                    int action = pairAction(pair * 2);
                    int x = bayX(pair * 2) + 23, y = ShipMenu.ROW_Y + (action < 0 ? 8 : 4);
                    if (action != 0 && in(mx, my, x, y, 10, 10)) return press((action > 0 ? ShipMenu.B_JOIN : ShipMenu.B_SPLIT) + pair);
                }
                if (menu.pages() > 1 && in(mx, my, PAGE_X, PAGE_Y, PLATE_W, PAGE_H)) return press(ShipMenu.B_PAGE);
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean press(int button) {
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(menu.containerId, button);
        return true;
    }

    // ---- drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        blit(g, "panel", leftPos, topPos, ShipMenu.IMAGE_WIDTH, ShipMenu.IMAGE_HEIGHT);
        double mx = mouseX - leftPos, my = mouseY - topPos;
        for (int t = 0; t < 2; t++) {
            boolean active = menu.tab() == t;
            String name = active ? "tab_active" : in(mx, my, tabX(t), TAB_Y, TAB_W, TAB_H) ? "tab_hover" : "tab";
            blit(g, name, leftPos + tabX(t), topPos + TAB_Y, TAB_W, TAB_H);
        }
        if (menu.tab() == ShipMenu.TAB_HOLD) holdBackground(g, mx, my);
        else partsBackground(g);
        // The inventory's sockets.
        for (int i = ShipMenu.PLAYER_START; i < ShipMenu.PLAYER_END; i++) {
            Slot s = menu.slots.get(i);
            blit(g, "slot", leftPos + s.x - 1, topPos + s.y - 1, 18, 18);
        }
    }

    private void holdBackground(GuiGraphicsExtractor g, double mx, double my) {
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            int x = leftPos + bayX(i) - 1, y = topPos + ShipMenu.ROW_Y - 1;
            blit(g, i >= menu.capacity() ? "slot_locked" : "bay", x, y, 18, 18);
        }
        for (int pair = 0; pair < 2; pair++) {
            int head = pair * 2, action = pairAction(head);
            int x = leftPos + bayX(head);
            if (menu.bay(head) == ShipCargo.Bay.LARGE_HEAD) blit(g, "link", x + 18, topPos + ShipMenu.ROW_Y + 1, 20, 6);
            if (action == 0) continue;
            int by = ShipMenu.ROW_Y + (action < 0 ? 8 : 4);
            boolean over = in(mx, my, bayX(head) + 23, by, 10, 10);
            blit(g, (action > 0 ? "btn_join" : "btn_split") + (over ? "_hover" : ""), x + 23, topPos + by, 10, 10);
        }
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            if (!viewable(i)) continue;
            boolean shown = menu.selectedBay() == i || menu.bay(i) == ShipCargo.Bay.LARGE_HEAD && menu.selectedBay() == i + 1;
            boolean over = in(mx, my, bayX(i) - 4, ShipMenu.ROW_Y + 20, PLATE_W, PLATE_H);
            blit(g, shown ? "plate_active" : over ? "plate_hover" : "plate", leftPos + bayX(i) - 4, topPos + ShipMenu.ROW_Y + 20, PLATE_W, PLATE_H);
            if (shown) {
                int rx = leftPos + bayX(i) - 3, ry = topPos + ShipMenu.ROW_Y - 3;
                blit(g, "bay_selected", rx, ry, 22, 22);
                if (menu.bay(i) == ShipCargo.Bay.LARGE_HEAD) blit(g, "bay_selected", rx + ShipMenu.ROW_STEP, ry, 22, 22);
            }
        }
        if (menu.pages() > 1) {
            boolean over = in(mx, my, PAGE_X, PAGE_Y, PLATE_W, PAGE_H);
            blit(g, over ? "page_hover" : "page", leftPos + PAGE_X, topPos + PAGE_Y, PLATE_W, PAGE_H);
        }
        for (int i = ShipMenu.CONTENT_START; i < ShipMenu.CONTENT_END; i++) {
            Slot s = menu.slots.get(i);
            if (s.isActive()) blit(g, "slot", leftPos + s.x - 1, topPos + s.y - 1, 18, 18);
        }
    }

    private ShipModules modules() {
        int[] t = new int[4];
        for (int p = 0; p < 4; p++) {
            var stack = menu.slots.get(ShipMenu.PART_START + p).getItem();
            t[p] = stack.isEmpty() ? 0 : ShipMenu.tierOf(stack);
        }
        return new ShipModules(t[0], t[1], t[2], t[3]);
    }

    private void partsBackground(GuiGraphicsExtractor g) {
        ShipModules m = modules();
        int[] tiers = {m.hull(), m.engine(), m.rudder(), m.cabin()};
        for (int p = 0; p < 4; p++) {
            int x = leftPos + bayX(p);
            blit(g, "bay", x - 1, topPos + ShipMenu.ROW_Y - 1, 18, 18);
            for (int k = 0; k < 3; k++) blit(g, k < tiers[p] ? "hud_pip_on" : "hud_pip_off", x - 1 + k * 7, topPos + ShipMenu.ROW_Y + 21, 6, 6);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.centeredText(font, title, ShipMenu.IMAGE_WIDTH / 2, 4, 0xFFFFFFFF);
        for (int t = 0; t < 2; t++) {
            Component name = Component.translatable(t == ShipMenu.TAB_PARTS ? "screen.galaxycraft.ship.tab_parts" : "screen.galaxycraft.ship.tab_hold");
            g.centeredText(font, name, tabX(t) + TAB_W / 2, TAB_Y + (menu.tab() == t ? 3 : 4), menu.tab() == t ? 0xFFFFFFFF : LABEL);
        }
        g.text(font, playerInventoryTitle, 8, 131, LABEL, false);
        if (menu.tab() == ShipMenu.TAB_HOLD) holdLabels(g);
        else partsLabels(g);
    }

    private void holdLabels(GuiGraphicsExtractor g) {
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            if (!viewable(i)) continue;
            Component v = Component.translatable("screen.galaxycraft.ship.view");
            g.centeredText(font, v, bayX(i) - 4 + PLATE_W / 2, ShipMenu.ROW_Y + 22, LIGHT);
        }
        if (menu.pages() > 1)
            g.centeredText(font, Component.translatable("screen.galaxycraft.ship.page", menu.page() + 1, menu.pages()),
                    PAGE_X + PLATE_W / 2, PAGE_Y + 1, LIGHT);
        if (menu.shownSize() == 0)
            g.centeredText(font, Component.translatable("screen.galaxycraft.ship.no_chest"), ShipMenu.IMAGE_WIDTH / 2, 92, DIM);
    }

    private void partsLabels(GuiGraphicsExtractor g) {
        ShipModules m = modules();
        ShipStats s = ShipStats.of(m);
        String[] keys = {"hull", "engine", "rudder", "cabin"};
        for (int p = 0; p < 4; p++)
            g.centeredText(font, Component.translatable("screen.galaxycraft.ship.part." + keys[p]), bayX(p) + 8, ShipMenu.ROW_Y + 30, LIGHT);
        String[][] rows = {
            {"screen.galaxycraft.ship.stat.speed", String.format("%.1f", ShipThrust.topSpeed(s) * 20), String.format("%.1f", ShipThrust.turboSpeed(s) * 20)},
            {"screen.galaxycraft.ship.stat.turn", String.format("%.2f", s.turn()), null},
            {"screen.galaxycraft.ship.stat.hp", Integer.toString(s.durability()), null},
            {"screen.galaxycraft.ship.stat.seats", Integer.toString(s.seats()), null},
            {"screen.galaxycraft.ship.stat.hold", Integer.toString(ShipCargo.capacity(m.cabin())), null},
        };
        int y = 74;
        for (String[] r : rows) {
            g.text(font, Component.translatable(r[0]), 12, y, LABEL, false);
            String value = r[2] == null ? r[1] : Component.translatable("screen.galaxycraft.ship.stat.speed_value", r[1], r[2]).getString();
            g.text(font, value, ShipMenu.IMAGE_WIDTH - 12 - font.width(value), y, VALUE, false);
            y += 10;
        }
    }
}
