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

    /** Most complete planets (gravity, chunks or their far view) in the game at once. */
    public static final int NEAR_PLANETS = 8;
    /** Most planets a stage holds outside a catalog (/galaxycraft planet add in a level). */
    public static final int MAX_PLANETS = NEAR_PLANETS;
    /** A complete planet gives its place to another only once that one is this much nearer, blocks. */
    public static final double KEEP_NEAR = 64;
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

    /** placeAlong walks the look this far at most (blocks), in steps of ALONG_STEP. */
    public static final double ALONG_MAX = 4000, ALONG_STEP = 4;

    /**
     * The center for a new planet whose gravity reaches gravity (units), where the player looks:
     * the first point along look from eye (galaxy units) whose gravity keeps GAP blocks from every
     * other planet's and from the eye. Looking down through the planet underfoot, it is past that
     * one, below it. Null if there is none within ALONG_MAX blocks.
     */
    public static Vector3d placeAlong(List<Sphere> others, double gravity, Vector3d eye, Vector3d look, double unitsPerBlock) {
        Vector3d dir = new Vector3d(look).normalize();
        for (double d = gravity + GAP * unitsPerBlock; d <= ALONG_MAX * unitsPerBlock; d += ALONG_STEP * unitsPerBlock) {
            Vector3d c = new Vector3d(dir).mul(d).add(eye);
            if (free(others, c, gravity, unitsPerBlock)) return c;
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

    /**
     * The planets by how near Mario is to their gravity, nearest first, those in had (complete now)
     * counted KEEP_NEAR blocks nearer than they are: the first NEAR_PLANETS are complete, the next
     * two are made ahead. Positions in all.
     */
    public static java.util.List<Integer> ranked(List<Sphere> all, Vector3d mario, java.util.Set<Integer> had, double unitsPerBlock) {
        double[] score = new double[all.size()];
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Sphere s = all.get(i);
            score[i] = s.center().distance(mario) - s.gravity() - (had.contains(i) ? KEEP_NEAR * unitsPerBlock : 0);
            out.add(i);
        }
        out.sort((a, b) -> Double.compare(score[a], score[b]));
        return out;
    }

    /**
     * Patches along a face's edge for a planet drawn from afar: 12 while it spans more than 6
     * degrees of view, 6 above 2, else 3; had (its patches now, 0: none) moves only 20% past a
     * threshold, so it does not flicker there.
     */
    public static int farPatches(double radius, double distance, int had) {
        double angle = Math.toDegrees(2 * Math.atan(radius / Math.max(distance, 1e-9)));
        double up12 = had == 12 ? 6 * 0.8 : 6 * 1.2, up6 = had >= 6 ? 2 * 0.8 : 2 * 1.2;
        return angle > up12 ? 12 : angle > up6 ? 6 : 3;
    }
}
