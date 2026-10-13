package dev.moui.galaxycraft.launchstar;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StarStoreTest {
    @TempDir Path dir;

    static StarRecord star(String id, String body) {
        return new StarRecord(id, new double[] {1, 2, 3}, new double[] {0, 1, 0}, "GalaxyCraftSpace", body, null);
    }

    @Test void secondStarOnAnotherBodyLinksToTheFirst() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        s.place(star("a", "p1"));
        assertNull(s.get("a").partner());
        StarRecord b = s.place(star("b", "p2"));
        assertEquals("a", b.partner());
        assertEquals("b", s.get("a").partner());
        assertEquals("b", s.partnerOf("a").orElseThrow().id());
    }

    @Test void sameBodyDoesNotLink() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        s.place(star("a", "p1"));
        assertNull(s.place(star("b", "p1")).partner());
        assertEquals("a", s.place(star("c", "p2")).partner());
    }

    @Test void removingUnlinksPartnerAndANewStarRelinks() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        s.place(star("a", "p1"));
        s.place(star("b", "p2"));
        s.remove("b");
        assertNull(s.get("a").partner());
        assertEquals("a", s.place(star("c", "p3")).partner());
    }

    @Test void roundTrip() throws IOException {
        Path f = dir.resolve("stars.json");
        StarStore s = StarStore.load(f);
        s.place(star("a", "p1"));
        s.place(star("b", "p2"));
        s.save();
        StarStore t = StarStore.load(f);
        assertEquals(2, t.all().size());
        assertEquals("b", t.get("a").partner());
        assertArrayEquals(new double[] {0, 1, 0}, t.get("a").up());
    }

    @Test void damagedFileLoadsEmpty() throws IOException {
        Path f = dir.resolve("stars.json");
        Files.writeString(f, "{ not json");
        assertTrue(StarStore.load(f).all().isEmpty());
    }
}
