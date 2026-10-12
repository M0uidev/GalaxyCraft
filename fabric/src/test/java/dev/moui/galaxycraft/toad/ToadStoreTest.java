package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToadStoreTest {
    @TempDir Path dir;

    @Test void aToadSurvivesSaveAndLoad() throws IOException {
        Path f = dir.resolve("toads.json");
        ToadStore s = ToadStore.load(f);
        s.put(new ToadRecord("t1", ToadType.MINER, new double[] {1, 2, 3}, new double[] {0.1, 0.2, 0.3, 0.9}, "planet-a"));
        s.save();
        ToadRecord r = ToadStore.load(f).all().get(0);
        assertEquals("t1", r.id());
        assertEquals(ToadType.MINER, r.type());
        assertArrayEquals(new double[] {1, 2, 3}, r.pos());
        assertArrayEquals(new double[] {0.1, 0.2, 0.3, 0.9}, r.rot());
        assertEquals("planet-a", r.stage());
    }

    @Test void putReplacesTheSameId() {
        ToadStore s = ToadStore.load(dir.resolve("none.json"));
        s.put(new ToadRecord("t", ToadType.GUIDE, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, ""));
        s.put(new ToadRecord("t", ToadType.MINER, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, ""));
        assertEquals(1, s.all().size());
        assertEquals(ToadType.MINER, s.all().get(0).type());
    }

    @Test void aDamagedFileLosesItsToadsNotTheWorld() throws IOException {
        Path f = dir.resolve("toads.json");
        Files.writeString(f, "{ not json");
        assertTrue(ToadStore.load(f).all().isEmpty());
    }

    @Test void anUnknownTypeIsSkippedNotFatal() throws IOException {
        Path f = dir.resolve("toads.json");
        Files.writeString(f, "[{\"id\":\"a\",\"type\":\"penguin\",\"pos\":[0,0,0],\"rot\":[0,0,0,1],\"stage\":\"\"},"
                + "{\"id\":\"b\",\"type\":\"guide\",\"pos\":[0,0,0],\"rot\":[0,0,0,1],\"stage\":\"\"}]");
        ToadStore s = ToadStore.load(f);
        assertEquals(1, s.all().size());
        assertEquals("b", s.all().get(0).id());
    }

    @Test void placedPlanetsSurviveAndOldFilesLoad() throws IOException {
        Path f = dir.resolve("p.json");
        ToadStore s = ToadStore.load(f);
        s.put(new ToadRecord("t", ToadType.MINER, new double[] {0, 0, 0}, new double[] {0, 0, 0, 1}, ""));
        s.markPlaced("p7");
        s.save();
        ToadStore back = ToadStore.load(f);
        assertTrue(back.wasPlaced("p7"));
        assertFalse(back.wasPlaced("p8"));
        assertEquals(1, back.all().size());
        // The first format was a bare array of Toads.
        Path old = dir.resolve("old.json");
        Files.writeString(old, "[{\"id\":\"a\",\"type\":\"guide\",\"pos\":[1,2,3]}]");
        ToadStore o = ToadStore.load(old);
        assertEquals("a", o.all().get(0).id());
        assertFalse(o.wasPlaced("p7"));
    }
}
