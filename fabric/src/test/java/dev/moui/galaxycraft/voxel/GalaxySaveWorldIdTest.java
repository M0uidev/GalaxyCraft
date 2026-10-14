package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GalaxySaveWorldIdTest {
    @Test
    void createdOnceAndStable(@TempDir Path dir) {
        java.util.UUID a = GalaxySave.of(dir).worldId();
        assertEquals(a, GalaxySave.of(dir).worldId());
        assertTrue(Files.exists(dir.resolve("galaxycraft").resolve("world-id")));
    }

    @Test
    void aCorruptFileIsReplaced(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("galaxycraft"));
        Files.writeString(dir.resolve("galaxycraft").resolve("world-id"), "not a uuid");
        java.util.UUID id = GalaxySave.of(dir).worldId();
        assertEquals(id, GalaxySave.of(dir).worldId(), "the replacement was saved");
    }

    @Test
    void galaxyTravelsAsJson(@TempDir Path dir) throws Exception {
        GalaxySave host = GalaxySave.of(dir);
        assertTrue(host.galaxyJson().isEmpty(), "nothing yet");
        GalaxyCatalog.Entry e = new GalaxyCatalog.Entry(0, 0, 0, 0, 48, GalaxyCatalog.Kind.GENERATED, "random", null, 7L);
        GalaxySave.Galaxy g = new GalaxySave.Galaxy(1, GalaxyCatalog.Options.defaults(42), List.of(e));
        host.writeGalaxy(g);
        String json = host.galaxyJson().orElseThrow();
        assertEquals(g, GalaxySave.parseGalaxy(json).orElseThrow());
    }

    @Test
    void garbageIsNotAGalaxy() {
        assertTrue(GalaxySave.parseGalaxy("").isEmpty());
        assertTrue(GalaxySave.parseGalaxy("{").isEmpty());
        assertTrue(GalaxySave.parseGalaxy("{\"version\":1}").isEmpty());
        assertTrue(GalaxySave.parseGalaxy("[]").isEmpty());
    }
}
