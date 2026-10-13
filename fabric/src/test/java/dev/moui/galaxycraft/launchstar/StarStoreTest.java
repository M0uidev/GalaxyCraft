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
        return new StarRecord(id, new double[] {1, 2, 3}, new double[] {0, 1, 0}, "GalaxyCraftSpace", body, "");
    }

    @Test void aLoneStarGetsADefaultCodeAndNoPartner() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        StarRecord a = s.place(star("a", "p1"));
        assertEquals("star-1", a.code());
        assertTrue(s.partnerOf("a").isEmpty());
    }

    @Test void secondStarOnAnotherBodyTakesTheWaitingCodeAndLinks() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        s.place(star("a", "p1"));
        StarRecord b = s.place(star("b", "p2"));
        assertEquals("star-1", b.code());
        assertEquals("b", s.partnerOf("a").orElseThrow().id());
        assertEquals("a", s.partnerOf("b").orElseThrow().id());
    }

    @Test void sameBodyDoesNotLinkAndGetsItsOwnCode() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        s.place(star("a", "p1"));
        StarRecord b = s.place(star("b", "p1"));
        assertEquals("star-2", b.code());
        assertTrue(s.partnerOf("b").isEmpty());
        assertEquals("a", s.place(star("c", "p2")).code().equals("star-1") ? "a" : "b");
    }

    @Test void typingTheSameCodeLinks_caseDoesNotMatter() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        s.place(star("a", "p1"));
        s.place(star("b", "p1"));
        s.place(star("c", "p2"));
        s.setCode("b", " MyPortal ");
        assertTrue(s.partnerOf("b").isEmpty());
        s.setCode("c", "myportal");
        assertEquals("c", s.partnerOf("b").orElseThrow().id());
    }

    @Test void blankCodeIsIgnoredAndLongOnesCut() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        s.place(star("a", "p1"));
        assertEquals("star-1", s.setCode("a", "   ").code());
        assertEquals(StarStore.MAX_CODE, s.setCode("a", "x".repeat(80)).code().length());
    }

    @Test void removingLeavesThePartnerAloneAndANewStarRelinks() {
        StarStore s = StarStore.load(dir.resolve("stars.json"));
        s.place(star("a", "p1"));
        s.place(star("b", "p2"));
        s.remove("b");
        assertTrue(s.partnerOf("a").isEmpty());
        assertEquals("a", s.partnerOf(s.place(star("c", "p3")).id()).orElseThrow().id());
    }

    @Test void roundTripAndAnOldFileWithPartners() throws IOException {
        Path f = dir.resolve("stars.json");
        StarStore s = StarStore.load(f);
        s.place(star("a", "p1"));
        s.place(star("b", "p2"));
        s.save();
        StarStore t = StarStore.load(f);
        assertEquals("b", t.partnerOf("a").orElseThrow().id());
        Files.writeString(f, "[{\"id\":\"aaaa1\",\"pos\":[0,0,0],\"up\":[0,1,0],\"stage\":\"S\",\"body\":\"x\",\"partner\":\"bbbb2\"},"
                + "{\"id\":\"bbbb2\",\"pos\":[0,0,0],\"up\":[0,1,0],\"stage\":\"S\",\"body\":\"y\",\"partner\":\"aaaa1\"}]");
        StarStore old = StarStore.load(f);
        assertEquals("bbbb2", old.partnerOf("aaaa1").orElseThrow().id());
    }

    @Test void damagedFileLoadsEmpty() throws IOException {
        Path f = dir.resolve("stars.json");
        Files.writeString(f, "{ not json");
        assertTrue(StarStore.load(f).all().isEmpty());
    }
}
