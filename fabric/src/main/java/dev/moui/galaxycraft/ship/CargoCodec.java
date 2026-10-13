package dev.moui.galaxycraft.ship;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * A hold as JSON: {"bays": ["SMALL", "LARGE_HEAD", "LARGE_TAIL", "EMPTY"], "chests": [[{"slot": 3, "item": ...}], ...]}
 * (one entry per bay, only a bay's chest, the head's for a large one). Items go through the game's
 * {@link ItemCodec}; a file that is not what this writes is an empty hold.
 */
public final class CargoCodec {
    /** Items to JSON and back, with the world's registries; set by the client when a world opens. */
    public interface ItemCodec {
        JsonElement encode(ItemStack stack);

        /** The stack, or {@link ItemStack#EMPTY} when the item no longer exists. */
        ItemStack decode(JsonElement json);
    }

    private static volatile ItemCodec items;

    private CargoCodec() {}

    public static void setItemCodec(ItemCodec codec) {
        items = codec;
    }

    static JsonArray encode(List<ItemStack> stacks) {
        JsonArray out = new JsonArray();
        ItemCodec codec = items;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack s = stacks.get(i);
            if (s.isEmpty() || codec == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.add("item", codec.encode(s));
            out.add(o);
        }
        return out;
    }

    static void decodeInto(JsonArray saved, List<ItemStack> stacks) {
        ItemCodec codec = items;
        if (codec == null) return;
        for (JsonElement e : saved) {
            try {
                JsonObject o = e.getAsJsonObject();
                int slot = o.get("slot").getAsInt();
                if (slot >= 0 && slot < stacks.size()) stacks.set(slot, codec.decode(o.get("item")));
            } catch (RuntimeException ex) {
                // one damaged stack is lost, not the hold
            }
        }
    }

    public static JsonObject toJson(ShipCargo cargo) {
        JsonObject o = new JsonObject();
        JsonArray bays = new JsonArray(), chests = new JsonArray();
        for (int i = 0; i < ShipCargo.BAYS; i++) {
            bays.add(cargo.bay(i).name());
            ShipCargo.Chest c = cargo.chest(i);
            chests.add(c == null || cargo.bay(i) == ShipCargo.Bay.LARGE_TAIL ? new JsonArray() : c.save());
        }
        o.add("bays", bays);
        o.add("chests", chests);
        return o;
    }

    /** The hold of a file with room for `capacity` bays; anything wrong with the file leaves bays empty, never a half chest. */
    public static ShipCargo fromJson(JsonElement e, int capacity) {
        ShipCargo cargo = new ShipCargo(capacity);
        try {
            JsonObject o = e.getAsJsonObject();
            JsonArray bays = o.getAsJsonArray("bays");
            JsonArray chests = o.has("chests") ? o.getAsJsonArray("chests") : new JsonArray();
            for (int i = 0; i < ShipCargo.BAYS && i < bays.size(); i++) {
                ShipCargo.Bay b = state(bays.get(i).getAsString());
                JsonArray saved = i < chests.size() && chests.get(i).isJsonArray() ? chests.get(i).getAsJsonArray() : null;
                if (b == ShipCargo.Bay.SMALL) {
                    cargo.restore(i, b, ShipCargo.Chest.loaded(ShipCargo.SMALL, saved));
                } else if (b == ShipCargo.Bay.LARGE_HEAD && (i == 0 || i == 2) && i + 1 < bays.size()
                        && state(bays.get(i + 1).getAsString()) == ShipCargo.Bay.LARGE_TAIL) {
                    ShipCargo.Chest large = ShipCargo.Chest.loaded(ShipCargo.LARGE, saved);
                    cargo.restore(i, b, large);
                    cargo.restore(i + 1, ShipCargo.Bay.LARGE_TAIL, large);
                    i++;
                }
                // EMPTY, a lone head or a lone tail: the bay stays empty
            }
            // A chest the file puts in a bay no cabin unlocks still needs the bay.
            for (int i = 0; i < ShipCargo.BAYS; i++)
                if (cargo.bay(i) != ShipCargo.Bay.EMPTY) cargo.setCapacity(cargo.capacity());
        } catch (RuntimeException ex) {
            return new ShipCargo(capacity);
        }
        return cargo;
    }

    private static ShipCargo.Bay state(String name) {
        try {
            return ShipCargo.Bay.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return ShipCargo.Bay.EMPTY;
        }
    }
}
