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
