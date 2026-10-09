package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MixerTest {
    /** A constant tone: value on both channels for length frames (-1: endless). */
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
        Mixer m = new Mixer();
        float[] out = render(m, 64);
        for (float v : out) assertEquals(0f, v);
        assertFalse(m.playing());
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
