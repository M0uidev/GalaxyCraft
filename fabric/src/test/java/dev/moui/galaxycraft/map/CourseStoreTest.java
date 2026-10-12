package dev.moui.galaxycraft.map;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.universe.UPos;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CourseStoreTest {
    static Trip trip(double x) {
        UPos from = UPos.of(0, 0, 0, 1, 2, 3), to = UPos.of(7, -2, 9, x, 5, 6);
        Orbit o = new Orbit(to, 120, 0.5, 0.01, 300);
        return new Trip(new Course(from, to, 10, 4.5), o);
    }

    @Test void roundTrip(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("courses.json");
        CourseStore s = CourseStore.load(f);
        s.put("a", trip(1));
        s.put("b", trip(2));
        s.save();
        CourseStore t = CourseStore.load(f);
        assertEquals(s.all(), t.all());
    }

    @Test void aMissingOrDamagedFileIsEmpty(@TempDir Path dir) throws Exception {
        assertTrue(CourseStore.load(dir.resolve("nope.json")).all().isEmpty());
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{ not json [");
        assertTrue(CourseStore.load(bad).all().isEmpty());
        Path odd = dir.resolve("odd.json");
        Files.writeString(odd, "{\"a\": {\"course\": 3}}");
        assertTrue(CourseStore.load(odd).all().isEmpty());
    }

    @Test void putReplacesAndRemoveReturns(@TempDir Path dir) {
        CourseStore s = CourseStore.load(dir.resolve("c.json"));
        s.put("a", trip(1));
        s.put("a", trip(2));
        assertEquals(1, s.all().size());
        assertEquals(trip(2), s.remove("a").orElseThrow());
        assertTrue(s.remove("a").isEmpty());
    }

    @Test void countsShipsOrbitingAPlanet(@TempDir Path dir) {
        CourseStore s = CourseStore.load(dir.resolve("c.json"));
        s.put("a", trip(1));
        s.put("b", trip(1));
        s.put("c", trip(2));
        assertEquals(2, s.orbitingAround(trip(1).orbit().center()));
        assertEquals(0, s.orbitingAround(UPos.of(new Vector3d(5, 5, 5))));
    }
}
