package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AstFileTest {
    @TempDir Path dir;

    private AstSource source(int channels, int frames, int block, boolean loops, int ls, int le) throws IOException {
        Path f = AstFixture.write(dir.resolve("t.ast"), channels, frames, block, loops, ls, le, 32000);
        return new AstSource(AstFile.open(f));
    }

    @Test void readsTheHeader() throws IOException {
        Path f = AstFixture.write(dir.resolve("h.ast"), 2, 1000, 64, true, 100, 300, 32000);
        AstFile a = AstFile.open(f);
        assertEquals(2, a.channels);
        assertEquals(32000, a.rate);
        assertEquals(1000, a.frames);
        assertTrue(a.loops);
        assertEquals(100, a.loopStart);
        assertEquals(300, a.loopEnd);
    }

    @Test void readsEveryFrameAcrossBlocksInOrder() throws IOException {
        try (AstSource s = source(2, 250, 64, false, 0, 0)) {
            float[] out = new float[2 * 250];
            assertEquals(250, s.read(out, 0, 250));
            for (int f = 0; f < 250; f++) {
                assertEquals(AstFixture.value(f, 0), out[2 * f], 1e-6f, "left " + f);
                assertEquals(AstFixture.value(f, 1), out[2 * f + 1], 1e-6f, "right " + f);
            }
        }
    }

    @Test void endsWithZeroFramesWithoutLoop() throws IOException {
        try (AstSource s = source(2, 50, 64, false, 0, 0)) {
            float[] out = new float[2 * 100];
            assertEquals(50, s.read(out, 0, 100));
            assertEquals(0, s.read(out, 0, 100));
            assertEquals(50, s.lengthFrames());
        }
    }

    @Test void loopsAtItsOwnLoopPointsWithNoGap() throws IOException {
        try (AstSource s = source(2, 1000, 64, true, 100, 300)) {
            float[] out = new float[2 * 700];
            assertEquals(700, s.read(out, 0, 700));
            for (int i = 0; i < 700; i++) {
                long frame = i < 300 ? i : 100 + (i - 100) % 200;
                assertEquals(AstFixture.value(frame, 0), out[2 * i], 1e-6f, "frame " + i);
            }
            assertTrue(s.loops());
            assertEquals(-1, s.lengthFrames());
        }
    }

    @Test void keepsTheFirstTwoOfFourChannels() throws IOException {
        try (AstSource s = source(4, 100, 32, false, 0, 0)) {
            float[] out = new float[2 * 100];
            assertEquals(100, s.read(out, 0, 100));
            assertEquals(AstFixture.value(40, 0), out[80], 1e-6f);
            assertEquals(AstFixture.value(40, 1), out[81], 1e-6f);
        }
    }

    @Test void readsIntoAnOffset() throws IOException {
        try (AstSource s = source(2, 100, 32, false, 0, 0)) {
            float[] out = new float[2 * 10];
            assertEquals(4, s.read(out, 6, 4));
            assertEquals(AstFixture.value(0, 0), out[12], 1e-6f);
        }
    }

    @Test void rejectsAFileThatIsNotAnAst() throws IOException {
        Path f = dir.resolve("bad.ast");
        Files.write(f, new byte[200]);
        assertThrows(IOException.class, () -> AstFile.open(f));
        Files.write(f, new byte[10]);
        assertThrows(IOException.class, () -> AstFile.open(f));
    }
}
