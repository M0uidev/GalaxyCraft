package dev.moui.galaxycraft.map;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;

/**
 * Tier and star of a system, for the map and for anyone who needs them (Toad NPC dialogue). Today
 * a stand-in: the tier from the distance to home (the progression spec's rings), the star always
 * yellow. When SystemIndex carries real values only this class changes.
 */
public record SystemInfo(int tier, StarType star) {
    /** Ring edges, blocks from home: tier 0 up to the first (home and its neighbors), 5 past the last. First draft. */
    static final double[] EDGES = {12_000, 20_000, 40_000, 70_000, 110_000};

    public static SystemInfo of(Universe.Star star) {
        return of(star, 1 / GravityFrame.SCALE);
    }

    public static SystemInfo of(Universe.Star star, double unitsPerBlock) {
        if (star.home()) return new SystemInfo(0, StarType.YELLOW);
        double blocks = star.center().minus(UPos.ZERO).length() / unitsPerBlock;
        return new SystemInfo(tierAt(blocks), starOf(star.seed()));
    }

    /** Stand-in star: varied by the system's seed so the map shows the colors; the real one comes from Universe. */
    static StarType starOf(long seed) {
        long h = seed * 0x9E3779B97F4A7C15L;
        int k = (int) ((h >>> 40) % 10);
        return k < 5 ? StarType.YELLOW : k < 7 ? StarType.RED_DWARF : k < 9 ? StarType.BLUE : StarType.LAVA;
    }

    /** Whether the player has been to the system. Stand-in: only home until visits are recorded. */
    public static boolean visited(Universe.Star star) {
        return star.home();
    }

    public static int tierAt(double blocksFromHome) {
        int t = 0;
        for (double e : EDGES) if (blocksFromHome > e) t++;
        return t;
    }
}
