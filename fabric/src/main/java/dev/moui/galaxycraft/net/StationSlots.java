package dev.moui.galaxycraft.net;

import java.util.HashMap;
import java.util.Map;

/**
 * Stations on the wire. Every message that names a planet carries an int; from {@link #BASE} up it names a
 * station instead, by a <em>slot</em> the host hands out the first time it speaks of the station and never
 * reuses in a session. Slots are not saved: a station's identity is its id. Pure.
 */
public final class StationSlots {
    public static final int BASE = 1 << 24;
    /** More slots than a session could ever hand out; anything past is not a slot. */
    public static final int LIMIT = BASE + (1 << 20);

    private final Map<String, Integer> slots = new HashMap<>();
    private final Map<Integer, String> ids = new HashMap<>();
    private int next = BASE;

    /** Whether a wire planet number names a station. */
    public static boolean isStation(int index) {
        return index >= BASE && index < LIMIT;
    }

    /** Host: the id's slot, handed out now if it has none. */
    public int assign(String id) {
        Integer have = slots.get(id);
        if (have != null) return have;
        int slot = next++;
        slots.put(id, slot);
        ids.put(slot, id);
        return slot;
    }

    /** Guest: the host said this id has this slot. False if the pair is not a sane one (a slot taken by another id, or out of range). */
    public boolean learn(int slot, String id) {
        if (!isStation(slot)) return false;
        String had = ids.get(slot);
        if (had != null) return had.equals(id);
        Integer old = slots.get(id);
        if (old != null && old != slot) return false;
        slots.put(id, slot);
        ids.put(slot, id);
        return true;
    }

    /** The id's slot, 0 if it has none yet. */
    public int slotOf(String id) {
        return slots.getOrDefault(id, 0);
    }

    /** The slot's station id, null if the slot is unknown. */
    public String idOf(int slot) {
        return ids.get(slot);
    }

    public void clear() {
        slots.clear();
        ids.clear();
        next = BASE;
    }

    /**
     * Whether an edit is made for a grid that is no longer the station's: a station's edits carry the
     * {@link dev.moui.galaxycraft.voxel.Station#gridKey(dev.moui.galaxycraft.voxel.FlatGrid)} of the grid
     * their cell numbers go by. Planets have no such key (0 and 0).
     */
    public static boolean stale(int index, int editGen, int currentKey) {
        return isStation(index) && editGen != currentKey;
    }
}
