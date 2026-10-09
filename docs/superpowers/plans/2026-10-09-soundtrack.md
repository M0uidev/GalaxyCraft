# Soundtrack player and space/planet music Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An in-game music player with the SMG2 songs and Minecraft's, whose automatic music follows space/planet with dwell, cooldown and crossfade (all settings), plus a per-station music choice.

**Architecture:** Pure-Java core in `dev.moui.galaxycraft.music` (AST reader, software mixer with equal-power crossfade, switch gate, catalog, shuffle bag, source policy), unit tested with JUnit. A thin client layer in `dev.moui.galaxycraft.client.music` wires it to Minecraft: Java Sound output thread, sensor (space/planet/station), Minecraft music via vanilla's `JOrbisAudioStream`, a mixin silencing vanilla `MusicManager`, the player screen, keybinds, and the Station Core button.

**Tech Stack:** Java 25, Fabric (Minecraft 26.3, Mojang names), JUnit 5, `javax.sound.sampled`. No new libraries.

**Spec:** `docs/superpowers/specs/2026-10-09-galaxycraft-soundtrack-design.md`

## Global Constraints

- Package for pure code `dev.moui.galaxycraft.music` (main source set, no Minecraft types); client code `dev.moui.galaxycraft.client.music`.
- Run unit tests with `fabric/test.sh [--tests 'pattern']` (it sets the JDK 25 `JAVA_HOME`).
- Defaults: cooldown 120 s (0..600, step 5), dwell 5 s (0..60), crossfade 4 s (0..15), volume 100 % (0..100, step 5), Music source `Both`, Automatic music on.
- `.ast` files are PCM16 big-endian, header 0x40, blocks `BLCK` + u32 bytes-per-channel + 24 pad bytes, then each channel's bytes in turn; loop flag `0xFFFF`; loop points in frames.
- Mixer output: 44100 Hz stereo, 16-bit little-endian to a `SourceDataLine`.
- Unclassified songs (`mood` empty) and tagged-only songs (Slipsand, `desert`) are in no automatic pool.
- Players' text: "Super Minecraft Galaxy"; no mention of branches/commits in anything player-facing.
- Do **not** copy or import any music from the disc yet (the user is still classifying). Tests use synthetic `.ast` files; the manual check uses files extracted to the scratchpad only.
- Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

- Mixer fed a source that returns 0 frames immediately, or a track shorter than the crossfade: no crash, no stuck deck.
- Cooldown/dwell set to 0 mid-session: switches immediately, no division by zero.
- An empty pool (nothing classified yet, mode `SMG2` with no SMG2 tracks, no audio device): silence, no exception, no busy loop.
- Corrupt/short `.ast`, or an `.ast` referenced in `tracks.tsv` that is not on disk: that track is skipped with one log line, music goes on.
- Crossfade requested while another crossfade is still running: three decks briefly, the oldest is dropped with no click.

---

### Task 1: AST reader

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/music/PcmSource.java`
- Create: `fabric/src/main/java/dev/moui/galaxycraft/music/AstFile.java`
- Create: `fabric/src/main/java/dev/moui/galaxycraft/music/AstSource.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/music/AstFixture.java`, `.../AstFileTest.java`

**Interfaces:**
- Produces: `PcmSource` (`int rate()`, `int read(float[] out, int offsetFrames, int frames)` stereo interleaved, returns 0 at the end; `boolean loops()`; `long lengthFrames()` (-1 unknown); `long positionFrames()`; `void close()`), `AstFile.open(Path)`, `new AstSource(AstFile)`.

- [ ] **Step 1: Write the fixture and failing tests**

```java
// AstFixture.java
package dev.moui.galaxycraft.music;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes small synthetic .ast files: frame f, channel c holds (f + 1000 c) % 30000. */
final class AstFixture {
    static short sample(long frame, int channel) {
        return (short) ((frame + 1000L * channel) % 30000);
    }

    static float value(long frame, int channel) {
        return sample(frame, channel) / 32768f;
    }

    static Path write(Path file, int channels, int frames, int blockFrames, boolean loops, int loopStart, int loopEnd, int rate)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int first = 0; first < frames; first += blockFrames) {
            int n = Math.min(blockFrames, frames - first);
            ByteBuffer b = ByteBuffer.allocate(0x20 + n * 2 * channels).order(ByteOrder.BIG_ENDIAN);
            b.putInt(0x424C434B).putInt(n * 2);
            b.position(0x20);
            for (int c = 0; c < channels; c++)
                for (int i = 0; i < n; i++) b.putShort(sample(first + i, c));
            body.write(b.array());
        }
        ByteBuffer h = ByteBuffer.allocate(0x40).order(ByteOrder.BIG_ENDIAN);
        h.putInt(0x5354524D).putInt(body.size()).putShort((short) 1).putShort((short) 16).putShort((short) channels)
                .putShort((short) (loops ? 0xFFFF : 0)).putInt(rate).putInt(frames).putInt(loopStart).putInt(loopEnd)
                .putInt(blockFrames * 2);
        Files.write(file, concat(h.array(), body.toByteArray()));
        return file;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
```

```java
// AstFileTest.java
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
```

- [ ] **Step 2: Run, expect compile failure** — `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.music.AstFileTest'` → FAIL (`AstFile`/`AstSource` not found).

- [ ] **Step 3: Implement**

```java
// PcmSource.java
package dev.moui.galaxycraft.music;

/** A stream of stereo float frames (L, R interleaved) at its own sample rate. */
public interface PcmSource extends AutoCloseable {
    int rate();

    /** Writes up to frames stereo frames at out[offsetFrames * 2 ...]; returns how many (0: the end). */
    int read(float[] out, int offsetFrames, int frames);

    /** True if it never ends by itself (it loops). */
    default boolean loops() {
        return false;
    }

    /** Its length in frames, -1 if unknown or endless. */
    default long lengthFrames() {
        return -1;
    }

    long positionFrames();

    @Override
    void close();
}
```

```java
// AstFile.java
package dev.moui.galaxycraft.music;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** A Nintendo STRM (.ast) file of 16-bit PCM: its header and an index of its blocks. */
public final class AstFile {
    static final int HEADER = 0x40, BLOCK_HEADER = 0x20;
    private static final int STRM = 0x5354524D, BLCK = 0x424C434B;

    public final Path path;
    public final int channels, rate;
    public final long frames;
    public final boolean loops;
    public final long loopStart, loopEnd;
    /** Per block: file offset of channel 0's bytes, bytes per channel, first frame. */
    final long[] blockData, blockFirst;
    final int[] blockBytes;
    final int maxBlockBytes;

    private AstFile(Path path, int channels, int rate, long frames, boolean loops, long loopStart, long loopEnd,
            long[] blockData, int[] blockBytes, long[] blockFirst) {
        this.path = path;
        this.channels = channels;
        this.rate = rate;
        this.frames = frames;
        this.loops = loops;
        this.loopStart = loopStart;
        this.loopEnd = loopEnd;
        this.blockData = blockData;
        this.blockBytes = blockBytes;
        this.blockFirst = blockFirst;
        int max = 0;
        for (int b : blockBytes) max = Math.max(max, b);
        this.maxBlockBytes = max;
    }

    public static AstFile open(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN);
            readFully(ch, h, 0);
            if (h.getInt(0) != STRM) throw new IOException(path + " is not an AST file");
            int format = h.getShort(8) & 0xFFFF, bits = h.getShort(10) & 0xFFFF;
            if (format != 1 || bits != 16) throw new IOException(path + ": AST format " + format + "/" + bits + " is not PCM16");
            int channels = h.getShort(12) & 0xFFFF;
            if (channels < 1 || channels > 8) throw new IOException(path + ": " + channels + " channels");
            boolean loops = (h.getShort(14) & 0xFFFF) == 0xFFFF;
            int rate = h.getInt(16);
            if (rate < 8000 || rate > 192000) throw new IOException(path + ": rate " + rate);
            long frames = h.getInt(20) & 0xFFFFFFFFL, ls = h.getInt(24) & 0xFFFFFFFFL, le = h.getInt(28) & 0xFFFFFFFFL;

            List<long[]> blocks = new ArrayList<>(); // {data offset, bytes per channel, first frame}
            long size = ch.size(), off = HEADER, frame = 0;
            ByteBuffer bh = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            while (off + BLOCK_HEADER <= size) {
                bh.clear();
                readFully(ch, bh, off);
                if (bh.getInt(0) != BLCK) throw new IOException(path + ": no block at " + off);
                int per = bh.getInt(4);
                if (per <= 0 || off + BLOCK_HEADER + (long) per * channels > size) break; // a cut-off last block is dropped
                blocks.add(new long[] {off + BLOCK_HEADER, per, frame});
                frame += per / 2;
                off += BLOCK_HEADER + (long) per * channels;
            }
            if (blocks.isEmpty()) throw new IOException(path + " has no audio");
            frames = Math.min(frames, frame);
            if (loops && (le <= ls || le > frames)) le = frames;
            if (loops && ls >= le) loops = false;
            long[] data = new long[blocks.size()], first = new long[blocks.size()];
            int[] bytes = new int[blocks.size()];
            for (int i = 0; i < data.length; i++) {
                data[i] = blocks.get(i)[0];
                bytes[i] = (int) blocks.get(i)[1];
                first[i] = blocks.get(i)[2];
            }
            return new AstFile(path, channels, rate, frames, loops, ls, le, data, bytes, first);
        }
    }

    static void readFully(FileChannel ch, ByteBuffer b, long at) throws IOException {
        while (b.hasRemaining()) {
            int n = ch.read(b, at + b.position());
            if (n < 0) throw new EOFException();
        }
    }
}
```

