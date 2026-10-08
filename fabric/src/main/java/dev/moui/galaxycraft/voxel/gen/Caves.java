package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.joml.Vector3d;

/**
 * Caves as Minecraft 1.7 digs them (MapGenCaves, MapGenRavine), made into a labyrinth for small
 * planets: more systems, narrow tunnels (2 to 4 blocks) that wander level with the ground, branch
 * and cross each other, few and small rooms, a rare ravine. Space around the planet is cut into
 * 16-block cubes; each that holds ground rolls from the seed and its place whether it starts a
 * system, so the same seed digs the same caves. "Level" is along the sphere: a tunnel's pitch is
 * taken against the up where it is, so it curves with the planet.
 */
public final class Caves {
    /** A tunnel's widest half width, blocks (rooms aside). */
    static final double TUNNEL_RADIUS = 2;
    /** A room's half width, blocks. */
    static final double ROOM_RADIUS = 3.5;
    /** Ground kept over caves under the surface when there are no entrances, blocks. */
    static final int ROOF = 3;
    private static final int CUBE = 16;

    private final CubeSphere grid;
    private final int depth;
    private final char[] cells;
    private final int[] top;
    private final boolean entrances;
    private final int lava;
    private final char water, lavaId;
    private final Set<Integer> seen = new HashSet<>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();

    private Caves(CubeSphere grid, int depth, char[] cells, int[] top, boolean entrances, int lava, char water, char lavaId) {
        this.grid = grid;
        this.depth = depth;
        this.cells = cells;
        this.top = top;
        this.entrances = entrances;
        this.lava = lava;
        this.water = water;
        this.lavaId = lavaId;
    }

    /**
     * Digs caves into cells. top: each column's highest ground (k); caves: 1 (a few) to 100 (many),
     * 0 none; below layer lava, what is dug fills with lava; nothing is dug beside water, nor the
     * bedrock (k 0).
     */
    public static void carve(CubeSphere grid, int depth, char[] cells, int[] top, long seed, int caves, boolean entrances,
            int lava, char water, char lavaId) {
        if (caves <= 0) return;
        double surface = grid.core + depth, inner = grid.core + 1, outer = grid.core + grid.layers;
        int reach = (int) Math.ceil(outer / CUBE);
        double systems = 0.35 * caves / 50, ravines = 0.02 * caves / 50;
        java.util.List<int[]> cubes = new java.util.ArrayList<>();
        for (int x = -reach; x < reach; x++)
            for (int y = -reach; y < reach; y++)
                for (int z = -reach; z < reach; z++) {
                    double mid = new Vector3d((x + 0.5) * CUBE, (y + 0.5) * CUBE, (z + 0.5) * CUBE).length();
                    if (mid >= inner - CUBE && mid <= surface + CUBE) cubes.add(new int[] {x, y, z});
                }
        // A cell is dug or not by what caves never change (its layer, the water, the ground's top),
        // so cubes can dig at once, in any order, and the same seed digs the same caves.
        cubes.parallelStream().forEach(q -> {
            int x = q[0], y = q[1], z = q[2];
            Caves c = new Caves(grid, depth, cells, top, entrances, lava, water, lavaId);
            Random rnd = new Random(seed ^ (x * 341873128712L + y * 132897987541L + z * 42317861L));
            if (rnd.nextDouble() < systems) {
                Vector3d start = c.start(rnd, x, y, z, inner, surface);
                if (start != null) {
                    int tunnels = 2 + rnd.nextInt(4);
                    if (rnd.nextInt(6) == 0) c.room(start, ROOM_RADIUS - rnd.nextDouble());
                    for (int t = 0; t < tunnels; t++)
                        c.tunnel(rnd.nextLong(), start, rnd.nextDouble() * Math.PI * 2, (rnd.nextDouble() - 0.5) / 4,
                                0.2 + rnd.nextDouble() * (TUNNEL_RADIUS - 1.2), 40 + rnd.nextInt(80), 2, false);
                }
            }
            if (rnd.nextDouble() < ravines) {
                Vector3d start = c.start(rnd, x, y, z, inner + 4, surface);
                if (start != null) c.tunnel(rnd.nextLong(), start, rnd.nextDouble() * Math.PI * 2, 0, 0.5, 60 + rnd.nextInt(50), 0, true);
            }
        });
    }

