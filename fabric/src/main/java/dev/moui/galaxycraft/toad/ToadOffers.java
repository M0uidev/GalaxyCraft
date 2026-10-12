package dev.moui.galaxycraft.toad;

import java.util.List;
import java.util.Map;

/** What each type sells at each tier and what it accepts. First-draft numbers: refined by playing. */
public final class ToadOffers {
    public record Price(String item, int count) {}

    /** One thing sold; any one of the prices pays for it. */
    public record Offer(String sell, int sellCount, List<Price> prices, int tier) {}

    private static final Map<ToadType, Map<Integer, List<Offer>>> TABLE = Map.of(
            ToadType.GUIDE, Map.of(
                    0, List.of(new Offer("minecraft:bread", 4, List.of(new Price("minecraft:coal", 4), new Price("minecraft:cobblestone", 16)), 0),
                            new Offer("minecraft:torch", 16, List.of(new Price("minecraft:coal", 2), new Price("minecraft:copper_ingot", 2)), 0))),
            ToadType.MINER, Map.of(
                    0, List.of(new Offer("minecraft:coal", 8, List.of(new Price("minecraft:cobblestone", 32), new Price("minecraft:copper_ingot", 2)), 0)),
                    1, List.of(new Offer("minecraft:iron_pickaxe", 1, List.of(new Price("minecraft:iron_ingot", 3), new Price("minecraft:gold_ingot", 2)), 1))),
            ToadType.EXPLORER, Map.of(
                    1, List.of(new Offer("minecraft:compass", 1, List.of(new Price("minecraft:iron_ingot", 2), new Price("minecraft:redstone", 8)), 1),
                            new Offer("minecraft:map", 1, List.of(new Price("minecraft:copper_ingot", 4), new Price("minecraft:iron_ingot", 1)), 1))),
            ToadType.TECHNICIAN, Map.of(
                    2, List.of(new Offer("minecraft:diamond", 1, List.of(new Price("minecraft:emerald", 4), new Price("minecraft:gold_ingot", 8)), 2))),
            ToadType.MAILMAN, Map.of(
                    3, List.of(new Offer("minecraft:amethyst_shard", 4, List.of(new Price("minecraft:diamond", 1), new Price("minecraft:emerald", 3)), 3))));

    private ToadOffers() {}

    /** The offers of a type at a tier (clamped to 0..5); empty when it sells nothing there. */
    public static List<Offer> forType(ToadType type, int tier) {
        int t = Math.max(0, Math.min(5, tier));
        return TABLE.getOrDefault(type, Map.of()).getOrDefault(t, List.of());
    }
}