```java
// AstSource.java
package dev.moui.galaxycraft.music;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Plays an {@link AstFile}: its first two channels as stereo, looping at the file's own loop points. */
public final class AstSource implements PcmSource {
    private final AstFile ast;
    private final FileChannel ch;
    private final ByteBuffer raw;
    private final short[][] cache;
    private int block = -1, cacheFrames;
    private long pos;

    public AstSource(AstFile ast) throws IOException {
        this.ast = ast;
        this.ch = FileChannel.open(ast.path, StandardOpenOption.READ);
        this.raw = ByteBuffer.allocate(ast.maxBlockBytes).order(ByteOrder.BIG_ENDIAN);
        this.cache = new short[2][ast.maxBlockBytes / 2];
    }

    @Override public int rate() {
        return ast.rate;
    }

    @Override public boolean loops() {
        return ast.loops;
    }

    @Override public long lengthFrames() {
        return ast.loops ? -1 : ast.frames;
    }

    @Override public long positionFrames() {
        return pos;
    }

    @Override public int read(float[] out, int offset, int frames) {
        int n = 0;
        long limit = ast.loops ? ast.loopEnd : ast.frames;
        while (n < frames) {
            if (pos >= limit) {
                if (!ast.loops) break;
                pos = ast.loopStart;
            }
            load(pos);
            int inBlock = (int) (pos - ast.blockFirst[block]);
            int take = (int) Math.min(Math.min(frames - n, cacheFrames - inBlock), limit - pos);
            if (take <= 0) break;
            short[] l = cache[0], r = cache[ast.channels > 1 ? 1 : 0];
            for (int i = 0; i < take; i++) {
                int o = (offset + n + i) * 2;
                out[o] = l[inBlock + i] / 32768f;
                out[o + 1] = r[inBlock + i] / 32768f;
            }
            n += take;
            pos += take;
        }
        return n;
    }

    private void load(long frame) {
        int i = Arrays.binarySearch(ast.blockFirst, frame);
        if (i < 0) i = -i - 2;
        if (i == block) return;
        int per = ast.blockBytes[i];
        try {
            for (int c = 0; c < Math.min(2, ast.channels); c++) {
                raw.clear().limit(per);
                AstFile.readFully(ch, raw, ast.blockData[i] + (long) c * per);
                raw.flip();
                raw.asShortBuffer().get(cache[c], 0, per / 2);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        block = i;
        cacheFrames = per / 2;
    }

    @Override public void close() {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }
}
```

- [ ] **Step 4: Run tests, expect PASS** — `cd fabric && ./test.sh --tests 'dev.moui.galaxycraft.music.AstFileTest'`.
- [ ] **Step 5: Commit** — `git add fabric/src && git commit -m "music: AST reader with loop points"` (plus the Co-Authored-By trailer).

---

### Task 2: Mixer (resample, crossfade, pause)

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/music/Mixer.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/music/MixerTest.java`

**Interfaces:**
- Consumes: `PcmSource`.
- Produces: `Mixer.RATE = 44100`; `play(PcmSource, double fadeSeconds)`, `stop(double fadeSeconds)`, `setPaused(boolean)`, `setVolume(float)`, `onEnd(Runnable)` (called on the audio thread when the current non-looping source ends: the callback must only post work elsewhere), `render(float[] out, int frames)` (interleaved stereo, overwrites), `positionSeconds()`, `remainingSeconds()` (infinity if endless), `playing()`.

- [ ] **Step 1: Failing tests**

```java
package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MixerTest {
    /** A constant tone: value on both channels for frames frames (-1: endless). */
    static final class Const implements PcmSource {
        final int rate;
        final float value;
        final long length;
        long pos;
        boolean closed;

        Const(int rate, float value, long length) {
            this.rate = rate;
            this.value = value;
            this.length = length;
        }

        @Override public int rate() { return rate; }
        @Override public int read(float[] out, int offset, int frames) {
            int n = length < 0 ? frames : (int) Math.min(frames, length - pos);
            for (int i = 0; i < n; i++) { out[(offset + i) * 2] = value; out[(offset + i) * 2 + 1] = value; }
            pos += n;
            return n;
        }
        @Override public boolean loops() { return length < 0; }
        @Override public long lengthFrames() { return length; }
        @Override public long positionFrames() { return pos; }
        @Override public void close() { closed = true; }
    }

    private static float[] render(Mixer m, int frames) {
        float[] out = new float[frames * 2];
        m.render(out, frames);
        return out;
    }

    @Test void playsASteadyTone() {
        Mixer m = new Mixer();
        m.play(new Const(44100, 0.5f, -1), 0);
        float[] out = render(m, 100);
        for (float v : out) assertEquals(0.5f, v, 1e-5f);
    }

    @Test void silenceWhenNothingPlays() {
        float[] out = render(new Mixer(), 64);
        for (float v : out) assertEquals(0f, v);
        assertFalse(new Mixer().playing());
    }

    @Test void resamplesAConstantWithoutRipple() {
        Mixer m = new Mixer();
        Const c = new Const(22050, 0.5f, -1);
        m.play(c, 0);
        float[] out = render(m, 2000);
        for (float v : out) assertEquals(0.5f, v, 1e-5f);
        assertEquals(1000, c.pos, 4300); // consumed about half as many frames (+ the read-ahead buffer)
        assertTrue(m.positionSeconds() > 0.0);
    }

    @Test void volumeScales() {
        Mixer m = new Mixer();
        m.setVolume(0.25f);
        m.play(new Const(44100, 1f, -1), 0);
        assertEquals(0.25f, render(m, 10)[5], 1e-5f);
    }

    @Test void crossfadeIsEqualPowerAndDropsTheOldDeck() {
        Mixer m = new Mixer();
        Const a = new Const(44100, 1f, -1), b = new Const(44100, 1f, -1);
        m.play(a, 0);
        render(m, 10);
        m.play(b, 1.0); // one second
        float[] first = render(m, 22050); // up to the middle
        assertEquals(Math.sqrt(2), first[first.length - 2], 0.02); // sin(45°) + cos(45°)
        float[] second = render(m, 22050 + 1024);
        assertEquals(1f, second[second.length - 2], 1e-3f);
        assertTrue(a.closed, "the faded-out deck is closed");
        assertFalse(b.closed);
    }

    @Test void pausedRendersSilenceAndDoesNotAdvance() {
        Mixer m = new Mixer();
        Const c = new Const(44100, 1f, -1);
        m.play(c, 0);
        render(m, 100);
        long at = c.pos;
        m.setPaused(true);
        for (float v : render(m, 100)) assertEquals(0f, v);
        assertEquals(at, c.pos);
        m.setPaused(false);
        assertEquals(1f, render(m, 10)[0], 1e-5f);
    }

    @Test void tellsOnceWhenASourceEnds() {
        Mixer m = new Mixer();
        AtomicInteger ends = new AtomicInteger();
        m.onEnd(ends::incrementAndGet);
        m.play(new Const(44100, 1f, 500), 0);
        for (int i = 0; i < 10; i++) render(m, 256);
        assertEquals(1, ends.get());
        assertFalse(m.playing());
    }

    @Test void anEndlessSourceNeverEnds() {
        Mixer m = new Mixer();
        AtomicInteger ends = new AtomicInteger();
        m.onEnd(ends::incrementAndGet);
        m.play(new Const(44100, 1f, -1), 0);
        for (int i = 0; i < 100; i++) render(m, 1024);
        assertEquals(0, ends.get());
        assertEquals(Double.POSITIVE_INFINITY, m.remainingSeconds());
    }

    @Test void aSourceThatIsEmptyAtOnceDoesNotWedgeTheMixer() {
        Mixer m = new Mixer();
        AtomicInteger ends = new AtomicInteger();
        m.onEnd(ends::incrementAndGet);
        m.play(new Const(44100, 1f, 0), 0);
        render(m, 256);
        assertEquals(1, ends.get());
        m.play(new Const(44100, 0.5f, -1), 0);
        assertEquals(0.5f, render(m, 8)[0], 1e-5f);
    }

    @Test void stopFadesToSilenceThenClosesTheDeck() {
        Mixer m = new Mixer();
        Const c = new Const(44100, 1f, -1);
        m.play(c, 0);
        render(m, 10);
        m.stop(0.1);
        float[] out = render(m, 6000);
        assertEquals(0f, out[out.length - 2], 1e-3f);
        assertTrue(c.closed);
        assertFalse(m.playing());
    }

    @Test void aQuickSecondCrossfadeDropsTheOldestDeckCleanly() {
        Mixer m = new Mixer();
        Const a = new Const(44100, 1f, -1), b = new Const(44100, 1f, -1), c = new Const(44100, 1f, -1);
        m.play(a, 0);
        render(m, 10);
        m.play(b, 1);
        render(m, 5000);
        m.play(c, 1);
        float[] out = render(m, 60000);
        assertEquals(1f, out[out.length - 2], 1e-3f);
        assertTrue(a.closed && b.closed);
        for (float v : out) assertTrue(v <= 2.0f && !Float.isNaN(v));
    }
}
```

- [ ] **Step 2: Run** `./test.sh --tests 'dev.moui.galaxycraft.music.MixerTest'` → FAIL (no `Mixer`).

- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.music;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mixes the playing sources into 44.1 kHz stereo. Each source is a deck with a linear resampler and
 * an equal-power fade (gain = sin(u·π/2), u from 0 to 1 fading in and 1 to 0 fading out, so a new
 * deck and the old ones one crossfade apart always add up to constant power). The game thread
 * calls play/stop/setPaused/setVolume, which are queued and applied at the top of the next
 * {@link #render}; render runs on the audio thread and allocates nothing.
 */
public final class Mixer {
    public static final int RATE = 44100;
    private static final int CAP = 4096;
    private static final double HALF_PI = Math.PI / 2;

    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final List<Deck> decks = new ArrayList<>();
    private Deck current;
    private boolean paused, endSent;
    private float volume = 1f;
    private volatile Runnable onEnd = () -> {};
    private volatile double position, remaining = Double.POSITIVE_INFINITY;
    private volatile boolean playing;

    public void play(PcmSource source, double fadeSeconds) {
        commands.add(() -> {
            for (Deck d : decks) d.fadeOut(fadeSeconds);
            current = new Deck(source, fadeSeconds);
            decks.add(current);
            endSent = false;
        });
    }

    public void stop(double fadeSeconds) {
        commands.add(() -> {
            for (Deck d : decks) d.fadeOut(fadeSeconds);
            current = null;
        });
    }

    public void setPaused(boolean p) {
        commands.add(() -> paused = p);
    }

    public void setVolume(float v) {
        float c = Math.clamp(v, 0f, 1f);
        commands.add(() -> volume = c);
    }

    /** Called on the audio thread when the current, non-looping source ends: only post work elsewhere. */
    public void onEnd(Runnable r) {
        onEnd = r;
    }

    public double positionSeconds() {
        return position;
    }

    /** Seconds left of the current source, infinity if it loops or its length is unknown. */
    public double remainingSeconds() {
        return remaining;
    }

    public boolean playing() {
        return playing;
    }

    /** Fills out (interleaved stereo) with frames frames. */
    public void render(float[] out, int frames) {
        for (Runnable c; (c = commands.poll()) != null; ) c.run();
        Arrays.fill(out, 0, frames * 2, 0f);
        if (!paused) {
            for (Deck d : decks) d.mixInto(out, frames, volume);
            for (int i = decks.size() - 1; i >= 0; i--) {
                Deck d = decks.get(i);
                if (d.finished() && d != current) {
                    d.src.close();
                    decks.remove(i);
                }
            }
            if (current != null && current.ended() && !endSent) {
                endSent = true;
                current.src.close();
                decks.remove(current);
                current = null;
                onEnd.run();
            }
        }
        Deck c = current;
        playing = c != null;
        if (c != null) {
            long p = c.src.positionFrames(), len = c.src.lengthFrames();
            position = p / (double) c.src.rate();
            remaining = c.src.loops() || len < 0 ? Double.POSITIVE_INFINITY : Math.max(0, len - p) / (double) c.src.rate();
        } else {
            position = 0;
            remaining = Double.POSITIVE_INFINITY;
        }
    }

    private static final class Deck {
        final PcmSource src;
        private final double step;
        private final float[] buf = new float[2 * CAP];
        private int len;
        private double pos, u, du;
        private boolean srcDone;

        Deck(PcmSource src, double fadeInSeconds) {
            this.src = src;
            this.step = (double) src.rate() / RATE;
            if (fadeInSeconds > 0) {
                u = 0;
                du = 1 / (fadeInSeconds * RATE);
            } else {
                u = 1;
            }
        }

        void fadeOut(double seconds) {
            if (seconds <= 0) {
                u = 0;
                du = -1;
            } else {
                du = -1 / (seconds * RATE);
            }
        }

        boolean ended() {
            return srcDone && pos >= len;
        }

        boolean finished() {
            return (du < 0 && u <= 0) || ended();
        }

        void mixInto(float[] out, int frames, float vol) {
            for (int i = 0; i < frames; i++) {
                if (du < 0 && u <= 0) return;
                if (pos + 1 >= len) {
                    refill();
                    if (pos >= len) return;
                }
                int a = (int) pos, b = Math.min(a + 1, len - 1);
                float f = (float) (pos - a);
                float l = buf[2 * a] + (buf[2 * b] - buf[2 * a]) * f;
                float r = buf[2 * a + 1] + (buf[2 * b + 1] - buf[2 * a + 1]) * f;
                float g = vol * (u >= 1 ? 1f : (float) Math.sin(u * HALF_PI));
                out[2 * i] += l * g;
                out[2 * i + 1] += r * g;
                pos += step;
                if (du != 0) {
                    u += du;
                    if (u >= 1) {
                        u = 1;
                        du = 0;
                    } else if (u < 0) {
                        u = 0;
                    }
                }
            }
        }

        private void refill() {
            int keep = (int) pos;
            if (keep > 0) {
                int remain = Math.max(0, len - keep);
                System.arraycopy(buf, keep * 2, buf, 0, remain * 2);
                len = remain;
                pos -= keep;
            }
            while (!srcDone && len < CAP) {
                int got = src.read(buf, len, CAP - len);
                if (got <= 0) {
                    srcDone = true;
                    break;
                }
                len += got;
            }
        }
    }
}
```

