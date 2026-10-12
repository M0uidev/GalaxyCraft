package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisitedTest {
    @TempDir Path dir;

    @Test void homeIsAlwaysVisited() {
        assertTrue(Visited.load(dir.resolve("visited.json")).has(Universe.Sector.HOME));
    }

    @Test void markedSectorsSurviveSaveAndLoad() throws Exception {
        Path f = dir.resolve("visited.json");
        Visited v = Visited.load(f);
        var s = new Universe.Sector(3, -2, 7);
        assertFalse(v.has(s));
        assertTrue(v.mark(s));
        assertFalse(v.mark(s));
        v.save();
        assertTrue(Visited.load(f).has(s));
    }

    @Test void aCorruptFileLoadsEmpty() throws Exception {
        Path f = dir.resolve("visited.json");
        Files.writeString(f, "{not json");
        Visited v = Visited.load(f);
        assertTrue(v.has(Universe.Sector.HOME));
        assertFalse(v.has(new Universe.Sector(1, 0, 0)));
    }
}
