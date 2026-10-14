package dev.moui.galaxycraft.net;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A custom skin on the wire: its 64x64 ARGB pixels, deflated. Decoding never trusts the input: too long
 * a stream, one that inflates to anything but exactly 64x64 pixels, or garbage, all give null. An empty
 * array means "no custom skin any more" ({@link #isClear}).
 */
public final class SkinCodec {
    public static final int SIZE = 64, PIXELS = SIZE * SIZE, RAW_BYTES = PIXELS * 4;

    private SkinCodec() {}

    public static byte[] encode(int[] argb) {
        if (argb.length != PIXELS) throw new IllegalArgumentException("a skin is " + SIZE + "x" + SIZE);
        ByteBuffer raw = ByteBuffer.allocate(RAW_BYTES);
        raw.asIntBuffer().put(argb);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw.array());
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    /** The pixels, or null if data is not a valid skin or is longer than a skin may be on the wire. */
    public static int[] decode(byte[] data) {
        if (data == null || data.length == 0 || data.length > Protocol.SKIN_MAX) return null;
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            byte[] raw = new byte[RAW_BYTES + 1]; // one more than a skin: a longer stream shows as a full buffer
            int n = 0;
            while (!inf.finished() && n < raw.length) {
                int got = inf.inflate(raw, n, raw.length - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) return null;
                n += got;
            }
            if (n != RAW_BYTES || !inf.finished() || inf.getRemaining() != 0) return null;
            int[] argb = new int[PIXELS];
            ByteBuffer.wrap(raw, 0, RAW_BYTES).asIntBuffer().get(argb);
            return argb;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    /** Whether this is the "no custom skin" message. */
    public static boolean isClear(byte[] data) {
        return data != null && data.length == 0;
    }

    /** What a server or client lets through: a valid skin, or the clear message. */
    public static boolean acceptable(byte[] data) {
        return isClear(data) || decode(data) != null;
    }
}
