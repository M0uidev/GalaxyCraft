package dev.moui.galaxycraft.collision;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.gravity.GravityFrame;
import java.io.InputStream;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CollisionFieldTest {
    static final double[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    static byte[] icosphere() throws Exception {
        try (InputStream in = CollisionFieldTest.class.getResourceAsStream("/icosphere.kcl")) {
            return in.readAllBytes();
        }
    }

    static GravityFrame frameAbove(Vector3d gal) {
        return new GravityFrame(gal, new Vector3d(0, 100, 0), new Vector3d(gal).normalize().negate());
    }

    @Test void noFrameMeansNoBoxes() throws Exception {
        var f = new CollisionField();
        f.upsertPart(1, IDENTITY, icosphere());
        assertTrue(f.boxesFor(new double[] {-.3, 99.5, -.3, .3, 100.1, .3}).isEmpty());
    }

    @Test void groundUnderFeetOnTopOfPlanet() throws Exception {
        var f = new CollisionField();
        f.upsertPart(1, IDENTITY, icosphere());
        f.setFrame(frameAbove(new Vector3d(0, 820, 0)));
        var boxes = f.boxesFor(new double[] {-.3, 99.5, -.3, .3, 100.1, .3});
        assertFalse(boxes.isEmpty());
        double top = boxes.stream().mapToDouble(b -> b[4]).max().orElseThrow();
        assertTrue(top >= 99.75 && top <= 100.0, "top=" + top);
    }

    @Test void groundBelowOnlyWhenLoadedAndClose() throws Exception {
        var f = new CollisionField();
        f.setFrame(frameAbove(new Vector3d(0, 820, 0))); // feet at y=100 on top of the planet
        assertFalse(f.hasGroundBelow(new double[] {0, 100, 0}, 6), "no parts yet");
        f.upsertPart(1, IDENTITY, icosphere());
        assertTrue(f.hasGroundBelow(new double[] {0, 100, 0}, 6));
        assertTrue(f.hasGroundBelow(new double[] {0, 104, 0}, 6), "mid-jump, ground 4 blocks down");
        assertFalse(f.hasGroundBelow(new double[] {0, 120, 0}, 6), "ground 20 blocks down");
        f.setFrame(null);
        assertFalse(f.hasGroundBelow(new double[] {0, 100, 0}, 6), "no frame");
    }

    @Test void groundUnderFeetOnTheSideOfPlanet() throws Exception {
        var f = new CollisionField();
        f.upsertPart(1, IDENTITY, icosphere());
        f.setFrame(frameAbove(new Vector3d(820, 0, 0)));  // gravity points to -X in galaxy space
        var boxes = f.boxesFor(new double[] {-.3, 99.5, -.3, .3, 100.1, .3});
        double top = boxes.stream().mapToDouble(b -> b[4]).max().orElseThrow();
        assertTrue(top >= 99.75 && top <= 100.0, "top=" + top);
    }

    @Test void matrixPlacesPart() throws Exception {
        var f = new CollisionField();
        f.upsertPart(2, new double[] {1, 0, 0, 0, 0, 1, 0, 2600, 0, 0, 1, 0}, icosphere());
        f.setFrame(frameAbove(new Vector3d(0, 820, 0)));
        assertTrue(f.boxesFor(new double[] {-.3, 99.5, -.3, .3, 100.1, .3}).isEmpty(), "planet moved away");
        f.setFrame(frameAbove(new Vector3d(0, 3420, 0)));  // 20 units above its top
        assertFalse(f.boxesFor(new double[] {-.3, 99.5, -.3, .3, 100.1, .3}).isEmpty());
    }

    @Test void removePartClearsIt() throws Exception {
        var f = new CollisionField();
        f.upsertPart(1, IDENTITY, icosphere());
        f.setFrame(frameAbove(new Vector3d(0, 820, 0)));
        f.removePart(1);
        assertTrue(f.boxesFor(new double[] {-.3, 99.5, -.3, .3, 100.1, .3}).isEmpty());
    }

    @Test void hugeQueryIsClipped() throws Exception {
        var f = new CollisionField();
        f.upsertPart(1, IDENTITY, icosphere());
        f.setFrame(frameAbove(new Vector3d(0, 820, 0)));
        for (double[] b : f.boxesFor(new double[] {-500, 0, -500, 500, 200, 500})) {
            assertTrue(Math.abs(b[0]) <= 4.5 && Math.abs(b[3]) <= 4.5, "x clipped to 8 blocks around center");
        }
    }
}
