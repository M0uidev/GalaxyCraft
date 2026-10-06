package dev.moui.galaxycraft.gravity;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class LookMathTest {
    @Test void directionMatchesMinecraftConvention() {
        // yaw 0 looks to +Z, yaw 90 looks to -X, pitch 90 looks down.
        assertTrue(LookMath.direction(0, 0).distance(new Vector3d(0, 0, 1)) < 1e-9);
        assertTrue(LookMath.direction(90, 0).distance(new Vector3d(-1, 0, 0)) < 1e-9);
        assertTrue(LookMath.direction(0, 90).distance(new Vector3d(0, -1, 0)) < 1e-9);
    }

    @Test void aLookTurnedKeepsItsDirectionAndTheNearestYaw() {
        org.joml.Quaterniond q = new org.joml.Quaterniond().rotationX(Math.toRadians(20));
        double[] t = LookMath.turned(170, 10, q);
        org.joml.Vector3d want = q.transform(LookMath.direction(170, 10));
        assertTrue(LookMath.direction(t[0], t[1]).distance(want) < 1e-9);
        assertTrue(Math.abs(t[0] - 170) <= 180, "no spin through 180: " + t[0]);
        double[] wrap = LookMath.turned(179, 0, new org.joml.Quaterniond().rotationY(Math.toRadians(-3)));
        assertEquals(182, wrap[0], 1e-6, "past 180 it goes on, not back to -178");
    }

    @Test void aLookKeptThroughAFramesTurnIsDrawnWithoutAJumpAtTheTick() {
        GravityFrame f = new GravityFrame(new org.joml.Vector3d(), new org.joml.Vector3d(0, 100, 0), new org.joml.Vector3d(0, -1, 0));
        double yaw = 30, pitch = 10;
        f.startTick();
        org.joml.Vector3d before = f.dirToGal(LookMath.direction(yaw, pitch)); // the galaxy look at the end of last tick
        GravityFrame.Update u = f.update(new org.joml.Vector3d(0.2, -1, 0.1), new org.joml.Vector3d(0, 100, 0));
        double[] kept = LookMath.turned(yaw, pitch, u.deltaMc());
        double[] old = LookMath.keptOld(yaw, pitch, u.deltaMc()); // what the turn leaves last tick's look as
        // Drawn at the start of this tick: last tick's look; at its end: the same galaxy look.
        assertTrue(f.dirToGal(LookMath.direction(old[0], old[1]), 0).distance(before) < 1e-9, "no jump at the tick");
        assertTrue(f.dirToGal(LookMath.direction(kept[0], kept[1]), 1).distance(before) < 1e-9, "the look kept in the galaxy");
    }
}
