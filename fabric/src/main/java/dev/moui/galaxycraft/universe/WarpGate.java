package dev.moui.galaxycraft.universe;

/** Whether the ship's Warp Core opens a system: visited systems are free, new ones need the core's reach. */
public final class WarpGate {
    private WarpGate() {}

    public static boolean allowed(Universe u, Universe.Star target, Visited visited, int coreTier) {
        return target.home() || visited.has(target.sector()) || u.tier(target) <= Tier.reach(coreTier);
    }

    /** The line shown when a warp is refused. */
    public static String why(Universe u, Universe.Star target, int coreTier) {
        return "This system is tier " + u.tier(target) + ": your Warp Core reaches tier " + Tier.reach(coreTier)
                + " in systems you have not been to";
    }
}
