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
    private volatile int applied;

    /** How many play calls the audio side has applied: playing() and the position follow once it catches up with the calls made. */
    public int playsApplied() {
        return applied;
    }

    public void play(PcmSource source, double fadeSeconds) {
        commands.add(() -> {
            for (Deck d : decks) d.fadeOut(fadeSeconds);
            current = new Deck(source, fadeSeconds);
            decks.add(current);
            endSent = false;
            applied++;
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
            for (Deck d : decks) {
                try {
                    d.mixInto(out, frames, volume);
                } catch (RuntimeException e) { // a file gone under a song, a corrupt ogg: drop that song, play on
                    if (!d.failed) System.err.println("GalaxyCraft: a song stopped: " + e);
                    d.failed = true;
                }
            }
            for (int i = decks.size() - 1; i >= 0; i--) {
                Deck d = decks.get(i);
                if (d.finished() && d != current) {
                    d.close();
                    decks.remove(i);
                }
            }
            if (current != null && current.ended() && !endSent) {
                endSent = true;
                current.close();
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
        boolean failed;

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
            return failed || srcDone && pos >= len;
        }

        boolean finished() {
            return (du < 0 && u <= 0) || ended();
        }

        void close() {
            try {
                src.close();
            } catch (RuntimeException ignored) {
            }
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
