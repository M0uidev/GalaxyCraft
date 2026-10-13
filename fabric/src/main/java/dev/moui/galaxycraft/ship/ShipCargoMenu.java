package dev.moui.galaxycraft.ship;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A ship's hold as a Minecraft menu: four bay slots (chests only, one each), the chest being looked at
 * (3 or 6 rows) and the player's inventory. The server's menu edits a {@link ShipCargo}; the client's is
 * a shell that Minecraft fills in by sync, and the bay states, the room and the chest shown travel as
 * {@link ContainerData}.
 */
public final class ShipCargoMenu extends AbstractContainerMenu {
    public static ExtendedMenuType<ShipCargoMenu, String> TYPE;

    public static final int BAY_START = 0, BAY_END = 4;
    public static final int CONTENT_START = 4, CONTENT_END = 4 + ShipCargo.LARGE;
    public static final int PLAYER_START = CONTENT_END, PLAYER_END = PLAYER_START + 36;
    /** Slot positions, shared with the screen (pixels in its 176-wide frame). */
    public static final int BAY_X = 29, BAY_STEP = 28, BAY_Y = 22, CONTENT_X = 8, CONTENT_Y = 56, INV_Y = 176;
    public static final int IMAGE_HEIGHT = 176 + 58 + 8;
    /** ContainerData: 0..3 the bays' {@link ShipCargo.Bay#ordinal()}, then these. */
    public static final int D_SELECTED = 4, D_CAPACITY = 5, D_SIZE = 6, D_COUNT = 7;
    /** Menu buttons: select a bay's chest, join, split. */
    public static final int B_SELECT = 0, B_JOIN = 4, B_SPLIT = 6;

    private final ShipCargo cargo; // null in the client's shell
    private final Runnable changed;
    private final ContainerData data;
    private int selected;

    /** The client's shell. */
    public ShipCargoMenu(int id, Inventory inv) {
        this(id, inv, null, () -> {});
    }

    /** The server's menu over a hold; `changed` runs after any edit (the caller saves). */
    public ShipCargoMenu(int id, Inventory inv, ShipCargo cargo, Runnable changed) {
        super(TYPE, id);
        this.cargo = cargo;
        this.changed = changed;
        this.data = cargo == null ? new SimpleContainerData(D_COUNT) : new ServerData();
        Container bays = cargo == null ? new SimpleContainer(ShipCargo.BAYS) : new BayView();
        Container view = cargo == null ? new SimpleContainer(ShipCargo.LARGE) : new CargoView();
        for (int i = 0; i < ShipCargo.BAYS; i++) addSlot(new BaySlot(bays, i, BAY_X + i * BAY_STEP, BAY_Y));
        for (int i = 0; i < ShipCargo.LARGE; i++)
            addSlot(new ContentSlot(view, i, CONTENT_X + (i % 9) * 18, CONTENT_Y + (i / 9) * 18));
        addStandardInventorySlots(inv, CONTENT_X, INV_Y);
        addDataSlots(data);
        if (cargo != null) selected = firstChest();
    }

    public static void register() {
        if (TYPE != null) return;
        TYPE = Registry.register(BuiltInRegistries.MENU, Identifier.fromNamespaceAndPath("galaxycraft", "ship_cargo"),
                new ExtendedMenuType<>((id, inv, shipId) -> new ShipCargoMenu(id, inv), ByteBufCodecs.STRING_UTF8));
    }

    /** Opens the hold of a ship for a player (server thread). */
    public static void open(ServerPlayer player, String shipId, ShipCargo cargo, Runnable changed) {
        player.openMenu(new ExtendedMenuProvider<String>() {
            @Override
            public String getScreenOpeningData(ServerPlayer p) {
                return shipId;
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("screen.galaxycraft.ship_cargo.title");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ShipCargoMenu(id, inv, cargo, changed);
            }
        });
    }

    // ---- what both sides read, from the synced data ----

    public ShipCargo.Bay bay(int i) {
        return ShipCargo.Bay.values()[Math.max(0, Math.min(ShipCargo.Bay.values().length - 1, data.get(i)))];
    }

    public int capacity() {
        return data.get(D_CAPACITY);
    }

    public int selectedBay() {
        return data.get(D_SELECTED);
    }

    /** Slots of the chest being looked at: 0 (none), 27 or 54. */
    public int shownSize() {
        return data.get(D_SIZE);
    }

    private int firstChest() {
        for (int i = 0; i < ShipCargo.BAYS; i++) if (cargo.bay(i) != ShipCargo.Bay.EMPTY) return i;
        return 0;
    }

    private ShipCargo.Chest shown() {
        return cargo == null ? null : cargo.chest(selected);
    }