Note: a fading-out *current* deck (via `stop`) sets `current = null`, so it is dropped by the loop above once `finished()`. A `current` deck that ended is closed and removed in the end block.

- [ ] **Step 4: Run** → PASS all of `MixerTest`. If `resamplesAConstantWithoutRipple`'s bound is wrong for the read-ahead (it asserts `c.pos` within 4300 of 1000, i.e. 1000 ± 4300, which the 4096-frame read-ahead satisfies), adjust only that bound, not the mixer.
- [ ] **Step 5: Commit** — `music: software mixer with equal-power crossfade`.

---

### Task 3: SwitchGate (dwell, cooldown, pending change)

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/music/SwitchGate.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/music/SwitchGateTest.java`

**Interfaces:**
- Produces: `SwitchGate<T>`: `T update(double nowSeconds, T wanted, double dwellSeconds, double cooldownSeconds)` returns the value to switch to or `null` to keep; `T current()`; `void force(T value, double now)` (a pick by the player: current is `value`, starts the cooldown); `void reset()`.

- [ ] **Step 1: Failing tests**

```java
package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SwitchGateTest {
    @Test void theFirstWantStartsAtOnce() {
        SwitchGate<String> g = new SwitchGate<>();
        assertEquals("space", g.update(0, "space", 5, 120));
        assertEquals("space", g.current());
    }

    @Test void aChangeWaitsForTheDwellTime() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 5, 0);
        assertNull(g.update(10, "planet", 5, 0));
        assertNull(g.update(14.9, "planet", 5, 0));
        assertEquals("planet", g.update(15, "planet", 5, 0));
    }

    @Test void theCooldownHoldsASwitchAndThePendingOneIsAppliedWhenItEnds() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 5, 120);
        assertNull(g.update(200, "planet", 5, 120));
        assertEquals("planet", g.update(205, "planet", 5, 120));
        // left the planet 60 s later: inside the cooldown, nothing changes...
        assertNull(g.update(260, "space", 5, 120));
        assertNull(g.update(319, "space", 5, 120));
        // ...and when the cooldown ends, still in space: it changes
        assertEquals("space", g.update(325, "space", 5, 120));
    }

    @Test void aFlipBackInsideTheCooldownCancelsThePendingChange() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 0, 120);
        assertEquals("planet", g.update(200, "planet", 0, 120));
        assertNull(g.update(250, "space", 0, 120));
        assertNull(g.update(260, "planet", 0, 120));
        assertNull(g.update(400, "planet", 0, 120));
        assertEquals("planet", g.current());
    }

    @Test void zeroDwellAndZeroCooldownSwitchAtOnce() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 0, 0);
        assertEquals("planet", g.update(0.1, "planet", 0, 0));
        assertEquals("space", g.update(0.2, "space", 0, 0));
    }

    @Test void aPlayersPickStartsTheCooldown() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 0, 60);
        g.force("planet", 100);
        assertEquals("planet", g.current());
        assertNull(g.update(130, "space", 0, 60));
        assertEquals("space", g.update(160, "space", 0, 60));
    }

    @Test void resetStartsOver() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 5, 120);
        g.reset();
        assertNull(g.current());
        assertEquals("planet", g.update(1, "planet", 5, 120));
    }

    @Test void aNullWantChangesNothing() {
        SwitchGate<String> g = new SwitchGate<>();
        assertNull(g.update(0, null, 5, 5));
        assertNull(g.current());
    }
}
```

- [ ] **Step 2: Run** → FAIL (no `SwitchGate`).
- [ ] **Step 3: Implement**

```java
package dev.moui.galaxycraft.music;

/**
 * Decides when automatic music may change: the wanted value must stay different for the dwell time,
 * and a change may not come sooner than the cooldown after the last one. A change that is wanted
 * when the cooldown ends is applied then; one the player walked away from is dropped.
 */
public final class SwitchGate<T> {
    private T current, candidate;
    private double candidateSince;
    private double lastSwitch = Double.NEGATIVE_INFINITY;

    /** The value to switch to now, or null to keep what plays. */
    public T update(double now, T wanted, double dwell, double cooldown) {
        if (wanted == null) return null;
        if (current == null) {
            current = wanted;
            candidate = null;
            return wanted;
        }
        if (wanted.equals(current)) {
            candidate = null;
            return null;
        }
        if (!wanted.equals(candidate)) {
            candidate = wanted;
            candidateSince = now;
        }
        if (now - candidateSince >= dwell && now - lastSwitch >= cooldown) {
            current = wanted;
            candidate = null;
            lastSwitch = now;
            return wanted;
        }
        return null;
    }

    public T current() {
        return current;
    }

    /** The player chose it: it plays now and starts the cooldown. */
    public void force(T value, double now) {
        current = value;
        candidate = null;
        lastSwitch = now;
    }

    public void reset() {
        current = null;
        candidate = null;
        lastSwitch = Double.NEGATIVE_INFINITY;
    }
}
```

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** — `music: switch gate with dwell and cooldown`.

---

### Task 4: Catalog, source policy, shuffle

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/music/{Mood,Source,Track,Want,SourceMode,Catalog,ShuffleBag}.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/music/{CatalogTest,SourceModeTest,ShuffleBagTest,WantTest}.java`

**Interfaces:**
- Produces:
  - `enum Mood {SPACE, PLANET}`; `enum Source {SMG2, MINECRAFT}`.
  - `record Track(String id, String title, String file, Source source, Mood mood, List<String> tags, boolean enabled)` with `withMood(Mood)`, `withEnabled(boolean)`.
  - `record Want(Kind kind, String trackId)`; `enum Kind {SPACE, PLANET, TRACK, SILENCE}`; constants `Want.SPACE`, `Want.PLANET`, `Want.SILENCE`, `Want.track(id)`; `String encode()`; `static Want decode(String)` (junk/null → `SPACE`).
  - `enum SourceMode {BOTH, SMG2, MINECRAFT, RANDOM}` with `Want apply(Want)` (for `MINECRAFT`/`RANDOM` a `SPACE`/`PLANET` want becomes `PLANET`; `TRACK`/`SILENCE` unchanged) and `List<Track> pool(List<Track> all, Want mood)` and `String label()`.
  - `Catalog.parse(String)`, `Catalog.format(List<Track>)`, `Catalog.read(Path)` (missing file → empty list), `Catalog.write(Path, List<Track>)` (atomic).
  - `ShuffleBag.next(List<Track> pool, java.util.Random)` → `Track` or `null`.

