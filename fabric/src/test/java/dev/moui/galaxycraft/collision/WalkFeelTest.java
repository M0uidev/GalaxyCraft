package dev.moui.galaxycraft.collision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moui.galaxycraft.geom.Tri;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.kcl.KclWriter;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetMesher;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Minecraft movement over a dug-up, built-on planet, the frame turning and snapping to the blocks
 * every tick as the client does it, and the player moved as Minecraft's Entity.collide moves it
 * (boxes it starts in pass; Y first; step-up to 0.6): pushing into walls, jumping, turning, it
 * never ends up inside a block or under the ground.
 */
class WalkFeelTest {
    private static final double UPB = 1 / GravityFrame.SCALE;
    private static final double H = 1.8, STEP = 0.6;
    /** Half the player's width: Minecraft's 0.3, narrower deep down (GalaxyCraftClient.fitWidth). */
    private double W = 0.3;

    private static final boolean DEBUG = Boolean.getBoolean("walkDebug");
    private final java.util.ArrayDeque<String> trail = new java.util.ArrayDeque<>();
    private VoxelPlanet p;
    private CubeSphere g;
    private final CollisionField field = new CollisionField();
    {
        field.setBlockParts(m -> true);
        field.setBlockSource((f, q, out) -> dev.moui.galaxycraft.voxel.PlanetCollision.boxes(p,
                l -> new Vector3d(l).mul(UPB), gal -> new Vector3d(gal).div(UPB), f, q, out));
    }

    @Test void walkingNeverClipsIntoTheGround() {
        for (int seed = 1; seed <= 4; seed++) walk(32, seed, 0.5);
    }

    @Test void nearTheCubesEdgesToo() {
        walk(32, 11, 0.12);
        walk(64, 12, 0.1);
    }

    @Test void inACaveByTheCore() {
        for (int seed = 1; seed <= 4; seed++) walk(32, seed, 0.5, 4);
        walk(32, 5, 0.12, 4);
        walk(64, 6, 0.1, 4);
        walk(128, 7, 0.1, 4);
    }

    @Test void onABigPlanet() {
        walk(128, 21, 0.5);
    }

    @Test void diggingDownAgainstAWallFalls() {
        for (int radius : new int[] {32, 128})
            for (double at : new double[] {0.5, 0.2, 0.08})
                for (int dir = 0; dir < 4; dir++) digDown(radius, at, dir, 0, true);
    }

    /** By the core, blocks are 0.7 wide and up: as wide as 0.6 of its block (GalaxyCraftClient.fitWidth), it drops in. */
    @Test void diggingDownByTheCoreFallsWithANarrowerBox() {
        for (int radius : new int[] {32, 128})
            for (double at : new double[] {0.5, 0.08})
                for (int dir = 0; dir < 4; dir++) digDown(radius, at, dir, 5, true);
    }

