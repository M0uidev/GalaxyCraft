package dev.moui.galaxycraft.geom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class VoxelizerTest {
    static Vector3d v(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    /** Two CCW-from-above triangles covering [-2,2]² at height y. */
    static List<Tri> floor(double y) {
        return List.of(Tri.of(v(-2, y, -2), v(-2, y, 2), v(2, y, 2)), Tri.of(v(-2, y, -2), v(2, y, 2), v(2, y, -2)));
    }

    static boolean covered(List<double[]> boxes, double x, double y, double z) {
        return boxes.stream().anyMatch(b -> x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]);
    }

    @Test void floorBecomesThinSlab() {
        var boxes = Voxelizer.voxelize(floor(0), new double[] {-.5, -.2, -.5, .5, .5, .5});
        assertFalse(boxes.isEmpty());
        for (double[] b : boxes) assertTrue(b[4] <= 0.125 + 1e-9, "top at most one cell above the floor");
        for (double x = -0.4375; x < 0.5; x += 0.125)
            for (double z = -0.4375; z < 0.5; z += 0.125) assertTrue(covered(boxes, x, 0.0625, z), x + "," + z);
    }

    @Test void cellsAlignToEighths() {
        for (double[] b : Voxelizer.voxelize(floor(0.3), new double[] {-.5, 0, -.5, .5, 1, .5}))
            for (double c : b) assertEquals(0, (c * 8) % 1, 1e-9);
    }

    @Test void steepWallIsRaisedOneBlock() {
        var wall = List.of(Tri.of(v(0, -2, -2), v(0, 2, -2), v(0, 2, 2)), Tri.of(v(0, -2, -2), v(0, 2, 2), v(0, -2, 2)));
        var boxes = Voxelizer.voxelize(wall, new double[] {-.5, 0, -.5, .5, .5, .5});
        assertFalse(boxes.isEmpty());
        for (double[] b : boxes) assertTrue(b[4] - b[1] >= 1.0, "wall boxes are at least a block tall");
    }

    @Test void ceilingIsNotRaised() {
        var ceiling = List.of(Tri.of(v(-2, 1, -2), v(2, 1, 2), v(-2, 1, 2)), Tri.of(v(-2, 1, -2), v(2, 1, -2), v(2, 1, 2)));
        for (double[] b : Voxelizer.voxelize(ceiling, new double[] {-.5, .5, -.5, .5, 1.5, .5}))
            assertTrue(b[4] - b[1] <= 0.125 + 1e-9);
    }

    @Test void boxOutsideTrianglesIsEmpty() {
        assertTrue(Voxelizer.voxelize(floor(0), new double[] {-.5, 3, -.5, .5, 4, .5}).isEmpty());
    }

    @Test void runsAreMergedAlongX() {
        var boxes = Voxelizer.voxelize(floor(0.06), new double[] {-.5, 0, -.5, .5, .1, .5});
        assertEquals(8, boxes.size(), "one merged run per z row");
    }
}
