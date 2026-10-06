package dev.moui.galaxycraft.gravity;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class UpTurnTest {
    @Test void aTurnStartsGentlySpeedsUpAndSlowsDownToItsEnd() {
        UpTurn t = new UpTurn();
        Vector3d up = new Vector3d(0, 1, 0), to = new Vector3d(0.2, -1, 0.1).normalize();
        double lastStep = 0;
        int ticks = 0;
        boolean slowing = false;
        while (up.angle(to) > 1e-9 && ticks < 400) {
            Vector3d next = t.step(up, to);
            double step = up.angle(next);
            assertTrue(step <= UpTurn.MAX_PER_TICK + 1e-12, "never faster than the most");
            assertTrue(step - lastStep <= UpTurn.ACCEL_PER_TICK + 1e-12, "no sudden speed-up at tick " + ticks);
            if (step < lastStep - 1e-12) slowing = true;
            else assertFalse(slowing && step > lastStep + 1e-9, "once slowing it does not speed up again");
            lastStep = step;
            up = next;
            ticks++;
        }
        assertTrue(ticks < 400, "it gets there");
        assertTrue(slowing, "it slows into its end");
    }

    @Test void stillWhenThereIsNoTurn() {
        UpTurn t = new UpTurn();
        Vector3d up = new Vector3d(0, 1, 0);
        assertTrue(t.step(up, up).distance(up) < 1e-12);
    }
}
