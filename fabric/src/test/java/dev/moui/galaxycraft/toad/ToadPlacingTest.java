package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.gen.Huts;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ToadPlacingTest {
    @Test void feetOnTheFloorUpAwayFromThePlanetFacingOut() {
        Huts.Placed p = new Huts.Placed("toad-p1-0", new double[] {0, 1, 0}, 100, new double[] {0, 0, 1});
        ToadRecord r = ToadPlacing.record("stage", ToadType.MINER, p, new Vector3d(10, 20, 30), 4);
        assertEquals("toad-p1-0", r.id());
        assertEquals(ToadType.MINER, r.type());
        assertEquals("stage", r.stage());
        assertArrayEquals(new double[] {10, 420, 30}, r.pos(), 1e-9);
        Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
        Vector3d up = new Vector3d(0, 1, 0).rotate(q), fwd = new Vector3d(0, 0, 1).rotate(q);
        assertEquals(0, up.distance(new Vector3d(0, 1, 0)), 1e-9);
        assertEquals(0, fwd.distance(new Vector3d(0, 0, 1)), 1e-9);
    }

    @Test void aTiltedSpotKeepsUpAndFacing() {
        Vector3d up = new Vector3d(1, 1, 0).normalize(), fwd = new Vector3d(0, 0, 1);
        Huts.Placed p = new Huts.Placed("t", new double[] {up.x, up.y, up.z}, 50, new double[] {fwd.x, fwd.y, fwd.z});
        ToadRecord r = ToadPlacing.record("s", ToadType.EXPLORER, p, new Vector3d(), 1);
        Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
        assertEquals(0, new Vector3d(0, 1, 0).rotate(q).distance(up), 1e-9);
        assertEquals(0, new Vector3d(0, 0, 1).rotate(q).distance(fwd), 1e-9);
    }
}
