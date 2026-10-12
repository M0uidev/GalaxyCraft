package dev.moui.galaxycraft.toad;

import dev.moui.galaxycraft.universe.StarType;

/**
 * What a Toad knows when you talk to it. The caller fills it (tier and star from the system the player
 * is in, {@link #forSystem}; the Warp Core and missing module from the ship). {@code missingModule}
 * may be null; {@code star} is "yellow", "red" (a red dwarf), "lava" or "blue".
 */
public record ToadContext(int systemTier, String star, int warpCoreTier, String missingModule, boolean visited) {
    /** The home system's context: tier 0, a yellow star, nothing asked of the ship. What a Toad assumes when no system is known. */
    public static ToadContext home() {
        return new ToadContext(0, "yellow", 0, null, true);
    }

    /** The context of a system of that tier and star (null star: yellow). The ship is not asked yet; the system counts as visited. */
    public static ToadContext forSystem(int tier, StarType star) {
        return new ToadContext(Math.max(0, Math.min(dev.moui.galaxycraft.universe.Tier.MAX, tier)), name(star), 0, null, true);
    }

    /** The star's name in the dialogue's keys. */
    static String name(StarType star) {
        if (star == null) return "yellow";
        return switch (star) {
            case YELLOW -> "yellow";
            case RED_DWARF -> "red";
            case LAVA -> "lava";
            case BLUE -> "blue";
        };
    }
}
