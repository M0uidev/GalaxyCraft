package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TileSplitTest {
    private static final float E = 1e-5f;

    /** A face 1 block wide along x and 1 tall along y, at z = 0; corners counter-clockwise from (0,0). */
    private static final float[] FACE = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};

    @Test void aFaceInsideOneCellStaysWhole() {
        float[] uv = {2, 14, 12, 14, 12, 4, 2, 4};
        List<TileSplit.Piece> pieces = TileSplit.split(FACE, uv);
        assertEquals(1, pieces.size());
        TileSplit.Piece p = pieces.get(0);
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertArrayEquals(FACE, p.pos(), E);
        assertArrayEquals(new float[] {2 / 16f, 14 / 16f, 12 / 16f, 14 / 16f, 12 / 16f, 4 / 16f, 2 / 16f, 4 / 16f}, p.uv(), E);
    }

    @Test void aFaceAcrossTwoCellsIsCutWhereTheTexturesCellsMeet() {
        // u from 8 to 24 along x: the cut at u = 16 is halfway across.
        float[] uv = {8, 16, 24, 16, 24, 0, 8, 0};
        List<TileSplit.Piece> pieces = TileSplit.split(FACE, uv);
        assertEquals(2, pieces.size());
        TileSplit.Piece left = pieces.stream().filter(p -> p.cellX() == 0).findFirst().orElseThrow();
        TileSplit.Piece right = pieces.stream().filter(p -> p.cellX() == 1).findFirst().orElseThrow();
        assertArrayEquals(new float[] {0, 0, 0, 0.5f, 0, 0, 0.5f, 1, 0, 0, 1, 0}, left.pos(), E);
        assertArrayEquals(new float[] {0.5f, 1, 1, 1, 1, 0, 0.5f, 0}, left.uv(), E);
        assertArrayEquals(new float[] {0.5f, 0, 0, 1, 0, 0, 1, 1, 0, 0.5f, 1, 0}, right.pos(), E);
        assertArrayEquals(new float[] {0, 1, 0.5f, 1, 0.5f, 0, 0, 0}, right.uv(), E);
    }

    @Test void aFaceAcrossFourCellsGivesFourPiecesCoveringIt() {
        float[] uv = {8, 24, 24, 24, 24, 8, 8, 8};
        List<TileSplit.Piece> pieces = TileSplit.split(FACE, uv);
        assertEquals(4, pieces.size());
        double area = 0;
        for (TileSplit.Piece p : pieces) {
            float w = p.pos()[3] - p.pos()[0], h = p.pos()[7] - p.pos()[4];
            area += Math.abs(w * h);
            assertTrue(p.cellX() >= 0 && p.cellX() <= 1 && p.cellY() >= 0 && p.cellY() <= 1);
        }
        assertEquals(1, area, E);
    }

    @Test void aMirroredFaceKeepsItsCornersAndCells() {
        // u runs backward along x (Minecraft mirrors cube faces): 24 at x = 0, 8 at x = 1.
        float[] uv = {24, 16, 8, 16, 8, 0, 24, 0};
        List<TileSplit.Piece> pieces = TileSplit.split(FACE, uv);
        assertEquals(2, pieces.size());
        TileSplit.Piece near = pieces.stream().filter(p -> p.cellX() == 1).findFirst().orElseThrow();
        assertArrayEquals(new float[] {0, 0, 0, 0.5f, 0, 0, 0.5f, 1, 0, 0, 1, 0}, near.pos(), E);
        assertArrayEquals(new float[] {0.5f, 1, 0, 1, 0, 0, 0.5f, 0}, near.uv(), E);
    }

    @Test void aFaceWhoseUGoesUpTheSideIsSplitAlongIt() {
        // u along y and v along x (a rotated face): u 0..32 up the face, v 0..8 across.
        float[] uv = {0, 0, 0, 8, 32, 8, 32, 0};
        List<TileSplit.Piece> pieces = TileSplit.split(FACE, uv);
        assertEquals(2, pieces.size());
        for (TileSplit.Piece p : pieces) {
            assertEquals(0, p.cellY());
            float y0 = Math.min(p.pos()[1], p.pos()[7]), y1 = Math.max(p.pos()[1], p.pos()[7]);
            assertEquals(p.cellX() == 0 ? 0 : 0.5f, y0, E);
            assertEquals(p.cellX() == 0 ? 0.5f : 1, y1, E);
        }
    }

    @Test void aFaceWithNoTextureAreaGivesNothing() {
        float[] uv = {4, 4, 4, 4, 4, 4, 4, 4};
        assertTrue(TileSplit.split(FACE, uv).isEmpty());
    }
}
