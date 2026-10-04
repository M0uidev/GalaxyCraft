package dev.moui.galaxycraft.voxel;

import java.util.List;
import org.joml.Vector3d;

/**
 * Where the planets of a stage go, and which of them the game gets in full (chunks, collision)
 * rather than as their far view. Galaxy units. No Minecraft types, so it is unit tested.
 */
public final class PlanetLayout {
    /** A planet's place: its center and the reach of its gravity. */
    public record Sphere(Vector3d center, double gravity) {}

    /** Most planets a stage holds (the module keeps 8). */
    public static final int MAX_PLANETS = 8;
    /** Between two planets' gravity, blocks. */
    public static final double GAP = 24;
    /**
     * A planet other than the nearest gets its chunks once Mario is DETAIL_IN blocks or closer to
     * its gravity, and loses them past DETAIL_OUT.
     */
    public static final double DETAIL_IN = 64, DETAIL_OUT = 128;

    private PlanetLayout() {}

    /**
     * The center for a new planet whose gravity reaches gravity (units): above the player first (as
     * a lone planet always went, its gravity GAP blocks over the feet), then around that spot, ring
     * after ring in the plane across up, the first where its gravity keeps GAP blocks from every
     * other planet's. Null if none of those is free.
     */
    public static Vector3d place(List<Sphere> others, double gravity, Vector3d feet, Vector3d up, double unitsPerBlock) {
        Vector3d u = new Vector3d(up).normalize();
        Vector3d first = new Vector3d(u).mul(gravity + GAP * unitsPerBlock).add(feet);
        if (free(others, first, gravity, unitsPerBlock)) return first;
        // Two directions across up.
        Vector3d a = Math.abs(u.x) < 0.9 ? new Vector3d(1, 0, 0) : new Vector3d(0, 0, 1);
        a.sub(new Vector3d(u).mul(a.dot(u))).normalize();
        Vector3d b = new Vector3d(u).cross(a);
        double step = 2 * gravity + GAP * unitsPerBlock;
        for (int ring = 1; ring <= 4; ring++) {
            int around = 6 * ring;
            for (int k = 0; k < around; k++) {
                double t = 2 * Math.PI * k / around;
                Vector3d c = new Vector3d(a).mul(Math.cos(t)).add(new Vector3d(b).mul(Math.sin(t))).mul(ring * step).add(first);
                if (free(others, c, gravity, unitsPerBlock)) return c;
            }
        }
        return null;
    }

    private static boolean free(List<Sphere> others, Vector3d c, double gravity, double unitsPerBlock) {
        for (Sphere o : others)
            if (o.center().distance(c) < o.gravity() + gravity + GAP * unitsPerBlock) return false;
        return true;
    }

    /**
     * Whether a planet gets its chunks: the one nearest Mario always (a lone planet always did),
     * the others once he nears their gravity, with some slack before they lose them again. distance:
     * from Mario to its center; had: whether it has them now.
     */
    public static boolean detail(boolean had, boolean nearest, double distance, double gravity, double unitsPerBlock) {
        if (nearest) return true;
        double past = distance - gravity;
        return past < (had ? DETAIL_OUT : DETAIL_IN) * unitsPerBlock;
    }
}