    /** A point in the cube, in the ground (biased deep as 1.7's), or null. */
    private Vector3d start(Random rnd, int x, int y, int z, double inner, double surface) {
        Vector3d p = new Vector3d((x + rnd.nextDouble()) * CUBE, (y + rnd.nextDouble()) * CUBE, (z + rnd.nextDouble()) * CUBE);
        double r = inner + 1 + rnd.nextDouble() * rnd.nextDouble() * (surface - inner);
        p.normalize(r);
        return p.x / CUBE >= x && p.x / CUBE < x + 1 && p.y / CUBE >= y && p.y / CUBE < y + 1 && p.z / CUBE >= z && p.z / CUBE < z + 1
                ? p : null;
    }

    private void room(Vector3d at, double radius) {
        dig(at, radius, radius * 0.8);
    }

    /** A heading along the ground at p, turned yaw from a fixed reference. */
    private static Vector3d heading(Vector3d p, double yaw) {
        Vector3d up = new Vector3d(p).normalize();
        Vector3d h = Math.abs(up.y) < 0.9 ? new Vector3d(up).cross(0, 1, 0).normalize() : new Vector3d(up).cross(1, 0, 0).normalize();
        return h.rotateAxis(yaw, up.x, up.y, up.z);
    }

    private void tunnel(long seed, Vector3d from, double yaw, double pitch, double width, int length, int branches, boolean ravine) {
        tunnel(seed, from, heading(from, yaw), pitch, width, length, branches, ravine);
    }

    /** One worm: 1.7's drift of yaw and pitch, a step a block, splitting in two once (branches deep). */
    private void tunnel(long seed, Vector3d from, Vector3d heading, double pitch, double width, int length, int branches, boolean ravine) {
        Random rnd = new Random(seed);
        Vector3d p = new Vector3d(from), up = new Vector3d();
        double dYaw = 0, dPitch = 0;
        int branchAt = length / 4 + rnd.nextInt(Math.max(1, length / 2));
        for (int step = 0; step < length; step++) {
            double radius = 1 + Math.sin(step * Math.PI / length) * width;
            if (ravine) dig(p, radius * 1.5, radius * 4.5);
            else dig(p, radius, radius * 0.85);
            up.set(p).normalize();
            // Keep the heading along the ground where it is now, then tilt it by the pitch.
            heading.fma(-heading.dot(up), up).normalize();
            p.fma(Math.cos(pitch), heading).fma(Math.sin(pitch), up);
            pitch = pitch * 0.7 + dPitch * 0.1;
            heading.rotateAxis(dYaw * 0.1, up.x, up.y, up.z);
            dPitch = dPitch * (ravine ? 0.8 : 0.9) + (rnd.nextDouble() - rnd.nextDouble()) * rnd.nextDouble() * 2;
            dYaw = dYaw * (ravine ? 0.5 : 0.75) + (rnd.nextDouble() - rnd.nextDouble()) * rnd.nextDouble() * 4;
            if (!ravine && step == branchAt && branches > 0) {
                for (int side = -1; side <= 1; side += 2)
                    tunnel(rnd.nextLong(), p, new Vector3d(heading).rotateAxis(side * Math.PI / 2, up.x, up.y, up.z), pitch / 3,
                            width * 0.8, Math.max(10, length - step), branches - 1, false);
                if (rnd.nextBoolean()) return; // 1.7 ends a worm where it splits; ours goes on half the time
            }
            double r = p.length();
            if (r < grid.core + 1 || r > grid.core + grid.layers) return;
        }
    }

    /** Digs the cells whose centers lie in the ellipsoid around at: across half widths, up half height (along up). */
    private void dig(Vector3d at, double across, double upHalf) {
        int start = grid.cellAt(at);
        if (start < 0) return;
        Vector3d up = new Vector3d(at).normalize(), rel = new Vector3d();
        seen.clear();
        queue.clear();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            grid.center(cell).sub(at, rel);
            double v = rel.dot(up), h2 = rel.lengthSquared() - v * v;
            if (h2 / (across * across) + v * v / (upHalf * upHalf) >= 1 && cell != start) continue;
            carve(cell);
            for (int side = 0; side < 6; side++) {
                int nb = grid.neighbor(cell, side);
                if (nb >= 0 && seen.add(nb)) queue.add(nb);
            }
        }
    }

    private void carve(int cell) {
        int k = grid.k(cell);
        char id = cells[cell];
        if (k == 0 || id == Blocks.AIR || id == water || id == lavaId) return;
        int col = cell / grid.layers;
        if (!entrances && k > top[col] - ROOF) return;
        for (int side = 0; side < 6; side++) {
            int nb = grid.neighbor(cell, side);
            if (nb >= 0 && cells[nb] == water) return;
        }
        cells[cell] = k < lava ? lavaId : (char) Blocks.AIR;
    }
}
