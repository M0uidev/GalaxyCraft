package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.ship.ShipCargo;
import dev.moui.galaxycraft.ship.ShipCargoMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * A ship's hold: the four bays on top (a chest goes in and comes out by dragging it), a "view" button under
 * each to choose the chest shown, Join / Split between the bays that can, then that chest's rows and the
 * player's inventory.
 */
public final class ShipCargoScreen extends AbstractContainerScreen<ShipCargoMenu> {
    private static final int PANEL = 0xFFC6C6C6, DARK = 0xFF373737, LIGHT = 0xFFFFFFFF, SLOT = 0xFF8B8B8B, LOCKED = 0xFF4A4A4A,
            SELECTED = 0xFF55FF55, TEXT = 0xFF404040, JOINED = 0xFFB07A30;
    private final Button[] view = new Button[ShipCargo.BAYS];
    private final Button[] join = new Button[2];

    public ShipCargoScreen(ShipCargoMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, 176, ShipCargoMenu.IMAGE_HEIGHT);
        inventoryLabelY = ShipCargoMenu.INV_Y - 11;
        titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            final int bay = i;
            view[i] = Button.builder(Component.translatable("screen.galaxycraft.ship_cargo.view"), b -> click(ShipCargoMenu.B_SELECT + bay))
                    .bounds(leftPos + ShipCargoMenu.BAY_X + i * ShipCargoMenu.BAY_STEP - 4, topPos + ShipCargoMenu.BAY_Y + 20, 26, 12).build();
            addRenderableWidget(view[i]);
        }
        for (int j = 0; j < 2; j++) {
            final int pair = j;
            join[j] = Button.builder(Component.literal("+"), b -> click((menu.bay(pair * 2) == ShipCargo.Bay.LARGE_HEAD
                    ? ShipCargoMenu.B_SPLIT : ShipCargoMenu.B_JOIN) + pair))
                    .bounds(leftPos + ShipCargoMenu.BAY_X + pair * 2 * ShipCargoMenu.BAY_STEP + 18 + 1, topPos + ShipCargoMenu.BAY_Y + 4, 9, 10).build();
            addRenderableWidget(join[j]);
        }
    }

    private void click(int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    @Override
    protected void containerTick() {
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            ShipCargo.Bay b = menu.bay(i);
            view[i].visible = b != ShipCargo.Bay.EMPTY && b != ShipCargo.Bay.LARGE_TAIL;
            view[i].active = menu.selectedBay() != i && !(b == ShipCargo.Bay.LARGE_HEAD && menu.selectedBay() == i + 1);
        }
        for (int j = 0; j < 2; j++) {
            int head = j * 2;
            boolean large = menu.bay(head) == ShipCargo.Bay.LARGE_HEAD;
            boolean canJoin = menu.bay(head) == ShipCargo.Bay.SMALL && menu.bay(head + 1) == ShipCargo.Bay.SMALL;
            join[j].visible = large || canJoin;
            join[j].setMessage(Component.literal(large ? "-" : "+"));
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        frame(g, leftPos, topPos, imageWidth, imageHeight, PANEL);
        // The bays: locked ones dark, a large chest's two bays joined by a bar.
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            int x = leftPos + ShipCargoMenu.BAY_X + i * ShipCargoMenu.BAY_STEP, y = topPos + ShipCargoMenu.BAY_Y;
            boolean locked = i >= menu.capacity();
            frame(g, x - 1, y - 1, 18, 18, locked ? LOCKED : SLOT);
            if (menu.bay(i) == ShipCargo.Bay.LARGE_HEAD) g.fill(x + 17, y + 6, x + 29, y + 10, JOINED);
            if (i == menu.selectedBay() && menu.bay(i) != ShipCargo.Bay.EMPTY) g.outline(x - 2, y - 2, 20, 20, SELECTED);
            else if (menu.bay(i) == ShipCargo.Bay.LARGE_TAIL && menu.selectedBay() == i - 1) g.outline(x - 2, y - 2, 20, 20, SELECTED);
        }
        for (Slot s : menu.slots) {
            if (s.index < ShipCargoMenu.CONTENT_START) continue;
            if (s.index < ShipCargoMenu.CONTENT_END && !s.isActive()) continue;
            frame(g, leftPos + s.x - 1, topPos + s.y - 1, 18, 18, SLOT);
        }
    }

    /** A Minecraft-looking raised or sunken box: light top-left edge, dark bottom-right. */
    private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
        if (fill == PANEL) {
            g.fill(x, y, x + w, y + h, DARK);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, LIGHT);
            g.fill(x + 2, y + 2, x + w - 1, y + h - 1, DARK);
            g.fill(x + 2, y + 2, x + w - 2, y + h - 2, PANEL);
        } else {
            g.fill(x, y, x + w, y + h, fill);
            g.fill(x, y, x + w - 1, y + 1, DARK);
            g.fill(x, y, x + 1, y + h - 1, DARK);
            g.fill(x + w - 1, y + 1, x + w, y + h, LIGHT);
            g.fill(x + 1, y + h - 1, x + w, y + h, LIGHT);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, titleLabelX + 8, titleLabelY, TEXT, false);
        g.text(font, playerInventoryTitle, inventoryLabelX + 8, inventoryLabelY, TEXT, false);
    }
}