- [ ] **Step 1: Failing tests**

```java
// CatalogTest.java
package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {
    @TempDir Path dir;

    @Test void roundTrips() {
        List<Track> in = List.of(
                new Track("galaxy02", "Yoshi Star Galaxy", "SMG2_galaxy02_strm.ast", Source.SMG2, Mood.PLANET, List.of(), true),
                new Track("galaxy23", "Slipsand Galaxy", "SMG2_galaxy23_strm.ast", Source.SMG2, null, List.of("desert", "hot"), false));
        assertEquals(in, Catalog.parse(Catalog.format(in)));
    }

    @Test void skipsCommentsBlankAndBrokenLines() {
        String text = "# header\n\nok\tOK\tf.ast\tsmg2\tspace\t\ttrue\nshort\tline\nbad\tB\tf.ast\tnope\tspace\t\ttrue\n";
        List<Track> t = Catalog.parse(text);
        assertEquals(1, t.size());
        assertEquals(Mood.SPACE, t.get(0).mood());
        assertEquals(List.of(), t.get(0).tags());
    }

    @Test void unknownMoodMeansNone() {
        assertNull(Catalog.parse("a\tA\tf.ast\tsmg2\tweird\t\ttrue\n").get(0).mood());
    }

    @Test void aMissingFileIsAnEmptyCatalog() throws IOException {
        assertEquals(List.of(), Catalog.read(dir.resolve("nope.tsv")));
    }

    @Test void writesAndReadsAFile() throws IOException {
        Path f = dir.resolve("sub/tracks.tsv");
        List<Track> in = List.of(new Track("a", "A\tB", "a.ast", Source.MINECRAFT, Mood.PLANET, List.of("x"), true));
        Catalog.write(f, in);
        List<Track> out = Catalog.read(f);
        assertEquals("A B", out.get(0).title(), "tabs in a title become spaces");
        assertEquals(Source.MINECRAFT, out.get(0).source());
    }
}
```

```java
// SourceModeTest.java
package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceModeTest {
    private static Track t(String id, Source s, Mood m, boolean on) {
        return new Track(id, id, id + ".x", s, m, List.of(), on);
    }

    private final List<Track> all = List.of(
            t("sp1", Source.SMG2, Mood.SPACE, true), t("sp2", Source.SMG2, Mood.SPACE, false),
            t("pl1", Source.SMG2, Mood.PLANET, true), t("none", Source.SMG2, null, true),
            t("mc1", Source.MINECRAFT, Mood.PLANET, true), t("mc2", Source.MINECRAFT, Mood.PLANET, true));

    private static List<String> ids(List<Track> l) {
        return l.stream().map(Track::id).sorted().toList();
    }

    @Test void bothMixesMinecraftIntoPlanets() {
        assertEquals(List.of("mc1", "mc2", "pl1"), ids(SourceMode.BOTH.pool(all, Want.PLANET)));
        assertEquals(List.of("sp1"), ids(SourceMode.BOTH.pool(all, Want.SPACE)));
    }

    @Test void smg2OnlyHasNoMinecraft() {
        assertEquals(List.of("pl1"), ids(SourceMode.SMG2.pool(all, Want.PLANET)));
    }

    @Test void minecraftOnlyPlaysEverywhere() {
        assertEquals(List.of("mc1", "mc2"), ids(SourceMode.MINECRAFT.pool(all, Want.SPACE)));
        assertEquals(List.of("mc1", "mc2"), ids(SourceMode.MINECRAFT.pool(all, Want.PLANET)));
    }

    @Test void randomTakesAnyClassifiedEnabledTrackOfEitherGame() {
        assertEquals(List.of("mc1", "mc2", "pl1", "sp1"), ids(SourceMode.RANDOM.pool(all, Want.SPACE)));
    }

    @Test void minecraftAndRandomIgnoreTheMoodWhenApplied() {
        assertEquals(Want.PLANET, SourceMode.RANDOM.apply(Want.SPACE));
        assertEquals(Want.PLANET, SourceMode.MINECRAFT.apply(Want.SPACE));
        assertEquals(Want.SPACE, SourceMode.BOTH.apply(Want.SPACE));
        assertEquals(Want.SPACE, SourceMode.SMG2.apply(Want.SPACE));
        assertEquals(Want.SILENCE, SourceMode.RANDOM.apply(Want.SILENCE));
        assertEquals(Want.track("a"), SourceMode.MINECRAFT.apply(Want.track("a")));
    }

    @Test void anEmptyPoolIsEmptyNotAnError() {
        assertEquals(List.of(), SourceMode.SMG2.pool(List.of(), Want.SPACE));
    }
}
```

```java
// ShuffleBagTest.java
package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ShuffleBagTest {
    private static Track t(String id) {
        return new Track(id, id, id, Source.SMG2, Mood.SPACE, List.of(), true);
    }

    @Test void usesEveryTrackOncePerRoundAndNeverRepeatsBackToBack() {
        List<Track> pool = List.of(t("a"), t("b"), t("c"));
        ShuffleBag bag = new ShuffleBag();
        Random r = new Random(7);
        Map<String, Integer> count = new HashMap<>();
        String last = null;
        for (int i = 0; i < 6; i++) {
            String id = bag.next(pool, r).id();
            assertNotEquals(last, id);
            count.merge(id, 1, Integer::sum);
            last = id;
        }
        assertEquals(Map.of("a", 2, "b", 2, "c", 2), count);
    }

    @Test void aSingleTrackPoolRepeatsItself() {
        ShuffleBag bag = new ShuffleBag();
        assertEquals("a", bag.next(List.of(t("a")), new Random(1)).id());
        assertEquals("a", bag.next(List.of(t("a")), new Random(1)).id());
    }

    @Test void anEmptyPoolGivesNull() {
        assertNull(new ShuffleBag().next(List.of(), new Random(1)));
    }
}
```

```java
// WantTest.java
package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WantTest {
    @Test void encodesAndDecodes() {
        for (Want w : new Want[] {Want.SPACE, Want.PLANET, Want.SILENCE, Want.track("galaxy02")})
            assertEquals(w, Want.decode(w.encode()));
    }

    @Test void junkIsSpace() {
        assertEquals(Want.SPACE, Want.decode(null));
        assertEquals(Want.SPACE, Want.decode("???"));
        assertEquals(Want.SPACE, Want.decode("track:"));
    }
}
```

- [ ] **Step 2: Run** (all four) → FAIL.
- [ ] **Step 3: Implement**

```java
// Mood.java
package dev.moui.galaxycraft.music;

public enum Mood { SPACE, PLANET }
```
```java
// Source.java
package dev.moui.galaxycraft.music;

public enum Source { SMG2, MINECRAFT }
```
```java
// Track.java
package dev.moui.galaxycraft.music;

import java.util.List;

/** One song. mood is null for a song in no automatic pool; file is relative to its source's folder. */
public record Track(String id, String title, String file, Source source, Mood mood, List<String> tags, boolean enabled) {
    public Track {
        tags = List.copyOf(tags);
    }

    public Track withMood(Mood m) {
        return new Track(id, title, file, source, m, tags, enabled);
    }

    public Track withEnabled(boolean on) {
        return new Track(id, title, file, source, mood, tags, on);
    }
}
```
```java
// Want.java
package dev.moui.galaxycraft.music;

/** What the place calls for: a mood's music, one song, or nothing. */
public record Want(Kind kind, String trackId) {
    public enum Kind { SPACE, PLANET, TRACK, SILENCE }

    public static final Want SPACE = new Want(Kind.SPACE, null), PLANET = new Want(Kind.PLANET, null),
            SILENCE = new Want(Kind.SILENCE, null);

    public static Want track(String id) {
        return new Want(Kind.TRACK, id);
    }

    public String encode() {
        return switch (kind) {
            case SPACE -> "space";
            case PLANET -> "planet";
            case SILENCE -> "silence";
            case TRACK -> "track:" + trackId;
        };
    }

    public static Want decode(String s) {
        if (s == null) return SPACE;
        return switch (s) {
            case "space" -> SPACE;
            case "planet" -> PLANET;
            case "silence" -> SILENCE;
            default -> s.startsWith("track:") && s.length() > 6 ? track(s.substring(6)) : SPACE;
        };
    }
}
```
```java
// SourceMode.java
package dev.moui.galaxycraft.music;

import java.util.List;

/** Which games' songs the automatic music uses. */
public enum SourceMode {
    BOTH("Both games"), SMG2("Super Mario Galaxy 2"), MINECRAFT("Minecraft"), RANDOM("Random (any)");

    private final String label;

    SourceMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Space and planet only matter when songs are chosen by mood; Minecraft only and Random ignore it. */
    public Want apply(Want w) {
        boolean moody = this == BOTH || this == SMG2;
        return !moody && (w.kind() == Want.Kind.SPACE || w.kind() == Want.Kind.PLANET) ? Want.PLANET : w;
    }

    /** The enabled songs automatic music may pick for a mood want (SPACE or PLANET). */
    public List<Track> pool(List<Track> all, Want want) {
        Mood mood = want.kind() == Want.Kind.SPACE ? Mood.SPACE : Mood.PLANET;
        return all.stream().filter(Track::enabled).filter(t -> switch (this) {
            case BOTH -> t.mood() == mood;
            case SMG2 -> t.source() == Source.SMG2 && t.mood() == mood;
            case MINECRAFT -> t.source() == Source.MINECRAFT;
            case RANDOM -> t.mood() != null;
        }).toList();
    }
}
```
```java
// Catalog.java
package dev.moui.galaxycraft.music;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * tracks.tsv: one song a line, tab separated: id, title, file, source (smg2|minecraft), mood
 * (space|planet|empty), tags (comma separated), enabled (true|false). Lines starting with # are notes.
 */
public final class Catalog {
    private Catalog() {}

    public static List<Track> parse(String text) {
        List<Track> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;
            Source source;
            switch (f[3].strip().toLowerCase()) {
                case "smg2" -> source = Source.SMG2;
                case "minecraft" -> source = Source.MINECRAFT;
                default -> { continue; }
            }
            Mood mood = switch (f[4].strip().toLowerCase()) {
                case "space" -> Mood.SPACE;
                case "planet" -> Mood.PLANET;
                default -> null;
            };
            List<String> tags = Arrays.stream(f[5].split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            out.add(new Track(f[0].strip(), f[1].strip(), f[2].strip(), source, mood, tags, f[6].strip().equals("true")));
        }
        return out;
    }

    public static String format(List<Track> tracks) {
        StringBuilder b = new StringBuilder("# id\ttitle\tfile\tsource\tmood\ttags\tenabled\n");
        for (Track t : tracks)
            b.append(t.id()).append('\t').append(t.title().replace('\t', ' ')).append('\t').append(t.file()).append('\t')
                    .append(t.source().name().toLowerCase()).append('\t').append(t.mood() == null ? "" : t.mood().name().toLowerCase())
                    .append('\t').append(String.join(",", t.tags())).append('\t').append(t.enabled()).append('\n');
        return b.toString();
    }

    public static List<Track> read(Path file) throws IOException {
        return Files.isRegularFile(file) ? parse(Files.readString(file, StandardCharsets.UTF_8)) : List.of();
    }

    public static void write(Path file, List<Track> tracks) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, format(tracks), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
```
```java
// ShuffleBag.java
package dev.moui.galaxycraft.music;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Picks songs at random, each once per round, never the one that just played. */
public final class ShuffleBag {
    private final Set<String> played = new HashSet<>();
    private String last;

    public Track next(List<Track> pool, Random random) {
        if (pool.isEmpty()) return null;
        List<Track> fresh = pool.stream().filter(t -> !played.contains(t.id())).toList();
        if (fresh.isEmpty()) {
            played.clear();
            fresh = pool;
        }
        List<Track> choices = fresh.stream().filter(t -> !t.id().equals(last)).toList();
        if (choices.isEmpty()) choices = fresh;
        Track pick = choices.get(random.nextInt(choices.size()));
        played.add(pick.id());
        last = pick.id();
        return pick;
    }
}
```

