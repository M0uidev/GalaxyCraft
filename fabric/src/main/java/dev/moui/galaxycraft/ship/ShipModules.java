package dev.moui.galaxycraft.ship;

/** A ship's modules, each a tier 0 (none) to 3. Engine and cabin are required. */
public record ShipModules(int hull, int engine, int rudder, int cabin) {
    public static final int MAX_TIER = 3;

    public boolean valid() {
        return engine >= 1 && cabin >= 1
                && hull >= 0 && rudder >= 0 && engine <= MAX_TIER && cabin <= MAX_TIER
                && hull <= MAX_TIER && rudder <= MAX_TIER;
    }

    /** The weakest valid ship that keeps what is sound (a corrupt save never crashes the game). */
    public ShipModules repaired() {
        return new ShipModules(clamp(hull, 0), clamp(engine, 1), clamp(rudder, 0), clamp(cabin, 1));
    }

    private static int clamp(int v, int min) {
        return Math.max(min, Math.min(MAX_TIER, v));
    }
}
