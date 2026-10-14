package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class SkinCodecTest {
    static int[] skin(long seed) {
        int[] px = new int[SkinCodec.PIXELS];
        Random r = new Random(seed);
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | r.nextInt(1 << 24);
        return px;
    }

    @Test
    void roundTrips() {
        int[] px = skin(1);
        assertArrayEquals(px, SkinCodec.decode(SkinCodec.encode(px)));
    }

    @Test
    void aSkinOfPureNoiseStillFitsTheWire() {
        // The worst case for deflate: random pixels. Must stay under the limit or no skin could be shared.
        assertTrue(SkinCodec.encode(skin(2)).length <= Protocol.SKIN_MAX);
    }

    @Test
    void aFlatSkinIsTiny() {
        assertTrue(SkinCodec.encode(new int[SkinCodec.PIXELS]).length < 200);
    }

    @Test
    void garbageEmptyAndNullAreNotSkins() {
        assertNull(SkinCodec.decode(null));
        assertNull(SkinCodec.decode(new byte[0]));
        assertNull(SkinCodec.decode(new byte[] {1, 2, 3, 4, 5}));
        byte[] good = SkinCodec.encode(skin(3));
        assertNull(SkinCodec.decode(java.util.Arrays.copyOf(good, good.length / 2)), "truncated");
    }

    @Test
    void trailingBytesAreRejected() {
        byte[] good = SkinCodec.encode(skin(4));
        byte[] more = java.util.Arrays.copyOf(good, good.length + 1);
        assertNull(SkinCodec.decode(more));
    }

    @Test
    void aStreamOfTheWrongSizeIsRejectedAndABombIsNotExpanded() {
        for (int size : new int[] {SkinCodec.RAW_BYTES - 1, SkinCodec.RAW_BYTES + 1, 50_000_000}) {
            Deflater d = new Deflater();
            d.setInput(new byte[size]);
            d.finish();
            byte[] buf = new byte[100_000];
            int n = d.deflate(buf);
            while (!d.finished() && n < buf.length) n += d.deflate(buf, n, buf.length - n);
            assertNull(SkinCodec.decode(java.util.Arrays.copyOf(buf, n)), "size " + size);
        }
    }

    @Test
    void clearIsAcceptableOnItsOwn() {
        assertTrue(SkinCodec.isClear(new byte[0]));
        assertTrue(SkinCodec.acceptable(new byte[0]));
        assertFalse(SkinCodec.isClear(null));
        assertFalse(SkinCodec.acceptable(null));
        assertTrue(SkinCodec.acceptable(SkinCodec.encode(skin(5))));
        assertFalse(SkinCodec.acceptable(new byte[] {9, 9}));
    }
}
