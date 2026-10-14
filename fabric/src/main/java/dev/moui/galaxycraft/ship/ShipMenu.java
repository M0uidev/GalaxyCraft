package dev.moui.galaxycraft.ship;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * A ship's own screen as a Minecraft menu, two tabs over one frame: the **parts** (hull, engine, rudder and cabin,
 * swapped for module items from the inventory) and the **hold** (four bay slots for chests, the chest being looked
 * at, three rows a page). The server's menu edits the ship's {@link ShipModules} and {@link ShipCargo}; the client's
 * is a shell that Minecraft fills in by sync, and the tab, the bay states, the room and the chest shown travel as
 * {@link ContainerData}.
 */
public final class ShipMenu extends AbstractContainerMenu {
    public static ExtendedMenuType<ShipMenu, String> TYPE;

    /** Slot ranges: bays, the chest's page, parts, then the player's 36. */
    public static final int BAY_START = 0, BAY_END = 4;
    public static final int CONTENT_START = 4, CONTENT_END = 4 + 27;
    public static final int PART_START = CONTENT_END, PART_END = PART_START + 4;
    public static final int PLAYER_START = PART_END, PLAYER_END = PLAYER_START + 36;
    /** Slot positions, shared with the screen (pixels in its 176 x 220 frame). */
    public static final int ROW_X = 22, ROW_STEP = 38, ROW_Y = 34, CONTENT_X = 8, CONTENT_Y = 70, INV_Y = 138;
    public static final int IMAGE_WIDTH = 176, IMAGE_HEIGHT = 220;
    /** ContainerData: 0..3 the bays' {@link ShipCargo.Bay#ordinal()}, then these. */
    public static final int D_SELECTED = 4, D_CAPACITY = 5, D_SIZE = 6, D_TAB = 7, D_PAGE = 8, D_USED = 9, D_COUNT = 10;
    /** Menu buttons: select a bay's chest, join, split, tab, next page. */
    public static final int B_SELECT = 0, B_JOIN = 4, B_SPLIT = 6, B_TAB = 8, B_PAGE = 10;
    public static final int TAB_PARTS = 0, TAB_HOLD = 1;
    public static final int PAGE = 27;

    private final ShipCargo cargo; // null in the client's shell
    private final Consumer<ShipModules> onModules;
    private final Runnable changed;
    private final ContainerData data;
    private ShipModules modules;
    private int selected, tab = TAB_HOLD, page;

    /** The client's shell. */
    public ShipMenu(int id, Inventory inv) {
        this(id, inv, null, new ShipModules(0, 1, 0, 1), m -> {}, () -> {});
    }

    /** The server's menu over a ship's hold and parts; `changed` runs after any edit, `onModules` with the new parts. */
    public ShipMenu(int id, Inventory inv, ShipCargo cargo, ShipModules modules, Consumer<ShipModules> onModules, Runnable changed) {
        super(TYPE, id);
        this.cargo = cargo;
        this.modules = modules;
        this.onModules = onModules;
        this.changed = changed;
        this.data = cargo == null ? new SimpleContainerData(D_COUNT) : new ServerData();
        Container bays = cargo == null ? new SimpleContainer(ShipCargo.BAYS) : new BayView();
        Container view = cargo == null ? new SimpleContainer(ShipCargo.LARGE) : new CargoView();
        Container parts = cargo == null ? new SimpleContainer(4) : new PartView();
        for (int i = 0; i < ShipCargo.BAYS; i++) addSlot(new BaySlot(bays, i, ROW_X + i * ROW_STEP, ROW_Y));
        for (int i = 0; i < PAGE; i++) addSlot(new ContentSlot(view, i, CONTENT_X + (i % 9) * 18, CONTENT_Y + (i / 9) * 18));
        for (int i = 0; i < 4; i++) addSlot(new PartSlot(parts, i, ROW_X + i * ROW_STEP, ROW_Y));
        addStandardInventorySlots(inv, CONTENT_X, INV_Y);
        addDataSlots(data);
        if (cargo != null) selected = firstChest();
    }

    public static void register() {
        if (TYPE != null) return;
        TYPE = Registry.register(BuiltInRegistries.MENU, Identifier.fromNamespaceAndPath("galaxycraft", "ship"),
                new ExtendedMenuType<>((id, inv, shipId) -> new ShipMenu(id, inv), ByteBufCodecs.STRING_UTF8));
    }