- [ ] **Step 4: Run** all four test classes → PASS. In `ShuffleBagTest.usesEveryTrackOncePerRound…`, if a round boundary yields a repeat (last of round N equals first of round N+1) the bag excludes `last`, so the assertion holds.
- [ ] **Step 5: Commit** — `music: catalog, source modes, want, shuffle`.

---

### Task 5: Station music store

**Files:**
- Create: `fabric/src/main/java/dev/moui/galaxycraft/music/StationMusic.java`
- Test: `fabric/src/test/java/dev/moui/galaxycraft/music/StationMusicTest.java`

**Interfaces:**
- Consumes: `Want`.
- Produces: `new StationMusic(Path file)` (null path: in memory), `Want get(String stationId)` (default `Want.SPACE`), `void set(String stationId, Want w)` (saves at once; `Want.SPACE` removes the entry).

- [ ] **Step 1: Failing test**

```java
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
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**

```java
package dev.moui.galaxycraft.music;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** The music each station plays while the player is on it: station id to a {@link Want}. */
public final class StationMusic {
    private final Path file;
    private final Properties map = new Properties();

    public StationMusic(Path file) {
        this.file = file;
        if (file == null || !Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            map.load(r);
        } catch (IOException | IllegalArgumentException e) {
            map.clear();
        }
    }

    public Want get(String stationId) {
        return Want.decode(map.getProperty(stationId));
    }

    public void set(String stationId, Want w) {
        if (w.equals(Want.SPACE)) map.remove(stationId);
        else map.setProperty(stationId, w.encode());
        save();
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                map.store(w, "Station music");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("GalaxyCraft: could not save station music: " + e);
        }
    }
}
```

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** — `music: per-station music choice`.

---

### Task 6: Music settings

**Files:**
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/GalaxyOptions.java` (add after `PARTICLES` at line ~62, same style)

**Interfaces:**
- Produces (static fields on `GalaxyOptions`): `MUSIC_AUTO` (`Setting.Toggle`), `MUSIC_SOURCE` (`Setting.Choice<SourceMode>`), `MUSIC_COOLDOWN`, `MUSIC_DWELL`, `MUSIC_CROSSFADE`, `MUSIC_VOLUME` (`Setting.Range`; seconds, seconds, seconds, percent).

- [ ] **Step 1: Read** `GalaxyOptions.java` lines 30–80 to see where `PARTICLES` ends and the `Action` list (`ACTIONS`/`ENTRIES`) is built.
- [ ] **Step 2: Add the settings** (labels are what players read; `Range(key,label,tooltip,min,max,step,fallback,unit)`):

```java
    public static final Setting.Toggle MUSIC_AUTO = SETTINGS.add(new Setting.Toggle("musicAuto", "Automatic Music",
            "The soundtrack follows where you are: space or a planet.\nOff: music plays only what you pick in the music player.", true));
    public static final Setting.Choice<dev.moui.galaxycraft.music.SourceMode> MUSIC_SOURCE = SETTINGS.add(new Setting.Choice<>(
            "musicSource", "Music Source",
            "Both games: Super Mario Galaxy 2 by place, Minecraft's music on planets.\n"
                    + "Super Mario Galaxy 2: only its songs, by place.\nMinecraft: only Minecraft's music, everywhere.\n"
                    + "Random: any song of either game, wherever you are.",
            dev.moui.galaxycraft.music.SourceMode.class, dev.moui.galaxycraft.music.SourceMode.BOTH,
            dev.moui.galaxycraft.music.SourceMode::label));
    public static final Setting.Range MUSIC_COOLDOWN = SETTINGS.add(new Setting.Range("musicCooldown", "Music Cooldown",
            "After the music changes, it will not change again for this long (0: no cooldown).", 0, 600, 5, 120, " s"));
    public static final Setting.Range MUSIC_DWELL = SETTINGS.add(new Setting.Range("musicDwell", "Music Delay",
            "How long you must stay in a new place before its music starts.", 0, 60, 1, 5, " s"));
    public static final Setting.Range MUSIC_CROSSFADE = SETTINGS.add(new Setting.Range("musicCrossfade", "Music Crossfade",
            "How long one song takes to melt into the next (0: a cut).", 0, 15, 1, 4, " s"));
    public static final Setting.Range MUSIC_VOLUME = SETTINGS.add(new Setting.Range("musicVolume", "Soundtrack Volume",
            "On top of Minecraft's Music and Master volume.", 0, 100, 5, 100, "%"));
```

- [ ] **Step 3: Compile** — `cd fabric && ./gradlew compileClientJava --console=plain -q` → no errors (use `JAVA_HOME` as in `test.sh`).
- [ ] **Step 4: Commit** — `music: settings (source, cooldown, delay, crossfade, volume)`.

---

### Task 7: Client service — output, library, Minecraft songs, sensor, tick

**Files:**
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/JavaSoundSink.java`
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/OggSource.java`
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/MusicLibrary.java`
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/MusicSensor.java`
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/MusicService.java`
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/mixin/MusicManagerMixin.java`
- Modify: `fabric/src/client/resources/galaxycraft.client.mixins.json` (add `"MusicManagerMixin"` to `client`)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/GalaxyCraftClient.java` (register tick + stop on disconnect)

**Interfaces:**
- Consumes: `Mixer`, `SwitchGate<Want>`, `SourceMode`, `Catalog`, `ShuffleBag`, `Want`, `StationMusic`, `AstFile/AstSource`, settings of Task 6, `PlanetClient.standingOn()`, `StationClient.sessions()` / `StationClient.of(session)`.
- Produces: `MusicService` static API used by the UI/station tasks:
  `void tick()` (client tick), `void shutdown()`, `List<Track> tracks()`, `Track playing()` (null if none), `void playNow(Track t)` (player pick: crossfades, pins), `void auto()` (unpin, back to automatic), `boolean pinned()`, `void pause(boolean)`/`boolean paused()`, `void next()`/`void previous()`, `void setMood(Track, Mood)`, `void setEnabled(Track, boolean)`, `double positionSeconds()`, `double remainingSeconds()`, `StationMusic stationMusic()`, `void stationPick(String stationId, Want w)` (remembers and applies at once), `static boolean shouldReplaceVanilla()`.

- [ ] **Step 1: JavaSoundSink**

```java
package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.Mixer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Runs a {@link Mixer} into the default audio line on a daemon thread. No device: the music is just off. */
final class JavaSoundSink implements Runnable {
    private static final int BLOCK = 1024;
    private final Mixer mixer;
    private volatile boolean running = true;
    private Thread thread;

    JavaSoundSink(Mixer mixer) {
        this.mixer = mixer;
    }

    void start() {
        thread = new Thread(this, "GalaxyCraft music");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        AudioFormat fmt = new AudioFormat(Mixer.RATE, 16, 2, true, false);
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, Mixer.RATE / 5 * 4); // ~0.2 s
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            System.err.println("GalaxyCraft: no audio line for the soundtrack: " + e);
            return;
        }
        float[] mix = new float[BLOCK * 2];
        byte[] bytes = new byte[BLOCK * 4];
        try {
            while (running) {
                mixer.render(mix, BLOCK);
                for (int i = 0; i < BLOCK * 2; i++) {
                    int s = Math.round(Math.clamp(mix[i], -1f, 1f) * 32767f);
                    bytes[2 * i] = (byte) s;
                    bytes[2 * i + 1] = (byte) (s >> 8);
                }
                line.write(bytes, 0, bytes.length);
            }
        } finally {
            line.stop();
            line.close();
        }
    }
}
```

- [ ] **Step 2: OggSource** (Minecraft's own songs through vanilla's decoder):

```java
package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.PcmSource;
import it.unimi.dsi.fastutil.floats.FloatConsumer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import net.minecraft.client.sounds.JOrbisAudioStream;

