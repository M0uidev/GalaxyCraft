package dev.moui.galaxycraft.map;

import dev.moui.galaxycraft.universe.StarType;
import dev.moui.galaxycraft.universe.Universe;

/**
 * Tier and star of a system, for the map and for anyone who needs them (Toad NPC dialogue). Both
 * come from the Universe (derived from the system's seed and distance to home).
 */
public record SystemInfo(int tier, StarType star) {
    public static SystemInfo of(Universe u, Universe.Star star) {
        return new SystemInfo(u.tier(star), u.starType(star));
    }

    /** Whether the player has been to the system. Stand-in: only home until the map reads Visited. */
    public static boolean visited(Universe.Star star) {
        return star.home();
    }
}