    // ---- buttons ----

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (cargo == null) return false;
        if (id >= B_SELECT && id < B_SELECT + ShipCargo.BAYS) {
            if (cargo.bay(id) == ShipCargo.Bay.EMPTY) return false;
            selected = id;
            return true;
        }
        int head = id >= B_SPLIT ? (id - B_SPLIT) * 2 : (id - B_JOIN) * 2;
        if (id >= B_JOIN && id < B_SPLIT && cargo.canJoin(head)) {
            cargo.join(head);
            selected = head;
        } else if (id >= B_SPLIT && id < B_SPLIT + 2 && cargo.canSplit(head)) {
            cargo.split(head);
            selected = head;
        } else {
            return false;
        }
        changed.run();
        return true;
    }

    // ---- shift-click ----

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (cargo == null || index < 0 || index >= slots.size()) return ItemStack.EMPTY; // the server answers; the client waits for the sync
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), before = stack.copy();
        if (index < CONTENT_END) {
            if (index < BAY_END && !slot.mayPickup(player)) return ItemStack.EMPTY;
            if (!moveItemStackTo(stack, PLAYER_START, PLAYER_END, true)) return ItemStack.EMPTY;
        } else {
            int bay = stack.is(Items.CHEST) ? freeBay() : -1;
            if (bay >= 0) {
                cargo.install(bay);
                stack.shrink(1);
                selected = bay;
                changed.run();
                slot.setChanged();
                return ItemStack.EMPTY; // one chest per shift-click: Minecraft repeats the move until we answer empty
            } else if (!moveItemStackTo(stack, CONTENT_START, CONTENT_END, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    private int freeBay() {
        for (int i = 0; i < ShipCargo.BAYS; i++) if (cargo.canInstall(i)) return i;
        return -1;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (cargo != null) changed.run();
    }

    // ---- the server's views over the hold ----

    private final class ServerData implements ContainerData {
        @Override
        public int get(int i) {
            if (i < ShipCargo.BAYS) return cargo.bay(i).ordinal();
            return switch (i) {
                case D_SELECTED -> selected;
                case D_CAPACITY -> cargo.capacity();
                case D_SIZE -> shown() == null ? 0 : shown().size();
                default -> 0;
            };
        }

        @Override
        public void set(int i, int v) {}

        @Override
        public int getCount() {
            return D_COUNT;
        }
    }

    /** The four bay slots: a bay with a chest shows a chest item; putting one in installs, taking it out removes (if empty). */
    private final class BayView implements Container {
        @Override
        public int getContainerSize() {
            return ShipCargo.BAYS;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < ShipCargo.BAYS; i++) if (cargo.bay(i) != ShipCargo.Bay.EMPTY) return false;
            return true;
        }

        @Override
        public ItemStack getItem(int i) {
            return cargo.bay(i) == ShipCargo.Bay.EMPTY ? ItemStack.EMPTY : new ItemStack(Items.CHEST);
        }

        @Override
        public ItemStack removeItem(int i, int n) {
            return removeItemNoUpdate(i);
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) {
            if (!cargo.canRemove(i)) return ItemStack.EMPTY;
            cargo.remove(i);
            changed.run();
            return new ItemStack(Items.CHEST);
        }

        @Override
        public void setItem(int i, ItemStack stack) {
            if (stack.isEmpty()) {
                if (cargo.canRemove(i)) {
                    cargo.remove(i);
                    changed.run();
                }
            } else if (stack.is(Items.CHEST) && cargo.canInstall(i)) {
                cargo.install(i);
                selected = i;
                changed.run();
            }
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {}
    }

    /** The 54 content slots over whichever chest is shown; past its size they read as empty. */
    private final class CargoView implements Container {
        @Override
        public int getContainerSize() {
            return ShipCargo.LARGE;
        }

        @Override
        public boolean isEmpty() {
            return shown() == null || shown().isEmpty();
        }

        @Override
        public ItemStack getItem(int i) {
            ShipCargo.Chest c = shown();
            return c == null || i >= c.size() ? ItemStack.EMPTY : c.items().get(i);
        }

        @Override
        public ItemStack removeItem(int i, int n) {
            ShipCargo.Chest c = shown();
            if (c == null || i >= c.size() || n <= 0) return ItemStack.EMPTY;
            ItemStack out = c.items().get(i).split(n);
            if (!out.isEmpty()) changed.run();
            return out;
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) {
            ShipCargo.Chest c = shown();
            if (c == null || i >= c.size()) return ItemStack.EMPTY;
            ItemStack out = c.items().get(i);
            c.items().set(i, ItemStack.EMPTY);
            changed.run();
            return out;
        }

        @Override
        public void setItem(int i, ItemStack stack) {
            ShipCargo.Chest c = shown();
            if (c == null || i >= c.size()) return;
            c.items().set(i, stack);
            changed.run();
        }

        @Override
        public void setChanged() {
            changed.run();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {}
    }

    // ---- slots (both sides) ----

    /** A bay: holds one chest item. What can go in or out is decided from the synced bay states (the server re-checks). */
    private final class BaySlot extends Slot {
        private final int bay;

        BaySlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
            this.bay = i;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(Items.CHEST) && bay(bay) == ShipCargo.Bay.EMPTY && bay < capacity();
        }

        @Override
        public boolean mayPickup(Player player) {
            if (bay(bay) != ShipCargo.Bay.SMALL) return false; // a large chest splits first
            return cargo == null || cargo.canRemove(bay);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }
    }

    /** A content slot: live only while the chest shown is big enough to have it. */
    private final class ContentSlot extends Slot {
        private final int n;

        ContentSlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
            this.n = i;
        }

        @Override
        public boolean isActive() {
            return n < shownSize();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive();
        }

        @Override
        public boolean mayPickup(Player player) {
            return isActive();
        }
    }
}
