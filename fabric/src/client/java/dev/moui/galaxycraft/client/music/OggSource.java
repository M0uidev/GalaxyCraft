package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.PcmSource;
import it.unimi.dsi.fastutil.floats.FloatConsumer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
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
                queue = Arrays.copyOf(queue, queue.length * 2);
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
