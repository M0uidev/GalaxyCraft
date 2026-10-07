package dev.moui.galaxycraft.universe;

import dev.moui.galaxycraft.voxel.PlanetLayout;
import java.util.List;
import java.util.Optional;
import org.joml.Vector3d;

/**
 * When the floating origin moves, and where to. Inside a system the origin is the system's
 * center, which keeps every planet of it within SYSTEM_BLOCKS (a float tells 1/32 of a unit apart
 * there): it moves once, on the way in. Out in the void between systems it follows Mario, a cell
 * corner near him whenever he is MOVE_AT from it. Moving is cheapest in the clear (no planet
 * collision near Mario, no landing under way: only gravity spheres, drawn planets, Mario and the
 * camera move), so it waits for that, unless he gets as far as MUST_AT. No Minecraft types.
 */
public final class OriginPolicy {
    /** Out in the void, the origin follows once Mario is this far from it on an axis (4 cells, 3277 blocks). */
    public static final double MOVE_AT = 4 * UPos.CELL;
    /** This far (16 cells, 13,107 blocks), it moves even if not in the clear: a hitch beats shaking. */
    public static final double MUST_AT = 16 * UPos.CELL;
    /** Past a system's reach, Mario still counts as in it by this much (blocks): it snaps there in the void. */
    public static final double SYSTEM_MARGIN = 2 * PlanetLayout.DETAIL_OUT;

    private OriginPolicy() {}

    /**
     * Whether moving the origin now touches no collision: Mario is past DETAIL_OUT of every
     * planet's gravity (so none has its chunks for him) and no teleport or landing is under way.
     * spheres: the planets in the game, units in the current origin.
     */
    public static boolean clear(Vector3d mario, List<PlanetLayout.Sphere> spheres, boolean landing, double unitsPerBlock) {
        if (landing) return false;
        for (PlanetLayout.Sphere s : spheres)
            if (s.center().distance(mario) - s.gravity() < PlanetLayout.DETAIL_OUT * unitsPerBlock) return false;
        return true;
    }

    /**
     * Where the origin should go now, or empty to leave it. mario: where he is in the universe;
     * system: the star whose system he is in (Universe.systemAt with SYSTEM_MARGIN), if any.
     */
    public static Optional<UPos> target(Origin origin, UPos mario, Optional<Universe.Star> system, boolean clear) {
        UPos goal = system.map(Universe.Star::center).orElse(mario);
        double far = maxAbs(origin.local(goal)), marioFar = maxAbs(origin.local(mario));
        // In a system, its center: once there, the center is within half a cell of the origin.
        boolean due = system.isPresent() ? far > UPos.CELL / 2 : far >= MOVE_AT;
        if ((due && clear) || marioFar >= MUST_AT) return Optional.of(goal);
        return Optional.empty();
    }

    private static double maxAbs(Vector3d v) {
        return Math.max(Math.abs(v.x), Math.max(Math.abs(v.y), Math.abs(v.z)));
    }
}