/** A Minecraft music .ogg decoded by vanilla's JOrbis, as stereo frames. */
final class OggSource implements PcmSource, FloatConsumer {
    private final JOrbisAudioStream stream;
    private final int rate, channels;
    private float[] queue = new float[1 << 15];
    private int head, tail; // floats
    private boolean done;
    private long pos;

    OggSource(InputStream in) throws IOException {
        stream = new JOrbisAudioStream(in);
        rate = (int) stream.getFormat().getSampleRate();
        channels = stream.getFormat().getChannels();
    }

    @Override public void accept(float v) {
        if (tail == queue.length) {
            if (head > 0) {
                System.arraycopy(queue, head, queue, 0, tail - head);
                tail -= head;
                head = 0;
            } else {
                queue = java.util.Arrays.copyOf(queue, queue.length * 2);
            }
        }
        queue[tail++] = v;
    }

    @Override public int rate() { return rate; }
    @Override public long positionFrames() { return pos; }

    @Override public int read(float[] out, int offset, int frames) {
        int need = frames * channels;
        try {
            while (!done && tail - head < need) if (!stream.readChunk(this)) done = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int n = Math.min(frames, (tail - head) / channels);
        for (int i = 0; i < n; i++) {
            float l = queue[head], r = channels > 1 ? queue[head + 1] : l;
            head += channels;
            out[(offset + i) * 2] = l;
            out[(offset + i) * 2 + 1] = r;
        }
        pos += n;
        return n;
    }

    @Override public void close() {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }
}
```

- [ ] **Step 3: MusicLibrary** — loads `tracks.tsv`, adds Minecraft songs, opens sources.

```java
package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.AstFile;
import dev.moui.galaxycraft.music.AstSource;
import dev.moui.galaxycraft.music.Catalog;
import dev.moui.galaxycraft.music.Mood;
import dev.moui.galaxycraft.music.PcmSource;
import dev.moui.galaxycraft.music.Source;
import dev.moui.galaxycraft.music.Track;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/** The songs the player can hear: soundtrack/tracks.tsv (SMG2) plus Minecraft's own music/game songs. */
final class MusicLibrary {
    private final Path dir = FabricLoader.getInstance().getGameDir().resolve("soundtrack");
    private final Path tsv = dir.resolve("tracks.tsv");
    private final Path mcTsv = dir.resolve("minecraft.tsv");
    private List<Track> tracks = List.of();

    Path dir() {
        return dir;
    }

    List<Track> tracks() {
        return tracks;
    }

    /** Reads the catalog again and finds Minecraft's songs (its choices of mood/enabled are kept in minecraft.tsv). */
    void reload() {
        List<Track> all = new ArrayList<>();
        try {
            all.addAll(Catalog.read(tsv));
        } catch (IOException e) {
            System.err.println("GalaxyCraft: could not read " + tsv + ": " + e);
        }
        all.addAll(minecraftTracks());
        tracks = List.copyOf(all);
    }

    private List<Track> minecraftTracks() {
        List<Track> found = new ArrayList<>();
        Map<Identifier, Resource> res = Minecraft.getInstance().getResourceManager()
                .listResources("sounds/music", id -> id.getPath().endsWith(".ogg") && id.getPath().contains("/game/"));
        for (Identifier id : res.keySet()) {
            String path = id.getPath(); // sounds/music/game/calm1.ogg
            String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
            found.add(new Track("mc:" + id.getNamespace() + ":" + path, "Minecraft: " + name, id.toString(),
                    Source.MINECRAFT, Mood.PLANET, List.of(), true));
        }
        found.sort((a, b) -> a.id().compareTo(b.id()));
        List<Track> saved;
        try {
            saved = Catalog.read(mcTsv);
        } catch (IOException e) {
            return found;
        }
        return found.stream().map(t -> saved.stream().filter(s -> s.id().equals(t.id())).findFirst()
                .map(s -> t.withEnabled(s.enabled()).withMood(s.mood())).orElse(t)).toList();
    }

    /** Saves the player's mood/enabled choices. */
    void save() {
        try {
            Catalog.write(tsv, tracks.stream().filter(t -> t.source() == Source.SMG2).toList());
            Catalog.write(mcTsv, tracks.stream().filter(t -> t.source() == Source.MINECRAFT).toList());
        } catch (IOException e) {
            System.err.println("GalaxyCraft: could not save the music catalog: " + e);
        }
    }

    void replace(Track old, Track now) {
        List<Track> l = new ArrayList<>(tracks);
        l.replaceAll(t -> t.id().equals(old.id()) ? now : t);
        tracks = List.copyOf(l);
        save();
    }

    /** Opens a song for playing; null (and one log line) if its file is missing or broken. */
    PcmSource open(Track t) {
        try {
            if (t.source() == Source.SMG2) {
                Path f = dir.resolve(t.file());
                if (!Files.isRegularFile(f)) throw new IOException("not on disk: " + f);
                return new AstSource(AstFile.open(f));
            }
            Identifier id = Identifier.parse(t.file());
            InputStream in = Minecraft.getInstance().getResourceManager().open(id);
            return new OggSource(in);
        } catch (IOException | RuntimeException e) {
            System.err.println("GalaxyCraft: cannot play " + t.title() + ": " + e);
            return null;
        }
    }
}
```

- [ ] **Step 4: Verify the Minecraft API names used above** against the sources jar (`fabric/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-7e9a32a5b8/26.3/minecraft-clientOnly-7e9a32a5b8-26.3-sources.jar`, and the `-common` jar for `Identifier`/`ResourceManager`): `ResourceManager.listResources(String, Predicate<Identifier>)` returns `Map<Identifier, Resource>`; `ResourceProvider.open(Identifier)` (as `SoundBufferLibrary` uses); `Identifier.parse`. Fix the imports/names if they differ. Run `./gradlew compileClientJava` until it compiles (the compile errors name exactly what to change).

- [ ] **Step 5: MusicSensor** — what the place calls for.

```java
package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.StationClient;
import dev.moui.galaxycraft.music.StationMusic;
import dev.moui.galaxycraft.music.Want;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.Station;
import org.joml.Vector3d;

/** Reads the game: on a station, its music; in a planet's gravity, planet music; else space music. */
final class MusicSensor {
    private MusicSensor() {}

    static Want wanted(StationMusic stations) {
        PlanetSession s = PlanetClient.standingOn();
        if (s != null) return Want.PLANET;
        Station st = stationUnderfoot();
        return st == null ? Want.SPACE : stations.get(st.id);
    }

    /** The station the player is in the gravity of, or null. */
    static Station stationUnderfoot() {
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (feet == null) return null;
        for (PlanetSession s : StationClient.sessions())
            if (s.active() && s.center().distance(feet) <= s.gravityUnits()) return StationClient.of(s).orElse(null);
        return null;
    }
}
```
Note: `PlanetClient`/`StationClient` members used here (`standingOn`, `sessions`, `of`) are public. `GalaxyCraftClient.galaxyPos()` is used by `PlanetClient.standingOn()` the same way; if it is package-private, make this class use `dev.moui.galaxycraft.client` package-private access by moving `MusicSensor` into `dev.moui.galaxycraft.client` (then fix the imports).
For a flat station, "in its gravity" may need `GravityBody.outside(p) <= 0` instead of the sphere test: check `PlanetSession.gravityUnits()` for stations (read `PlanetSession` ~line 280–300 and `StationClient.Active`), and use the same test `StationClient.tick` uses to decide the player is on a station; adapt `stationUnderfoot()` to it.

- [ ] **Step 6: MusicService**

```java
package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.music.Mixer;
import dev.moui.galaxycraft.music.Mood;
import dev.moui.galaxycraft.music.PcmSource;
import dev.moui.galaxycraft.music.ShuffleBag;
import dev.moui.galaxycraft.music.StationMusic;
import dev.moui.galaxycraft.music.SwitchGate;
import dev.moui.galaxycraft.music.Track;
import dev.moui.galaxycraft.music.Want;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/** The soundtrack: ties the library, the gate, the mixer and the game together. Client thread only. */
public final class MusicService {
    private static final Mixer mixer = new Mixer();
    private static final JavaSoundSink sink = new JavaSoundSink(mixer);
    private static final MusicLibrary library = new MusicLibrary();
    private static final SwitchGate<Want> gate = new SwitchGate<>();
    private static final ShuffleBag bag = new ShuffleBag();
    private static final Random random = new Random();
    private static final AtomicBoolean ended = new AtomicBoolean();
    private static StationMusic stations;
    private static boolean started, pinned, paused, queuedNext;
    private static Track playing;
    private static Want playingWant;
    private static double clock;
    private static long lastNanos;

    private MusicService() {}

    public static List<Track> tracks() { return library.tracks(); }
    public static Track playing() { return playing; }
    public static boolean pinned() { return pinned; }
    public static boolean paused() { return paused; }
    public static double positionSeconds() { return mixer.positionSeconds(); }
    public static double remainingSeconds() { return mixer.remainingSeconds(); }

    /** True while automatic music owns the music: vanilla's own stays quiet. */
    public static boolean shouldReplaceVanilla() { return started && GalaxyOptions.MUSIC_AUTO.get(); }

    public static StationMusic stationMusic() { return stations; }

    private static void ensureStarted() {
        if (started) return;
        started = true;
        stations = new StationMusic(FabricLoader.getInstance().getConfigDir().resolve("galaxycraft-station-music.properties"));
        library.reload();
        mixer.onEnd(() -> ended.set(true));
        sink.start();
    }

    public static void shutdown() {
        if (!started) return;
        mixer.stop(0);
        sink.stop();
        started = false;
        pinned = false;
        playing = null;
        gate.reset();
    }

