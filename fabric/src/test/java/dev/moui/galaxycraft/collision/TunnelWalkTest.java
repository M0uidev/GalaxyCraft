package dev.moui.galaxycraft.collision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moui.galaxycraft.geom.Tri;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.kcl.KclWriter;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetMesher;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Minecraft movement through a one-wide, two-high tunnel dug into a planet: the player's box
 * (0.6 x 1.8, square to Minecraft's axes) walks it end to end once Minecraft's axes follow the
 * blocks (GravityFrame.alignGrid); at the angle they meet otherwise, it snags on the walls.
 */
class TunnelWalkTest {
    private static final double UPB = 1 / GravityFrame.SCALE;
    private static final int LENGTH = 14;

    private final VoxelPlanet p = VoxelPlanet.ofRadius(32);
    private final CollisionField field = new CollisionField();
    {
        field.setBlockParts(m -> true);
        field.setBlockSource((f, q, out) -> dev.moui.galaxycraft.voxel.PlanetCollision.boxes(p,
                l -> new Vector3d(l).mul(UPB), gal -> new Vector3d(gal).div(UPB), f, q, out));
    }
    private final int face = 0, j0, i0, kFloor;

    TunnelWalkTest() {
        int n = p.grid.n;
        i0 = n / 5;
        j0 = n / 3;
        kFloor = p.depth - 3; // two cells of air under the top block: a roofed tunnel
        for (int i = i0; i < i0 + LENGTH; i++)
            for (int k = kFloor; k < kFloor + 2; k++) p.set(p.grid.index(face, i, j0, k), Material.AIR);
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

    @Test void alignedWalksTheWholeTunnel() {
        assertEquals(0, walk(true), "ticks the walls stopped the player");
    }

    @Test void leaningGravityStillWalksIt() {
        // SMG2's gravity leans off the blocks' up (deep in a planet, by its core): up comes from
        // the blocks where the player is, as the client takes it, and the tunnel still fits.
        lean = Math.toRadians(25);
        assertEquals(0, walk(true), "ticks the walls stopped the player");
    }

    @Test void leaningGravityAsUpSnags() {
        lean = Math.toRadians(25);
        gridUp = false;
        assertTrue(walk(true) > 0, "blocks leaning off Minecraft's up collide wider than they are");
    }

    private double lean = 0;
    private boolean gridUp = true;

    @Test void unalignedSnags() {
        assertTrue(walk(false) > 0, "the angle the tunnel meets Minecraft's axes at narrows it");
    }

    @Test void alignmentKeepsThePlayerAndTheLook() {
        Vector3d gal = galOf(p.grid.index(face, i0 + 2, j0, kFloor), 0.5, 0.01, 0.5);
        Vector3d feet = new Vector3d(3.3, 100, -7.9);
        GravityFrame f = new GravityFrame(gal, feet, new Vector3d(gal).normalize().negate());
        Vector3d look = new Vector3d(0.6, 0, 0.8);
        Vector3d lookGal = f.dirToGal(look);
        int cell = p.grid.index(face, i0 + 2, j0, kFloor);
        Vector3d corner = new Vector3d(p.grid.corner(cell, 0, 0, 0)).mul(UPB);
        Vector3d edge = new Vector3d(p.grid.corner(cell, 1, 0, 0)).mul(UPB).sub(corner);
        GravityFrame.Align a = f.alignGrid(edge, corner, feet);
        Vector3d moved = new Vector3d(feet).add(a.shift());
        assertTrue(f.toGal(moved).distance(gal) < 1e-6, "the player stays put in the galaxy");
        Vector3d c = f.toMc(corner);
        assertEquals(Math.round(c.x), c.x, 1e-9);
        assertEquals(Math.round(c.y), c.y, 1e-9);
        assertEquals(Math.round(c.z), c.z, 1e-9);
        Vector3d e = f.dirToMc(edge).normalize();
        assertTrue(Math.min(Math.abs(e.x), Math.abs(e.z)) < 1e-9, "the block edge runs along X or Z: " + e);
        // The look, turned as the client turns it (Ry(yaw)), still points where it did in the galaxy.
        Vector3d turned = new org.joml.Quaterniond().rotationY(a.yaw()).transform(new Vector3d(look));
        assertTrue(f.dirToGal(turned).distance(lookGal) < 1e-9);
    }

    @Test void walkingTheSurfaceNeverSinksIntoTheGround() {
        int start = p.grid.index(face, 3, 5, p.depth);
        Vector3d gal = galOf(start, 0.5, 0.0, 0.5);
        Vector3d feet = new Vector3d(0.5, 100, 0.5);
        GravityFrame frame = new GravityFrame(gal, feet, new Vector3d(gal).normalize().negate());
        int sunk = 0;
        double worst = 0;
        for (int tick = 0; tick < 1500; tick++) {
            Vector3d g = frame.toGal(feet);
            int here = p.grid.cellAt(frame.toGal(new Vector3d(feet).add(0, 0.5, 0)).div(UPB));
            Vector3d up = CellSpace.point(p.grid, here, 0.5, 1, 0.5).sub(CellSpace.point(p.grid, here, 0.5, 0, 0.5)).normalize();
            Vector3d smg2 = new Vector3d(g).normalize().negate();
            if (lean != 0) smg2 = new org.joml.AxisAngle4d(lean, new Vector3d(smg2).cross(0, 0, 1).normalize()).transform(smg2);
            frame.update(new Vector3d(lean != 0 && !gridUp ? smg2 : gridUp && lean != 0 ? up : new Vector3d(g).normalize()).negate(), feet);
            int cell = p.grid.cellAt(frame.toGal(new Vector3d(feet).add(0, 0.5, 0)).div(UPB));
            Vector3d corner = new Vector3d(p.grid.corner(cell, 0, 0, 0)).mul(UPB);
            Vector3d edge = new Vector3d(p.grid.corner(cell, 1, 0, 0)).mul(UPB).sub(corner);
            feet.add(frame.alignGrid(edge, corner, feet).shift());
            field.setFrame(frame);
            feet.add(field.pushOut(new double[] {feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3}, 0.6, 0.25));
            double inside = highest(feet, 0.0, 0.6);
            if (!Double.isNaN(inside) && inside > feet.y + 1e-7) {
                sunk++;
                worst = Math.max(worst, inside - feet.y);
            }
            double floor = highest(feet, -0.5, 0.0);
            if (!Double.isNaN(floor)) feet.y = Math.max(feet.y - 0.5, floor);
            Vector3d dir = new Vector3d(Math.cos(tick * 0.01), 0, Math.sin(tick * 0.01)).mul(0.2);
            double top = highest(new Vector3d(feet).add(dir), 0.0, 0.6);
            feet.add(dir);
            if (!Double.isNaN(top)) feet.y = Math.max(feet.y, top);
        }
        assertEquals(0, sunk, "ticks the ground's top stood over the feet (worst " + worst + ")");
    }

    /** Walks the tunnel's length; returns the ticks a wall stopped it. */
    private int walk(boolean align) {
        int start = p.grid.index(face, i0, j0, kFloor);
        Vector3d gal = galOf(start, 0.5, 0.0, 0.5);
        Vector3d feet = new Vector3d(0.5, 100, 0.5);
        GravityFrame frame = new GravityFrame(gal, feet, new Vector3d(gal).normalize().negate());
        int blocked = 0;
        for (int tick = 0; tick < 200; tick++) {
            Vector3d g = frame.toGal(feet);
            int here = p.grid.cellAt(frame.toGal(new Vector3d(feet).add(0, 0.5, 0)).div(UPB));
            Vector3d up = CellSpace.point(p.grid, here, 0.5, 1, 0.5).sub(CellSpace.point(p.grid, here, 0.5, 0, 0.5)).normalize();
            Vector3d smg2 = new Vector3d(g).normalize().negate();
            if (lean != 0) smg2 = new org.joml.AxisAngle4d(lean, new Vector3d(smg2).cross(0, 0, 1).normalize()).transform(smg2);
            frame.update(new Vector3d(lean != 0 && !gridUp ? smg2 : gridUp && lean != 0 ? up : new Vector3d(g).normalize()).negate(), feet);
            int cell = p.grid.cellAt(frame.toGal(new Vector3d(feet).add(0, 0.5, 0)).div(UPB));
            if (cell >= 0 && p.grid.i(cell) >= i0 + LENGTH - 2) return blocked; // through
            Vector3d corner = new Vector3d(p.grid.corner(cell, 0, 0, 0)).mul(UPB);
            Vector3d edge = new Vector3d(p.grid.corner(cell, 1, 0, 0)).mul(UPB).sub(corner);
            if (align) {
                feet.add(frame.alignGrid(edge, corner, feet).shift());
            } else if (tick == 0) {
                // The blocks 30° off Minecraft's axes, as they may be anywhere on a planet.
                Vector3d tilted = new org.joml.AxisAngle4d(Math.toRadians(30), new Vector3d(g).normalize()).transform(new Vector3d(edge));
                feet.add(frame.alignGrid(tilted, corner, feet).shift());
            }
            field.setFrame(frame);
            // Down onto the floor (falling at most 0.5 a tick).
            double floor = highest(feet, -0.5, 0.0);
            if (!Double.isNaN(floor)) feet.y = Math.max(feet.y - 0.5, floor);
            // Along the tunnel, 0.2 a tick, stepping up at most 0.6.
            Vector3d dir = frame.dirToMc(edge);
            dir.y = 0;
            dir.normalize(0.2);
            Vector3d next = new Vector3d(feet).add(dir);
            double top = highest(next, 0.0, 1.8);
            if (!Double.isNaN(top) && top > feet.y + 0.6 + 1e-9) {
                blocked++;
                continue;
            }
            if (!Double.isNaN(top) && overlapsAbove(next, Math.max(top, feet.y))) {
                blocked++;
                continue;
            }
            feet.set(next.x, Double.isNaN(top) ? feet.y : Math.max(top, feet.y), next.z);
        }
        return blocked + 1000; // never got through
    }

    private Vector3d galOf(int cell, double x, double y, double z) {
        return CellSpace.point(p.grid, cell, x, y, z).mul(UPB);
    }

    /** Highest box top over the player's footprint at feet, among boxes reaching y+from..y+to. */
    private double highest(Vector3d feet, double from, double to) {
        double best = Double.NaN;
        for (double[] b : boxes(feet, feet.y + from, feet.y + to))
            best = Double.isNaN(best) ? b[4] : Math.max(best, b[4]);
        return best;
    }

    /** Whether a box overlaps the player standing at y (head room). */
    private boolean overlapsAbove(Vector3d feet, double y) {
        for (double[] b : boxes(feet, y + 1e-6, y + 1.8)) if (b[1] < y + 1.8 - 1e-6 && b[4] > y + 1e-6) return true;
        return false;
    }

    private List<double[]> boxes(Vector3d feet, double lo, double hi) {
        List<double[]> out = new ArrayList<>();
        for (double[] b : field.boxesFor(new double[] {feet.x - 0.3, lo, feet.z - 0.3, feet.x + 0.3, hi, feet.z + 0.3}))
            if (b[3] > feet.x - 0.3 + 1e-9 && b[0] < feet.x + 0.3 - 1e-9 && b[5] > feet.z - 0.3 + 1e-9
                    && b[2] < feet.z + 0.3 - 1e-9 && b[1] < hi && b[4] > lo)
                out.add(b);
        return out;
    }
}
