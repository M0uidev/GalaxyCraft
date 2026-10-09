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
