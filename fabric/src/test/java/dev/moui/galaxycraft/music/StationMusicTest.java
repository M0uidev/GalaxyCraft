package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StationMusicTest {
    @TempDir Path dir;

    @Test void defaultsToSpaceMusic() {
        assertEquals(Want.SPACE, new StationMusic(dir.resolve("m.properties")).get("abc"));
    }

    @Test void remembersAChoicePerStationAcrossRestarts() {
        Path f = dir.resolve("m.properties");
        StationMusic a = new StationMusic(f);
        a.set("s1", Want.track("galaxy02"));
        a.set("s2", Want.SILENCE);
        StationMusic b = new StationMusic(f);
        assertEquals(Want.track("galaxy02"), b.get("s1"));
        assertEquals(Want.SILENCE, b.get("s2"));
        assertEquals(Want.SPACE, b.get("s3"));
    }

    @Test void backToSpaceForgetsTheEntry() throws IOException {
        Path f = dir.resolve("m.properties");
        StationMusic a = new StationMusic(f);
        a.set("s1", Want.PLANET);
        a.set("s1", Want.SPACE);
        assertFalse(Files.readString(f).contains("s1"));
    }

    @Test void aBrokenFileIsIgnored() throws IOException {
        Path f = dir.resolve("m.properties");
        Files.writeString(f, "\\u12");
        assertEquals(Want.SPACE, new StationMusic(f).get("x"));
    }

    @Test void worksInMemory() {
        StationMusic m = new StationMusic(null);
        m.set("a", Want.PLANET);
        assertEquals(Want.PLANET, m.get("a"));
    }
}