    /** Every client tick, in a world. */
    public static void tick() {
        ensureStarted();
        long now = System.nanoTime();
        clock += lastNanos == 0 ? 0 : (now - lastNanos) / 1e9;
        lastNanos = now;
        Minecraft mc = Minecraft.getInstance();
        // getFinalSoundSourceVolume already multiplies the Music slider by the Master slider
        mixer.setVolume(GalaxyOptions.MUSIC_VOLUME.get() / 100f * mc.options.getFinalSoundSourceVolume(SoundSource.MUSIC));
        if (paused) return;

        if (ended.getAndSet(false)) { // a non-looping song ended
            queuedNext = false;
            playing = null;
            if (pinned) advance(1);
            else playFor(gate.current(), true);
        }
        // start the next song early so a song without a loop point crossfades into the next
        if (!queuedNext && playing != null && mixer.remainingSeconds() <= GalaxyOptions.MUSIC_CROSSFADE.get()
                && mixer.remainingSeconds() != Double.POSITIVE_INFINITY) {
            queuedNext = true;
            if (pinned) advance(1);
            else playFor(gate.current(), true);
        }
        if (pinned || !GalaxyOptions.MUSIC_AUTO.get()) return;
        Want wanted = GalaxyOptions.MUSIC_SOURCE.get().apply(MusicSensor.wanted(stations));
        Want go = gate.update(clock, wanted, GalaxyOptions.MUSIC_DWELL.get(), GalaxyOptions.MUSIC_COOLDOWN.get());
        if (go != null) playFor(go, false);
    }

    /** Starts what a want calls for: a song of its pool, one song, or silence. */
    private static void playFor(Want w, boolean fromEnd) {
        if (w == null) return;
        playingWant = w;
        switch (w.kind()) {
            case SILENCE -> {
                mixer.stop(GalaxyOptions.MUSIC_CROSSFADE.get());
                playing = null;
            }
            case TRACK -> library.tracks().stream().filter(t -> t.id().equals(w.trackId())).findFirst()
                    .ifPresentOrElse(t -> start(t), () -> mixer.stop(GalaxyOptions.MUSIC_CROSSFADE.get()));
            default -> {
                List<Track> pool = GalaxyOptions.MUSIC_SOURCE.get().pool(library.tracks(), w);
                for (int tries = 0; tries < 5 && !pool.isEmpty(); tries++) {
                    Track t = bag.next(pool, random);
                    if (start(t)) return;
                }
                mixer.stop(GalaxyOptions.MUSIC_CROSSFADE.get()); // nothing playable: silence, no loop
                playing = null;
            }
        }
    }

    private static boolean start(Track t) {
        PcmSource src = library.open(t);
        if (src == null) return false;
        mixer.play(src, GalaxyOptions.MUSIC_CROSSFADE.get());
        playing = t;
        queuedNext = false;
        return true;
    }

    // ---- the player's choices ----

    public static void playNow(Track t) {
        ensureStarted();
        pinned = true;
        paused = false;
        mixer.setPaused(false);
        start(t);
    }

    /** Back to automatic music: the gate starts over, the place decides. */
    public static void auto() {
        pinned = false;
        gate.reset();
    }

    public static void pause(boolean p) {
        paused = p;
        mixer.setPaused(p);
    }

    public static void next() { advance(1); }
    public static void previous() { advance(-1); }

    /** The next/previous enabled song of the library (by order); pins. */
    private static void advance(int dir) {
        List<Track> all = library.tracks().stream().filter(Track::enabled).toList();
        if (all.isEmpty()) return;
        int i = playing == null ? -1 : all.indexOf(playing);
        Track t = all.get(Math.floorMod(i + dir, all.size()));
        pinned = true;
        start(t);
    }

    public static void setMood(Track t, Mood m) { library.replace(t, t.withMood(m)); }
    public static void setEnabled(Track t, boolean on) { library.replace(t, t.withEnabled(on)); }

    /** A station's Music button: remembered, and played at once (a pick, so no cooldown). */
    public static void stationPick(String stationId, Want w) {
        ensureStarted();
        stations.set(stationId, w);
        pinned = false;
        Want eff = GalaxyOptions.MUSIC_SOURCE.get().apply(w);
        gate.force(eff, clock);
        playFor(eff, false);
    }
}
```
- [ ] **Step 7: Silence vanilla music while automatic music owns it**

```java
package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.music.MusicService;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
abstract class MusicManagerMixin {
    /** The soundtrack plays Minecraft's songs itself (with its crossfade): vanilla stays quiet. */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$quiet(CallbackInfo ci) {
        if (MusicService.shouldReplaceVanilla()) ci.cancel();
    }
}
```
Add `"MusicManagerMixin"` to the `client` array of `galaxycraft.client.mixins.json` (alphabetical, after `LocalPlayerMixin`).

- [ ] **Step 8: Register in `GalaxyCraftClient.onInitializeClient`** next to the other `ClientTickEvents.END_CLIENT_TICK.register` calls:

```java
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player != null && mc.level != null) dev.moui.galaxycraft.client.music.MusicService.tick();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> dev.moui.galaxycraft.client.music.MusicService.shutdown());
```
(Read `GalaxyCraftClient.onInitializeClient` first; reuse its existing tick registration if there is one, and keep one `MusicService.tick()` call.) In the entering/loading screens before a world is up, `mc.player == null`, so nothing plays: the quiet-entry behaviour is kept.

- [ ] **Step 9: Compile and run all unit tests** — `cd fabric && ./gradlew compileClientJava --console=plain -q && ./test.sh` → compiles; all tests pass.
- [ ] **Step 10: Commit** — `music: client service (Java Sound output, library, Minecraft songs, sensor, vanilla silenced)`.

---

### Task 8: Player screen, keybinds, now playing, Station Core button

**Files:**
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/SoundtrackScreen.java`
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/MusicKeys.java`
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/NowPlaying.java`
- Create: `fabric/src/client/java/dev/moui/galaxycraft/client/music/StationMusicScreen.java`
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/StationScreen.java` (a Music button)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/PauseMenu.java` (a Music button; read it first for how it adds buttons)
- Modify: `fabric/src/main/resources/assets/galaxycraft/lang/en_us.json` (keys below)
- Modify: `fabric/src/client/java/dev/moui/galaxycraft/client/GalaxyCraftClient.java` (`MusicKeys.register()` and HUD hook)

**Interfaces:** Consumes the `MusicService` API of Task 7. Produces: `SoundtrackScreen.open()`, `StationMusicScreen.open(Station)`.

- [ ] **Step 1: Confirm the keybinding API** — read `net/minecraft/client/KeyMapping.java` lines 80–110 and 195–225 in the sources jar (category registration: `KeyMapping.Category.register(Identifier)`), and find Fabric's registration helper: `unzip -l` the fabric-key-mapping/-binding API jar under `~/.gradle/caches` (search `find ~/.gradle -name 'fabric-key*'`) for `KeyMappingHelper.registerKeyMapping` (or `KeyBindingHelper.registerKeyBinding`). Use the names that exist.
- [ ] **Step 2: MusicKeys** (adapt names per Step 1):

```java
package dev.moui.galaxycraft.client.music;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Keys of the music player: M opens it; the rest are unbound until the player sets them. */
public final class MusicKeys {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.parse("galaxycraft:music"));
    private static final KeyMapping OPEN = key("open", InputConstants.KEY_M);
    private static final KeyMapping PLAY_PAUSE = key("play_pause", InputConstants.UNKNOWN.getValue());
    private static final KeyMapping NEXT = key("next", InputConstants.UNKNOWN.getValue());
    private static final KeyMapping PREVIOUS = key("previous", InputConstants.UNKNOWN.getValue());

    private MusicKeys() {}

    private static KeyMapping key(String name, int code) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galaxycraft.music." + name,
                InputConstants.Type.KEYSYM, code, CATEGORY));
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN.consumeClick()) if (mc.gui.screen() == null) SoundtrackScreen.open();
            while (PLAY_PAUSE.consumeClick()) MusicService.pause(!MusicService.paused());
            while (NEXT.consumeClick()) MusicService.next();
            while (PREVIOUS.consumeClick()) MusicService.previous();
        });
    }
}
```

- [ ] **Step 3: SoundtrackScreen** — a paged list of songs (8 rows), a filter button (All / Space / Planet / Minecraft / Unsorted), row click plays, a small "mood" cycle button per row (Space/Planet/none), an on/off toggle per row, and transport buttons (Previous, Pause/Play, Next, Auto). Follows `StationScreen`'s API (`Screen`, `Button.builder(...).bounds(...).build()`, `extractRenderState`, `g.text`, `g.centeredText`):