    /**
     * Stands in a one-wide pit against its wall (dir: which wall), pushing into it, and digs the
     * block under the feet: the player drops into the hole, not floating on its rim.
     */
    private void digDown(int radius, double at, int dir, int layer, boolean fit) {
        p = VoxelPlanet.ofRadius(radius);
        g = p.grid;
        field.clear();
        int face = 0, n = g.n, top = layer > 0 ? layer : p.depth;
        int i = Math.max(11, Math.min(n - 12, (int) Math.round(n * at))), j = i;
        // A one-wide pit two deep to stand in: walls on every side.
        p.set(g.index(face, i, j, top - 1), Material.AIR);
        p.set(g.index(face, i, j, top - 2), Material.AIR);
        int floor = g.index(face, i, j, top - 3);
        Vector3d gal = new Vector3d(dev.moui.galaxycraft.voxel.CellSpace.point(g, floor, 0.5, 1, 0.5)).mul(UPB);
        Vector3d feet = new Vector3d(0.5, 100, 0.5);
        GravityFrame frame = new GravityFrame(gal, feet, new Vector3d(gal).normalize().negate());
        Vector3d v = new Vector3d();
        boolean onGround = false;
        double yaw = dir * Math.PI / 2;
        double startY = Double.NaN;
        for (int tick = 0; tick < 80; tick++) {
            if (tick == 30) {
                p.set(floor, Material.AIR); // dug out
                startY = feet.y;
            }
            Vector3d here = frame.toGal(feet);
            int cell = g.cellAt(frame.toGal(new Vector3d(feet).add(0, 0.5, 0)).div(UPB));
            Vector3d up = dev.moui.galaxycraft.voxel.CellSpace.point(g, cell, 0.5, 1, 0.5)
                    .sub(dev.moui.galaxycraft.voxel.CellSpace.point(g, cell, 0.5, 0, 0.5)).normalize();
            frame.update(up.negate(), feet);
            Vector3d corner = new Vector3d(g.corner(cell, 0, 0, 0)).mul(UPB);
            Vector3d edge = new Vector3d(g.corner(cell, 1, 0, 0)).mul(UPB).sub(corner);
            GravityFrame.Align a = frame.alignGrid(edge, corner, feet);
            Vector3d edgeJ = new Vector3d(g.corner(cell, 0, 1, 0)).mul(UPB).sub(corner);
            W = fit ? Math.min(0.3, 0.3 * Math.min(edge.length(), edgeJ.length()) / UPB) : 0.3;
            feet.add(a.shift());
            v = new org.joml.Quaterniond().rotationY(a.yaw()).transform(v);
            yaw -= a.yaw();
            field.setFrame(frame);
            feet.add(field.pushOut(box(feet), STEP, 0.25));
            v.x = Math.cos(yaw) * 0.22;
            v.z = Math.sin(yaw) * 0.22;
            v.y = (v.y - 0.08) * 0.98;
            Vector3d moved = collide(feet, v, onGround);
            onGround = v.y < 0 && moved.y > v.y + 1e-9;
            if (moved.y != v.y) v.y = 0;
            feet.add(moved);
            if (DEBUG && tick >= 31 && tick <= 33) {
                StringBuilder sb = new StringBuilder();
                for (double[] b : field.boxesFor(new double[] {feet.x - 0.5, feet.y - 1.2, feet.z - 0.5, feet.x + 0.5, feet.y + 0.3, feet.z + 0.5}))
                    sb.append(String.format("[%.4f %.4f %.4f | %.4f %.4f %.4f]", b[0], b[1], b[2], b[3], b[4], b[5]));
                System.out.println("DIG " + tick + " feet " + feet + " moved " + moved + " push? boxes " + sb);
            }
        }
        assertTrue(startY - feet.y > 0.9, "radius " + radius + " at " + at + " wall " + dir
                + ": fell " + (startY - feet.y) + " blocks into the dug hole");
    }

    /** Walks around (at a fraction `at` across face 0) a planet of that radius, built on by seed. */
    private void walk(int radius, int seed, double at) {
        walk(radius, seed, at, 0);
    }