    /** Opens a ship's screen for a player (server thread). */
    public static void open(ServerPlayer player, String shipId, ShipCargo cargo, ShipModules modules, Consumer<ShipModules> onModules,
            Runnable changed) {
        player.openMenu(new ExtendedMenuProvider<String>() {
            @Override
            public String getScreenOpeningData(ServerPlayer p) {
                return shipId;
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("screen.galaxycraft.ship.title");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ShipMenu(id, inv, cargo, modules, onModules, changed);
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

    public int tab() {
        return data.get(D_TAB);
    }

    public int page() {
        return data.get(D_PAGE);
    }

    public int pages() {
        return Math.max(1, shownSize() / PAGE);
    }

    /** Bays from the nose the chests reach: the cabin cannot be swapped for one that opens fewer. */
    public int usedBays() {
        return data.get(D_USED);
    }

    // ---- modules as items ----

    /** The module item of a part (0 hull, 1 engine, 2 rudder, 3 cabin). */
    public static Item partItem(int part) {
        return switch (part) {
            case 0 -> ShipBlocks.HULL;
            case 1 -> ShipBlocks.ENGINE;
            case 2 -> ShipBlocks.RUDDER;
            default -> ShipBlocks.CABIN;
        };
    }

    /** A module item's tier (1 unless its data says otherwise). */
    public static int tierOf(ItemStack s) {
        return ShipTableBlock.tier(s);
    }

    /** A part's module item of this tier; empty for tier 0 (nothing installed). */
    public static ItemStack moduleStack(int part, int tier) {
        if (tier <= 0) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(partItem(part));
        CompoundTag tag = new CompoundTag();
        tag.putInt(ShipTableBlock.TIER, tier);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static int tierOfPart(ShipModules m, int part) {
        return switch (part) {
            case 0 -> m.hull();
            case 1 -> m.engine();
            case 2 -> m.rudder();
            default -> m.cabin();
        };
    }

    private static ShipModules with(ShipModules m, int part, int tier) {
        return new ShipModules(part == 0 ? tier : m.hull(), part == 1 ? tier : m.engine(), part == 2 ? tier : m.rudder(),
                part == 3 ? tier : m.cabin());
    }

    /** Engine and cabin are swapped, never taken out; hull and rudder can go. */
    private static boolean removable(int part) {
        return part == 0 || part == 2;
    }

    private int firstChest() {
        for (int i = 0; i < ShipCargo.BAYS; i++) if (cargo.bay(i) != ShipCargo.Bay.EMPTY) return i;
        return 0;
    }

    private ShipCargo.Chest shown() {
        return cargo == null ? null : cargo.chest(selected);
    }

    private void setPart(int part, int tier) {
        modules = with(modules, part, tier);
        cargo.setCapacity(ShipCargo.capacity(modules.cabin()));
        onModules.accept(modules);
        changed.run();
    }

    // ---- buttons ----

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (cargo == null) return false;
        if (id >= B_TAB && id < B_TAB + 2) {
            tab = id - B_TAB;
            return true;
        }
        if (id == B_PAGE) {
            page = (page + 1) % Math.max(1, (shown() == null ? 0 : shown().size()) / PAGE);
            return true;
        }
        if (id >= B_SELECT && id < B_SELECT + ShipCargo.BAYS) {
            if (cargo.bay(id) == ShipCargo.Bay.EMPTY) return false;
            selected = id;
            page = 0;
            return true;
        }
        int head = id >= B_SPLIT ? (id - B_SPLIT) * 2 : (id - B_JOIN) * 2;
        if (id >= B_JOIN && id < B_SPLIT && cargo.canJoin(head)) {
            cargo.join(head);
        } else if (id >= B_SPLIT && id < B_SPLIT + 2 && cargo.canSplit(head)) {
            cargo.split(head);
        } else {
            return false;
        }
        selected = head;
        page = 0;
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
        if (index >= PART_START && index < PART_END) {
            if (!removable(index - PART_START)) return ItemStack.EMPTY; // swapped, never taken out
            if (!moveItemStackTo(stack, PLAYER_START, PLAYER_END, true)) return ItemStack.EMPTY;
        } else if (index < CONTENT_END) {
            if (index < BAY_END && !slot.mayPickup(player)) return ItemStack.EMPTY;
            if (!moveItemStackTo(stack, PLAYER_START, PLAYER_END, true)) return ItemStack.EMPTY;
        } else {
            int part = tab == TAB_PARTS ? partOf(stack) : -1;
            int bay = tab == TAB_HOLD && stack.is(Items.CHEST) ? freeBay() : -1;
            if (part >= 0) {
                if (!swapIn(player, part, stack)) return ItemStack.EMPTY;
                slot.setChanged();
                return ItemStack.EMPTY; // one module per shift-click: Minecraft repeats the move until we answer empty
            } else if (bay >= 0) {
                cargo.install(bay);
                stack.shrink(1);
                selected = bay;
                page = 0;
                changed.run();
                slot.setChanged();
                return ItemStack.EMPTY; // likewise one chest per shift-click
            } else if (tab != TAB_HOLD || !moveItemStackTo(stack, CONTENT_START, CONTENT_END, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    private static int partOf(ItemStack s) {
        for (int p = 0; p < 4; p++) if (s.is(partItem(p))) return p;
        return -1;
    }

    private boolean cabinFits(int tier) {
        return ShipCargo.capacity(tier) >= usedBaysNow();
    }

    private int usedBaysNow() {
        return cargo == null ? usedBays() : cargo.usedBays();
    }

    /** A module from the inventory into its part; the one it replaces goes back to the player. */
    private boolean swapIn(Player player, int part, ItemStack stack) {
        int tier = tierOf(stack);
        if (part == 3 && !cabinFits(tier)) return false;
        ItemStack old = moduleStack(part, tierOfPart(modules, part));
        setPart(part, tier);
        stack.shrink(1);
        if (!old.isEmpty()) player.getInventory().placeItemBackInInventory(old, net.minecraft.util.Prediction.SERVER_ONLY);
        return true;
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

    // ---- the server's views over the ship ----

    private final class ServerData implements ContainerData {
        @Override
        public int get(int i) {
            if (i < ShipCargo.BAYS) return cargo.bay(i).ordinal();
            return switch (i) {
                case D_SELECTED -> selected;
                case D_CAPACITY -> cargo.capacity();
                case D_SIZE -> shown() == null ? 0 : shown().size();
                case D_TAB -> tab;
                case D_PAGE -> page;
                case D_USED -> cargo.usedBays();
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

    /** The four part slots: each shows its module item; putting a module in swaps it, taking one out (hull, rudder) removes it. */
    private final class PartView implements Container {
        @Override
        public int getContainerSize() {
            return 4;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public ItemStack getItem(int i) {
            return moduleStack(i, tierOfPart(modules, i));
        }

        @Override
        public ItemStack removeItem(int i, int n) {
            return removeItemNoUpdate(i);
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) {
            if (!removable(i) || tierOfPart(modules, i) == 0) return ItemStack.EMPTY;
            ItemStack old = moduleStack(i, tierOfPart(modules, i));
            setPart(i, 0);
            return old;
        }

        @Override
        public void setItem(int i, ItemStack stack) {
            if (stack.isEmpty()) {
                if (removable(i) && tierOfPart(modules, i) != 0) setPart(i, 0);
            } else if (stack.is(partItem(i)) && (i != 3 || cabinFits(tierOf(stack)))) {
                setPart(i, tierOf(stack));
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
                page = 0;
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

    /** The content slots over the page of the chest shown; past its size they read as empty. */
    private final class CargoView implements Container {
        private int at(int i) {
            return page * PAGE + i;
        }

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
            return c == null || at(i) >= c.size() ? ItemStack.EMPTY : c.items().get(at(i));
        }

        @Override
        public ItemStack removeItem(int i, int n) {
            ShipCargo.Chest c = shown();
            if (c == null || at(i) >= c.size() || n <= 0) return ItemStack.EMPTY;
            ItemStack out = c.items().get(at(i)).split(n);
            if (!out.isEmpty()) changed.run();
            return out;
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) {
            ShipCargo.Chest c = shown();
            if (c == null || at(i) >= c.size()) return ItemStack.EMPTY;
            ItemStack out = c.items().get(at(i));
            c.items().set(at(i), ItemStack.EMPTY);
            changed.run();
            return out;
        }

        @Override
        public void setItem(int i, ItemStack stack) {
            ShipCargo.Chest c = shown();
            if (c == null || at(i) >= c.size()) return;
            c.items().set(at(i), stack);
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

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("galaxycraft", "ship/icon_" + name);
    }

    /** A part's slot: only its own module item, one at a time; live on the parts tab. */
    private final class PartSlot extends Slot {
        private final int part;

        PartSlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
            this.part = i;
        }

        @Override
        public boolean isActive() {
            return tab() == TAB_PARTS;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive() && stack.is(partItem(part)) && (part != 3 || ShipCargo.capacity(tierOf(stack)) >= usedBaysNow());
        }

        @Override
        public boolean mayPickup(Player player) {
            return isActive();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }

        @Override
        public Identifier getNoItemIcon() {
            return icon(switch (part) {
                case 0 -> "hull";
                case 1 -> "engine";
                case 2 -> "rudder";
                default -> "cabin";
            });
        }
    }

    /** A bay: holds one chest item; live on the hold tab. What can go in or out is decided from the synced bay states (the server re-checks). */
    private final class BaySlot extends Slot {
        private final int bay;

        BaySlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
            this.bay = i;
        }

        @Override
        public boolean isActive() {
            return tab() == TAB_HOLD;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive() && stack.is(Items.CHEST) && bay(bay) == ShipCargo.Bay.EMPTY && bay < capacity();
        }

        @Override
        public boolean mayPickup(Player player) {
            if (!isActive() || bay(bay) != ShipCargo.Bay.SMALL) return false; // a large chest splits first
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

        @Override
        public Identifier getNoItemIcon() {
            return icon("chest");
        }
    }

    /** A content slot: live only while the chest shown has this one on the page. */
    private final class ContentSlot extends Slot {
        private final int n;

        ContentSlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
            this.n = i;
        }

        @Override
        public boolean isActive() {
            return tab() == TAB_HOLD && page() * PAGE + n < shownSize();
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
