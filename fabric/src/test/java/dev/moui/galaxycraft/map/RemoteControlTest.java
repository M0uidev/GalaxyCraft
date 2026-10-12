package dev.moui.galaxycraft.map;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.universe.UPos;
import java.nio.file.Path;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteControlTest {
    static final double U = 80;
    static final UPos HOME = UPos.ZERO, PLANET = UPos.of(new Vector3d(80_000, 0, 0));

    static CourseStore store(Path d) {
        return CourseStore.load(d.resolve("c.json"));
    }

    @Test void aShipTravelsThenOrbits(@TempDir Path d) {
        CourseStore s = store(d);
        Trip t = RemoteControl.send(s, "a", HOME, PLANET, 64, U, 50, 1000);
        assertEquals(t, s.get("a").orElseThrow());
        assertFalse(t.arrived(1000));
        long end = t.course().startTick() + t.course().durationTicks();
        double r = t.orbit().radius();
        assertTrue(r > 64 * U); // outside the planet
        assertEquals(r, t.positionAt(end + 500).minus(PLANET).length(), 1e-3);
        assertEquals(0, t.positionAt(end).minus(t.course().to()).length(), 1e-3);
    }

    @Test void aShipAlreadyAtItsOrbitPointArrivesWithinATick(@TempDir Path d) {
        CourseStore s = store(d);
        Trip first = RemoteControl.send(s, "a", HOME, PLANET, 64, U, 50, 0);
        long end = first.course().durationTicks();
        Trip again = RemoteControl.send(s, "a", HOME, PLANET, 64, U, 50, end);
        assertTrue(again.course().durationTicks() <= 1);
        assertTrue(Double.isFinite(again.positionAt(end).x()));
        assertTrue(Double.isFinite(again.positionAt(end + 5).x()));
    }

    @Test void aReplacedCourseStartsFromWhereTheShipIsNow(@TempDir Path d) {
        CourseStore s = store(d);
        Trip first = RemoteControl.send(s, "a", HOME, PLANET, 64, U, 10, 0);
        UPos now = first.positionAt(2000);
        assertTrue(now.minus(HOME).length() > 0);
        UPos other = UPos.of(new Vector3d(0, 0, 90_000));
        Trip second = RemoteControl.send(s, "a", HOME, other, 64, U, 10, 2000);
        assertEquals(0, second.course().from().minus(now).length(), 1e-6);
        assertEquals(1, s.all().size());
    }

    @Test void twoShipsOnOnePlanetHaveDifferentPhases(@TempDir Path d) {
        CourseStore s = store(d);
        Trip a = RemoteControl.send(s, "a", HOME, PLANET, 64, U, 50, 0);
        Trip b = RemoteControl.send(s, "b", HOME, PLANET, 64, U, 50, 0);
        assertNotEquals(a.orbit().phase(), b.orbit().phase(), 1e-9);
        long t = Math.max(a.orbit().epochTick(), b.orbit().epochTick()) + 100;
        assertTrue(a.positionAt(t).minus(b.positionAt(t)).length() > 1);
    }

    @Test void cancelStopsTheShipWhereItIsAndForgetsTheTrip(@TempDir Path d) {
        CourseStore s = store(d);
        Trip t = RemoteControl.send(s, "a", HOME, PLANET, 64, U, 10, 0);
        UPos at = RemoteControl.cancel(s, "a", HOME, 100);
        assertEquals(0, at.minus(t.positionAt(100)).length(), 1e-6);
        assertTrue(s.get("a").isEmpty());
        assertEquals(0, RemoteControl.cancel(s, "a", HOME, 200).minus(HOME).length(), 1e-9); // no trip: stays recorded
    }
}