    /** cave > 0: in a cave dug out deep down, its floor that layer (blocks narrow by the core). */
    private void walk(int radius, int seed, double at, int cave) {
        p = VoxelPlanet.ofRadius(radius);
        g = p.grid;
        field.clear();
        int face = 0, n = g.n, top = cave > 0 ? cave : p.depth; // k = top: the first air layer
        int mid = Math.max(11, Math.min(n - 12, (int) Math.round(n * at)));
        if (cave > 0)
            for (int i = Math.max(0, mid - 12); i < Math.min(n, mid + 12); i++)
                for (int j = Math.max(0, mid - 12); j < Math.min(n, mid + 12); j++)
                    for (int k = top; k < top + 6; k++) p.set(g.index(face, i, j, k), Material.AIR);
        Random rnd = new Random(seed);
        // Pits, pillars, a walled corridor around the middle of a face.
        for (int t = 0; t < 40; t++) {
            int i = mid - 6 + rnd.nextInt(12), j = mid - 6 + rnd.nextInt(12);
            if (rnd.nextBoolean()) for (int k = top - 1 - rnd.nextInt(2); k < top; k++) p.set(g.index(face, i, j, k), Material.AIR);
            else for (int k = top; k < top + 1 + rnd.nextInt(3); k++) p.set(g.index(face, i, j, k), Material.STONE);
        }
        for (int i = mid - 8; i < mid + 8; i++)
            for (int k = top; k < top + 2; k++) {
                p.set(g.index(face, i, mid + 8, k), Material.STONE);
                p.set(g.index(face, i, mid + 10, k), Material.STONE);
            }
        load();

        int start = g.index(face, mid, mid, top + (cave > 0 ? 2 : 4));
        Vector3d gal = new Vector3d(g.center(start)).mul(UPB);
        Vector3d feet = new Vector3d(0.5, 100, 0.5);
        GravityFrame frame = new GravityFrame(gal, feet, new Vector3d(gal).normalize().negate());
        Vector3d v = new Vector3d();
        boolean onGround = false;
        double yaw = 0;
        int clipped = 0, under = 0;
        String first = null;
        for (int tick = 0; tick < 4000; tick++) {
            // The client's tick start: gravity, the blocks' grid, out of anything overlapping.
            Vector3d here = frame.toGal(feet);
            frame.update(new Vector3d(here).normalize().negate(), feet);
            int cell = g.cellAt(frame.toGal(new Vector3d(feet).add(0, 0.5, 0)).div(UPB));
            if (cell >= 0) {
                Vector3d corner = new Vector3d(g.corner(cell, 0, 0, 0)).mul(UPB);
                Vector3d edge = new Vector3d(g.corner(cell, 1, 0, 0)).mul(UPB).sub(corner);
                GravityFrame.Align a = frame.alignGrid(edge, corner, feet);
                feet.add(a.shift());
                v = new org.joml.Quaterniond().rotationY(a.yaw()).transform(v);
                yaw -= a.yaw();
            }
            field.setFrame(frame);
            Vector3d po = field.pushOut(box(feet), STEP, 0.25);
            if (DEBUG) {
                StringBuilder ov = new StringBuilder();
                double[] a = box(feet);
                for (double[] b : field.boxesFor(a))
                    if (b[0] < a[3] - 1e-7 && b[3] > a[0] + 1e-7 && b[1] < a[4] - 1e-7 && b[4] > a[1] + 1e-7 && b[2] < a[5] - 1e-7 && b[5] > a[2] + 1e-7)
                        ov.append(String.format("[%.3f %.3f %.3f | %.3f %.3f %.3f]", b[0], b[1], b[2], b[3], b[4], b[5]));
                trail.addLast("tick " + tick + " feet " + feet + " push " + po + " v " + v + " overlaps " + ov);
                if (trail.size() > 12) trail.removeFirst();
            }
            feet.add(po);
            // Input: a heading for a while, sometimes a jump.
            if (tick % 50 == 0) yaw = rnd.nextDouble() * Math.PI * 2;
            if (onGround && rnd.nextInt(30) == 0) v.y = 0.42;
            v.x = Math.cos(yaw) * 0.22;
            v.z = Math.sin(yaw) * 0.22;
            v.y = (v.y - 0.08) * 0.98;
            Vector3d moved = collide(feet, v, onGround);
            onGround = v.y < 0 && moved.y > v.y + 1e-9;
            if (moved.y != v.y) v.y = 0;
            feet.add(moved);
            // Where it ended: inside a solid block, or under the planet's surface?
            for (double h : new double[] {0.05, 0.9, 1.75}) {
                int c = g.cellAt(frame.toGal(new Vector3d(feet).add(0, h, 0)).div(UPB));
                if (c >= 0 && p.info(c).collides() && insideCell(c, frame, new Vector3d(feet).add(0, h, 0))) {
                    clipped++;
                    if (first == null) {
                        first = "tick " + tick + " inside a block at +" + h;
                        if (DEBUG) trail.forEach(System.out::println);
                    }
                }
            }
            double r = frame.toGal(feet).length() / UPB;
            if (r < g.radius(top) - 2.5) {
                under++;
                if (first == null) {
                    first = "tick " + tick + " under the ground, radius " + r;
                    if (DEBUG) trail.forEach(System.out::println);
                }
            }
        }
        assertEquals(0, clipped + under, "radius " + radius + " seed " + seed + ": " + first);
    }

