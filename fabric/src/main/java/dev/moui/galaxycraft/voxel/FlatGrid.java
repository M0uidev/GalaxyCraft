package dev.moui.galaxycraft.voxel;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A station's cells: one face of n × n columns, `layers` cells high, unit cubes turned by
 * `rotation`. Cell (i, j, k) is at station coordinate (j + ox, k + oy, i + oz): CellSpace's x along
 * j, y = k, z along i. Station coordinate (0, 0, 0) is the core's cell, centered on the origin. A
 * regrow makes a bigger box with other offsets, and every cell keeps its coordinate and place.
 */
public final class FlatGrid extends CellGrid {
    public final int ox, oy, oz;
    /** Station axes (x, y, z) to the galaxy's. */
    public final Quaterniond rotation;
    private final Quaterniond inverse;

    public FlatGrid(int n, int layers, int ox, int oy, int oz, Quaterniond rotation) {
        super(n, layers);
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.rotation = new Quaterniond(rotation).normalize();
        this.inverse = new Quaterniond(this.rotation).conjugate();
    }

    @Override
    public int faces() {
        return 1;
    }

    public int stationX(int cell) {
        return j(cell) + ox;
    }

    public int stationY(int cell) {
        return k(cell) + oy;
    }

    public int stationZ(int cell) {
        return i(cell) + oz;
    }

    /** The cell at a station coordinate, -1 outside the box. */
    public int cellOf(int sx, int sy, int sz) {
        return cellBeyond(0, sz - oz, sx - ox, sy - oy);
    }

    /** A point (blocks, from the center) in station coordinates: the core's cell spans -0.5 to 0.5. */
    public Vector3d toStation(Vector3d p) {
        return inverse.transform(new Vector3d(p));
    }

    @Override
    public Vector3d vertex(int face, int i, int j, double h) {
        return rotation.transform(new Vector3d(j + ox - 0.5, h + oy - 0.5, i + oz - 0.5));
    }

    @Override
    public Vector3d columnUp(int face, int i, int j) {
        return up();
    }

    public Vector3d up() {
        return rotation.transform(new Vector3d(0, 1, 0));
    }

    @Override
    public int cellAt(Vector3d p) {
        Vector3d s = toStation(p);
        return cellOf((int) Math.floor(s.x + 0.5), (int) Math.floor(s.y + 0.5), (int) Math.floor(s.z + 0.5));
    }

    @Override
    public int cellBeyond(int face, int i, int j, int k) {
        return i < 0 || j < 0 || k < 0 || i >= n || j >= n || k >= layers ? -1 : index(0, i, j, k);
    }

    @Override
    public int neighbor(int cell, int side) {
        int i = i(cell), j = j(cell), k = k(cell);
        return switch (side) {
            case TOP -> cellBeyond(0, i, j, k + 1);
            case BOTTOM -> cellBeyond(0, i, j, k - 1);
            case I_MINUS -> cellBeyond(0, i - 1, j, k);
            case I_PLUS -> cellBeyond(0, i + 1, j, k);
            case J_MINUS -> cellBeyond(0, i, j - 1, k);
            default -> cellBeyond(0, i, j + 1, k);
        };
    }

    @Override
    public double radiusAt(int k) {
        double r = 0;
        for (int m = 0; m < 4; m++) r = Math.max(r, vertex(0, (m & 1) * n, (m >> 1) * n, k).length());
        return r;
    }

    @Override
    public boolean inCore(Vector3d p) {
        return false;
    }

    @Override
    public boolean affine() {
        return true;
    }
}
