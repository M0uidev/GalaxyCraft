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
        s.put(new ToadRecord("t1", ToadType.MINER, new double[] {1, 2, 3}, 0.5, "planet-a"));
        s.save();
        ToadRecord r = ToadStore.load(f).all().get(0);
        assertEquals("t1", r.id());
        assertEquals(ToadType.MINER, r.type());
        assertArrayEquals(new double[] {1, 2, 3}, r.pos());
        assertEquals(0.5, r.yaw());
        assertEquals("planet-a", r.stage());
    }

    @Test void putReplacesTheSameId() {
        ToadStore s = ToadStore.load(dir.resolve("none.json"));
        s.put(new ToadRecord("t", ToadType.GUIDE, new double[] {0, 0, 0}, 0, ""));
        s.put(new ToadRecord("t", ToadType.MINER, new double[] {0, 0, 0}, 0, ""));
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
        Files.writeString(f, "[{\"id\":\"a\",\"type\":\"penguin\",\"pos\":[0,0,0],\"yaw\":0,\"stage\":\"\"},"
                + "{\"id\":\"b\",\"type\":\"guide\",\"pos\":[0,0,0],\"yaw\":0,\"stage\":\"\"}]");
        ToadStore s = ToadStore.load(f);
        assertEquals(1, s.all().size());
        assertEquals("b", s.all().get(0).id());
    }
}