    /** Deeper than 0.05 inside the cell's block (its trilinear model space), not merely touching it. */
    private boolean insideCell(int c, GravityFrame frame, Vector3d mc) {
        Vector3d m = dev.moui.galaxycraft.voxel.CellSpace.local(g, c, frame.toGal(mc).div(UPB));
        double d = 0.05;
        return m.x > d && m.x < 1 - d && m.y > d && m.y < 1 - d && m.z > d && m.z < 1 - d;
    }

    private double[] box(Vector3d feet) {
        return new double[] {feet.x - W, feet.y, feet.z - W, feet.x + W, feet.y + H, feet.z + W};
    }

    // Minecraft's Entity.collide, as far as walls and floors go.
    private Vector3d collide(Vector3d feet, Vector3d m, boolean onGround) {
        double[] a = box(feet);
        List<double[]> boxes = field.boxesFor(new double[] {a[0] - 1, a[1] - 1, a[2] - 1, a[3] + 1, a[4] + 1, a[5] + 1});
        Vector3d out = sweep(a, m, boxes);
        boolean landed = m.y < 0 && out.y != m.y;
        if ((landed || onGround) && (out.x != m.x || out.z != m.z)) {
            double[] ground = landed ? shift(a, 0, out.y, 0) : a;
            List<Double> heights = new ArrayList<>();
            for (double[] b : boxes)
                for (double y : new double[] {b[1], b[4]}) {
                    double rel = y - ground[1];
                    if (rel >= 0 && rel <= STEP && rel != out.y) heights.add(rel);
                }
            heights.sort(Double::compare);
            for (double h : heights) {
                Vector3d s = sweep(ground, new Vector3d(m.x, h, m.z), boxes);
                if (s.x * s.x + s.z * s.z > out.x * out.x + out.z * out.z)
                    return s.sub(0, a[1] - ground[1], 0);
            }
        }
        return out;
    }

    private static Vector3d sweep(double[] a, Vector3d m, List<double[]> boxes) {
        double y = axis(boxes, a, 1, m.y);
        double[] b = shift(a, 0, y, 0);
        double x, z;
        if (Math.abs(m.x) < Math.abs(m.z)) {
            z = axis(boxes, b, 2, m.z);
            x = axis(boxes, shift(b, 0, 0, z), 0, m.x);
        } else {
            x = axis(boxes, b, 0, m.x);
            z = axis(boxes, shift(b, x, 0, 0), 2, m.z);
        }
        return new Vector3d(x, y, z);
    }

    /** Shapes.collide along one axis: boxes the player already overlaps along it do not stop it. */
    private static double axis(List<double[]> boxes, double[] a, int ax, double d) {
        int o1 = (ax + 1) % 3, o2 = (ax + 2) % 3;
        for (double[] b : boxes) {
            if (!(b[o1] < a[o1 + 3] - 1e-7 && b[o1 + 3] > a[o1] + 1e-7 && b[o2] < a[o2 + 3] - 1e-7 && b[o2 + 3] > a[o2] + 1e-7))
                continue;
            if (d > 0 && b[ax] >= a[ax + 3] - 1e-7) d = Math.min(d, b[ax] - a[ax + 3]);
            else if (d < 0 && b[ax + 3] <= a[ax] + 1e-7) d = Math.max(d, b[ax + 3] - a[ax]);
        }
        return d;
    }

    private static double[] shift(double[] a, double x, double y, double z) {
        return new double[] {a[0] + x, a[1] + y, a[2] + z, a[3] + x, a[4] + y, a[5] + z};
    }

    private void load() {
        for (int ch = 0; ch < p.chunkCount(); ch++) {
            List<Tri> tris = new ArrayList<>();
            for (Vector3d[] c : PlanetMesher.collision(p, ch)) {
                Vector3d[] k = new Vector3d[4];
                for (int v = 0; v < 4; v++) k[v] = new Vector3d(c[v]).mul(UPB);
                tris.add(Tri.of(k[0], k[1], k[2]));
                tris.add(Tri.of(k[0], k[2], k[3]));
            }
            if (!tris.isEmpty())
                field.upsertPart(ch, new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}, KclWriter.write(tris));
        }
    }
}