```java
package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.Mood;
import dev.moui.galaxycraft.music.Source;
import dev.moui.galaxycraft.music.Track;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The music player: the library, a mood for each song, and the transport. */
public final class SoundtrackScreen extends Screen {
    private static final int ROWS = 8, WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, GREEN = 0xFF55FF55;
    private enum Filter { ALL, SPACE, PLANET, MINECRAFT, UNSORTED }
    private Filter filter = Filter.ALL;
    private int page;

    private SoundtrackScreen() {
        super(Component.translatable("screen.galaxycraft.music.title"));
    }

    public static void open() {
        Minecraft.getInstance().gui.setScreen(new SoundtrackScreen());
    }

    private List<Track> shown() {
        return MusicService.tracks().stream().filter(t -> switch (filter) {
            case ALL -> true;
            case SPACE -> t.mood() == Mood.SPACE && t.source() == Source.SMG2;
            case PLANET -> t.mood() == Mood.PLANET && t.source() == Source.SMG2;
            case MINECRAFT -> t.source() == Source.MINECRAFT;
            case UNSORTED -> t.mood() == null;
        }).toList();
    }

    @Override
    protected void init() {
        int w = Math.min(width - 20, 360), x = (width - w) / 2, y = 34;
        List<Track> list = shown();
        int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS);
        page = Math.clamp(page, 0, pages - 1);
        for (int i = 0; i < ROWS && page * ROWS + i < list.size(); i++) {
            Track t = list.get(page * ROWS + i);
            int ry = y + i * 22;
            addRenderableWidget(Button.builder(Component.literal((MusicService.playing() == t ? "> " : "") + t.title()),
                    b -> MusicService.playNow(t)).bounds(x, ry, w - 110, 20).build());
            addRenderableWidget(Button.builder(Component.literal(t.mood() == null ? "-" : t.mood() == Mood.SPACE ? "Space" : "Planet"),
                    b -> {
                        MusicService.setMood(t, t.mood() == null ? Mood.SPACE : t.mood() == Mood.SPACE ? Mood.PLANET : null);
                        rebuildWidgets();
                    }).bounds(x + w - 106, ry, 56, 20).build());
            addRenderableWidget(Button.builder(Component.literal(t.enabled() ? "ON" : "OFF"), b -> {
                MusicService.setEnabled(t, !t.enabled());
                rebuildWidgets();
            }).bounds(x + w - 46, ry, 46, 20).build());
        }
        int by = y + ROWS * 22 + 6, bw = (w - 12) / 4;
        addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> MusicService.previous()).bounds(x, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(MusicService.paused() ? "Play" : "Pause"), b -> {
            MusicService.pause(!MusicService.paused());
            rebuildWidgets();
        }).bounds(x + bw + 4, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next >"), b -> MusicService.next()).bounds(x + 2 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.galaxycraft.music.auto"), b -> MusicService.auto())
                .bounds(x + 3 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Filter: " + filter.name()), b -> {
            filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
            page = 0;
            rebuildWidgets();
        }).bounds(x, by + 24, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
                .bounds(x + w - 90, by + 24, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
                .bounds(x + w - 20, by + 24, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(x + w / 2 - 50, by + 50, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title.getString(), width / 2, 14, WHITE);
        Track now = MusicService.playing();
        String line = now == null ? Component.translatable("screen.galaxycraft.music.silent").getString()
                : now.title() + (MusicService.pinned() ? "  (picked)" : "  (automatic)");
        g.centeredText(font, line, width / 2, 24, now == null ? GRAY : GREEN);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
```
(The `-` mood label means the song is in no automatic pool. `screen.galaxycraft.music.auto` is "Automatic".)

- [ ] **Step 4: Now playing toast** — a short line on the HUD when a song starts. Simplest and safe: reuse Minecraft's now-playing mechanism is vanilla-only, so use `Minecraft.getInstance().gui.setOverlayMessage(Component.literal("♪ " + title), false)` from `MusicService.start` (add one line after `playing = t;`: `Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("music.galaxycraft.now_playing", t.title()), false);`). `NowPlaying.java` is therefore not needed: drop it from the file list.
- [ ] **Step 5: StationMusicScreen + button** — a screen listing: "Space music", "Planet music", "Silence", then every SMG2 song (paged like above), each calling `MusicService.stationPick(station.id, want)` and closing. In `StationScreen.init()` add a fourth button under the row: `Button.builder(Component.translatable("screen.galaxycraft.station.music"), b -> StationMusicScreen.open(station)).bounds(x, y + 84, w, 20).build()`. Code (same shape as `SoundtrackScreen`, 8 rows per page, entries = three fixed wants then `MusicService.tracks()` filtered to `Source.SMG2`):

```java
package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.Source;
import dev.moui.galaxycraft.music.Track;
import dev.moui.galaxycraft.music.Want;
import dev.moui.galaxycraft.voxel.Station;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A Station Core's Music: which music plays while you are on this station. */
public final class StationMusicScreen extends Screen {
    private static final int ROWS = 8;
    private record Entry(String label, Want want) {}
    private final Station station;
    private int page;

    private StationMusicScreen(Station station) {
        super(Component.translatable("screen.galaxycraft.station.music"));
        this.station = station;
    }

    public static void open(Station station) {
        Minecraft.getInstance().gui.setScreen(new StationMusicScreen(station));
    }

    private List<Entry> entries() {
        List<Entry> e = new ArrayList<>(List.of(new Entry("Space music", Want.SPACE), new Entry("Planet music", Want.PLANET),
                new Entry("Silence", Want.SILENCE)));
        for (Track t : MusicService.tracks()) if (t.source() == Source.SMG2) e.add(new Entry(t.title(), Want.track(t.id())));
        return e;
    }

    @Override
    protected void init() {
        int w = Math.min(width - 20, 260), x = (width - w) / 2, y = 34;
        List<Entry> all = entries();
        int pages = Math.max(1, (all.size() + ROWS - 1) / ROWS);
        page = Math.clamp(page, 0, pages - 1);
        Want chosen = MusicService.stationMusic().get(station.id);
        for (int i = 0; i < ROWS && page * ROWS + i < all.size(); i++) {
            Entry en = all.get(page * ROWS + i);
            addRenderableWidget(Button.builder(Component.literal((en.want().equals(chosen) ? "> " : "") + en.label()), b -> {
                MusicService.stationPick(station.id, en.want());
                onClose();
            }).bounds(x, y + i * 22, w, 20).build());
        }
        int by = y + ROWS * 22 + 6;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(x, by, 40, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + w / 2 - 50, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(x + w - 40, by, 40, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
```
`StationScreen` has `station` (a `Station`) and its row of buttons ends at `y + 80`; place the Music button at `y + 84` and move nothing else.

- [ ] **Step 6: Pause menu button** — read `PauseMenu.register()` (`ScreenEvents.AFTER_INIT`); add next to its existing button a "Music" button that calls `SoundtrackScreen.open()`, using the same placement code it uses for the existing one.
- [ ] **Step 7: Lang keys** in `en_us.json`:

```json
  "screen.galaxycraft.music.title": "Soundtrack",
  "screen.galaxycraft.music.auto": "Automatic",
  "screen.galaxycraft.music.silent": "Nothing playing",
  "screen.galaxycraft.station.music": "Music",
  "music.galaxycraft.now_playing": "♪ %s",
  "key.galaxycraft.music.open": "Open Soundtrack",
  "key.galaxycraft.music.play_pause": "Play / Pause Music",
  "key.galaxycraft.music.next": "Next Song",
  "key.galaxycraft.music.previous": "Previous Song",
  "key.category.galaxycraft.music": "Soundtrack"
```
- [ ] **Step 8: In `GalaxyCraftClient.onInitializeClient`** call `dev.moui.galaxycraft.client.music.MusicKeys.register();`.
- [ ] **Step 9: Compile** — `cd fabric && ./gradlew compileClientJava --console=plain -q` → fix API mismatches the compiler names (widget and screen API follow `StationScreen`'s). Then `./test.sh` → all pass.
- [ ] **Step 10: Commit** — `music: player screen, keys, station Music button`.

---

### Task 9: Listening check and probe

**Files:**
- Create: `fabric/src/gametest/java/dev/moui/galaxycraft/gametest/SoundtrackProbe.java` (follow `LauncherProbe`'s structure; read it and `tools/gxvoxel.sh` first)
- Modify: `ROADMAP.md`

- [ ] **Step 1: Put three synthetic or real test songs in a scratch folder only** — the three Starship Mario files may be extracted to the scratchpad (`dolphin-tool extract … -s AudioRes/Stream/SMG2_mario_ship01_strm.ast`), never into the repo or the game folder the player uses. A scratch `tracks.tsv` lists them (`ship01` as `space`, `ship02` as `planet`) and a run with `-Dgalaxycraft.soundtrackDir=<scratch>` (read in `MusicLibrary` instead of `getGameDir()/soundtrack` when set) uses it. Add that property to `MusicLibrary.dir`.
- [ ] **Step 2: SoundtrackProbe** drives the sensor through planet → space → planet with the mixer on a null device (property `-Dgalaxycraft.soundtrackNull=true` makes `ensureStarted` skip `sink.start()` and the probe call `mixer.render` itself), reading `MusicService.playing()` after each leg and with cooldown 120 s / 0 s; it logs the track ids and `SOUNDTRACK PROBE OK` or the failure.
- [ ] **Step 3: Run it** the way the other probes run (`tools/gxvoxel.sh`'s pattern; never while `gxplay` runs) and read the log.
- [ ] **Step 4: Listening check with the user** — they set cooldown to 10 s, fly planet → space → planet, and judge: crossfade length, loop seams, volume against Minecraft's slider, the cooldown. Do not merge or post a devlog before they have tested (`merge-after-playtest`).
- [ ] **Step 5: Roadmap** — update the "Soundtrack player" row in `ROADMAP.md` (status stays `now`, note "awaiting playtest"; a follow-up row in Inbox for the install step that copies the classified `.ast` files, biome music, layered tracks). Bump "Last updated". Commit.

---

### Task 10 (later, once the user has finished classifying): install

Not part of this plan's execution. Recorded so nothing is lost: map the user's song titles to `AudioRes/Stream` files with them (draft list, they correct it), write `soundtrack/tracks.tsv` into the module, make the launcher's disc step copy the listed `.ast` files to `<game>/soundtrack/`, then the Windows/Linux packaging check. Needs the user's classification and title-to-file confirmation first.

## Self-review

- **Spec coverage:** engine (T1–2), mood logic with dwell/cooldown/crossfade/force (T3, T7), music source modes (T4, T7), catalog + classification (T4; data in T10), settings (T6), Station Core music (T5, T8), player UI/keybinds/now playing/pause button (T8), vanilla music silenced (T7), testing (every task; probe in T9), install deferred as the spec says (T10). Dwell/cooldown/crossfade/volume are settings (T6); "limit for the cooldown" is the 0–600 s range.
- **Placeholders:** none; Steps 4–5 of T7 and Step 1 of T8 are explicit API-verification steps with the exact file to read, because those Minecraft 26.3 names can only be confirmed by the compiler.
- **Types:** `Want`, `Track`, `Mood`, `Source`, `SourceMode.pool(List<Track>, Want)`/`apply(Want)`, `SwitchGate<Want>.update/force/reset/current`, `Mixer.play/stop/setPaused/setVolume/onEnd/render/positionSeconds/remainingSeconds/playing`, `MusicService.*` are used with the same names and signatures in every task.
