package dev.moui.galaxycraft.ship;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * A ship's hold: four bays in a row (0 at the nose), the first {@link #capacity} of them unlocked by the
 * cabin. A bay holds a small chest (27 slots); the bays 0+1 or 2+3 can be joined into one large chest
 * (54 slots, a double chest). Rules only: the menu and the screen sit on top of this.
 */
public final class ShipCargo {
    public static final int BAYS = 4;
    public static final int SMALL = 27, LARGE = 54;

    public enum Bay { EMPTY, SMALL, LARGE_HEAD, LARGE_TAIL }

    /** One chest's contents. The stacks are made on first use: a chest nobody has opened costs nothing. */
    public static final class Chest {
        private final int size;
        private List<ItemStack> items;
        /** What a file held, until the first {@link #items()} (the world's registries may not exist yet at load). */
        private JsonArray pending;
        private boolean usedForTest;

        Chest(int size) {
            this.size = size;
        }

        public int size() {
            return size;
        }

        /** The slots, made empty on first call. */
        public List<ItemStack> items() {
            if (items == null) {
                items = new ArrayList<>(size);
                for (int i = 0; i < size; i++) items.add(ItemStack.EMPTY);
                if (pending != null) {
                    CargoCodec.decodeInto(pending, items);
                    pending = null;
                }
            }
            return items;
        }

        public boolean isEmpty() {
            if (usedForTest) return false;
            if (items == null) return pending == null || pending.isEmpty();
            for (ItemStack s : items) if (!s.isEmpty()) return false;
            return true;
        }

        /** Makes {@link #isEmpty} false without an ItemStack (unit tests run without Minecraft's registries). */
        void markUsedForTest() {
            usedForTest = true;
        }

        boolean hasItemsList() {
            return items != null;
        }

        /** The slots as a file keeps them: only the non-empty ones. */
        JsonArray save() {
            if (items == null) return pending == null ? new JsonArray() : pending;
            return CargoCodec.encode(items);
        }

        static Chest loaded(int size, JsonArray pending) {
            Chest c = new Chest(size);
            c.pending = pending == null || pending.isEmpty() ? null : pending;
            return c;
        }
    }

    private int capacity;
    private final Bay[] bays = new Bay[BAYS];
    private final Chest[] chests = new Chest[BAYS];

    public ShipCargo(int capacity) {
        this.capacity = Math.max(0, Math.min(BAYS, capacity));
        java.util.Arrays.fill(bays, Bay.EMPTY);
    }

    /** Bays a cabin of this tier unlocks: 0, 1, 2 and 4 for tiers 0..3. */
    public static int capacity(int cabinTier) {
        return cabinTier <= 0 ? 0 : cabinTier == 1 ? 1 : cabinTier == 2 ? 2 : BAYS;
    }

    public int capacity() {
        return capacity;
    }

    /** The bays a cabin unlocks: never fewer than a chest needs (a chest is not left in a locked bay). */
    public void setCapacity(int wanted) {
        capacity = Math.max(usedBays(), Math.max(0, Math.min(BAYS, wanted)));
    }

    /** How many bays from the nose are needed to reach the last chest (0 for an empty hold). */
    public int usedBays() {
        int used = 0;
        for (int i = 0; i < BAYS; i++) if (bays[i] != Bay.EMPTY) used = i + 1;
        return used;
    }

    private static boolean inRange(int i) {
        return i >= 0 && i < BAYS;
    }

    public Bay bay(int i) {
        return inRange(i) ? bays[i] : Bay.EMPTY;
    }

    public boolean locked(int i) {
        return !inRange(i) || i >= capacity;
    }

    /** The chest a bay shows (the same object for both bays of a large chest), or null for an empty bay. */
    public Chest chest(int i) {
        return inRange(i) ? chests[i] : null;
    }

    public boolean canInstall(int i) {
        return !locked(i) && bays[i] == Bay.EMPTY;
    }

    public void install(int i) {
        if (!canInstall(i)) throw new IllegalStateException("bay " + i + " cannot take a chest");
        bays[i] = Bay.SMALL;
        chests[i] = new Chest(SMALL);
    }

    public boolean canRemove(int i) {
        return inRange(i) && bays[i] == Bay.SMALL && chests[i].isEmpty();
    }

    public void remove(int i) {
        if (!canRemove(i)) throw new IllegalStateException("bay " + i + " cannot lose its chest");
        bays[i] = Bay.EMPTY;
        chests[i] = null;
    }

    /** i is the head bay: 0 (bays 0+1) or 2 (bays 2+3). */
    public boolean canJoin(int i) {
        return (i == 0 || i == 2) && !locked(i + 1) && bays[i] == Bay.SMALL && bays[i + 1] == Bay.SMALL;
    }

    /** The large chest keeps what both small ones held: the head's slots first, then the tail's. */
    public void join(int i) {
        if (!canJoin(i)) throw new IllegalStateException("bays " + i + "+" + (i + 1) + " cannot join");
        Chest head = chests[i], tail = chests[i + 1], large = new Chest(LARGE);
        if (!head.isEmpty() || !tail.isEmpty()) {
            List<ItemStack> to = large.items();
            for (int k = 0; k < SMALL; k++) {
                to.set(k, head.items().get(k));
                to.set(SMALL + k, tail.items().get(k));
            }
        }
        if (head.usedForTest || tail.usedForTest) large.usedForTest = true;
        bays[i] = Bay.LARGE_HEAD;
        bays[i + 1] = Bay.LARGE_TAIL;
        chests[i] = large;
        chests[i + 1] = large;
    }

    /** i is the head bay. Only an empty large chest splits (two empty small ones come out of it). */
    public boolean canSplit(int i) {
        return inRange(i) && bays[i] == Bay.LARGE_HEAD && chests[i].isEmpty();
    }

    public void split(int i) {
        if (!canSplit(i)) throw new IllegalStateException("bay " + i + " cannot split");
        bays[i] = Bay.SMALL;
        bays[i + 1] = Bay.SMALL;
        chests[i] = new Chest(SMALL);
        chests[i + 1] = new Chest(SMALL);
    }

    /** Restores a bay while loading (no rules: the file is trusted to be what a past game saved, then repaired by the caller). */
    void restore(int i, Bay state, Chest chest) {
        bays[i] = state;
        chests[i] = chest;
    }

    static Chest newChest(int size) {
        return new Chest(size);
    }
}
