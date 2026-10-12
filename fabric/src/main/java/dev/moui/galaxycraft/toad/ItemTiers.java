package dev.moui.galaxycraft.toad;

import java.util.Map;

/** The first tier an item is produced at (planet progression's ore table); -1 when unknown. */
public final class ItemTiers {
    private static final Map<String, Integer> TIERS = Map.ofEntries(
            Map.entry("minecraft:bread", 0), Map.entry("minecraft:coal", 0), Map.entry("minecraft:copper_ingot", 0),
            Map.entry("minecraft:cobblestone", 0), Map.entry("minecraft:oak_log", 0), Map.entry("minecraft:torch", 0),
            Map.entry("minecraft:iron_ingot", 1), Map.entry("minecraft:gold_ingot", 1), Map.entry("minecraft:redstone", 1),
            Map.entry("minecraft:lapis_lazuli", 1), Map.entry("minecraft:iron_pickaxe", 1), Map.entry("minecraft:compass", 1),
            Map.entry("minecraft:emerald", 2), Map.entry("minecraft:diamond", 2), Map.entry("minecraft:map", 1),
            Map.entry("minecraft:quartz", 3), Map.entry("minecraft:amethyst_shard", 3), Map.entry("minecraft:ancient_debris", 3),
            Map.entry("minecraft:netherite_scrap", 4));

    private ItemTiers() {}

    public static int tier(String itemId) {
        return TIERS.getOrDefault(itemId, -1);
    }
}
