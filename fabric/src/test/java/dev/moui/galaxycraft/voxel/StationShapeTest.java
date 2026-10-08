package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class StationShapeTest {
    @Test void starterGridHasSlackAndIsAligned() {
        StationShape.Size s = StationShape.sizeFor(StationShape.starter());
        assertEquals(0, s.n() % 8);
        assertTrue(s.ox() <= -4 - 16 && s.ox() + s.n() - 1 >= 4 + 16);
        assertTrue(s.oz() <= -4 - 16 && s.oz() + s.n() - 1 >= 4 + 16);
        assertTrue(s.oy() <= -16 && s.oy() + s.layers() - 1 >= 16);
        assertTrue(s.oy() >= StationShape.MIN_Y && s.oy() + s.layers() - 1 <= StationShape.MAX_Y);
    }

    @Test void maxSpanAndVerticalRangeAreRefused() {
        StationShape.Bounds b = new StationShape.Bounds(-100, 0, 0, 155, 0, 0); // 256 wide on x
        assertTrue(StationShape.allowed(b, 155, 0, 0));
        assertFalse(StationShape.allowed(b, 156, 0, 0));
        assertFalse(StationShape.allowed(b, -101, 0, 0));
        assertTrue(StationShape.allowed(StationShape.starter(), 0, 79, 0));
        assertFalse(StationShape.allowed(StationShape.starter(), 0, 80, 0));
        assertTrue(StationShape.allowed(StationShape.starter(), 0, -48, 0));
        assertFalse(StationShape.allowed(StationShape.starter(), 0, -49, 0));
    }

    @Test void boundsGrow() {
        StationShape.Bounds b = StationShape.starter().with(10, 3, -7);
        assertEquals(new StationShape.Bounds(-4, 0, -7, 10, 3, 4), b);
        assertEquals(15, b.spanX());
        assertTrue(b.contains(10, 3, -7));
        assertFalse(b.contains(11, 3, -7));
    }

    @Test void nearEdgeTriggersRegrow() {
        StationShape.Size s = StationShape.sizeFor(StationShape.starter());
        FlatGrid g = new FlatGrid(s.n(), s.layers(), s.ox(), s.oy(), s.oz(), new Quaterniond());
        assertFalse(StationShape.nearEdge(g, 0, 1, 0));
        assertTrue(StationShape.nearEdge(g, s.ox() + 2, 0, 0));
        assertTrue(StationShape.nearEdge(g, 0, 0, s.oz() + s.n() - 3));
        assertTrue(StationShape.nearEdge(g, 0, s.oy() + s.layers() - 2, 0));
        assertTrue(StationShape.nearEdge(g, s.ox() - 5, 0, 0)); // outside the box: regrow too
    }

    @Test void theVerticalLimitsNeverAskForARegrow() {
        StationShape.Size s = StationShape.sizeFor(new StationShape.Bounds(-4, -48, -4, 4, 79, 4));
        FlatGrid g = new FlatGrid(s.n(), s.layers(), s.ox(), s.oy(), s.oz(), new Quaterniond());
        assertFalse(StationShape.nearEdge(g, 0, 79, 0));
        assertFalse(StationShape.nearEdge(g, 0, -48, 0));
    }

    @Test void sizeNeverPassesTheMaximum() {
        StationShape.Size s = StationShape.sizeFor(new StationShape.Bounds(-128, -48, -128, 127, 79, 127));
        assertTrue(s.n() <= 256 + 2 * 16 + 8);
        assertEquals(StationShape.MIN_Y, s.oy());
        assertEquals(128, s.layers());
    }
}
