package dev.moui.galaxycraft.ship.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class BckReaderTest {
    /**
     * A one-joint BCK: duration 10 frames; the x scale constant 2, the y translation keyed (frame 0: 0,
     * frame 10: 10, tangents 0, one tangent per key), the z rotation keyed in shorts (0 to 16384 =
     * 90 degrees over the 10 frames, tangent mode 1: two tangents per key).
     */
    static byte[] sample() {
        ByteBuffer b = ByteBuffer.allocate(0x200);
        b.put(0, (byte) 'J').put(1, (byte) '3').put(2, (byte) 'D').put(3, (byte) '1');
        b.put(4, (byte) 'b').put(5, (byte) 'c').put(6, (byte) 'k').put(7, (byte) '1');
        b.putInt(0x0C, 1);
        int a = 0x20; // ANK1
        b.put(a, (byte) 'A').put(a + 1, (byte) 'N').put(a + 2, (byte) 'K').put(a + 3, (byte) '1');
        b.putInt(a + 4, 0x1E0);
        b.put(a + 8, (byte) 2); // loop
        b.put(a + 9, (byte) 0); // angle multiplier
        b.putShort(a + 0x0A, (short) 10);
        b.putShort(a + 0x0C, (short) 1);
        b.putShort(a + 0x0E, (short) 1); // scales
        b.putShort(a + 0x10, (short) 9); // rotations: 1 + 8 shorts
        b.putShort(a + 0x12, (short) 6); // translations: 6 floats
        int joints = 0x60, scales = 0x100, rots = 0x120, trans = 0x160;
        b.putInt(a + 0x14, joints).putInt(a + 0x18, scales).putInt(a + 0x1C, rots).putInt(a + 0x20, trans);
        // joint 0: 9 components (count, index, tangent mode): sx rx tx sy ry ty sz rz tz
        int j = a + joints;
        short[][] comp = {
            {1, 0, 0}, {1, 0, 0}, {1, 0, 0}, // sx const (scales[0]=2), rx const rots[0]=0, tx const trans[0]
            {1, 0, 0}, {1, 0, 0}, {2, 0, 0}, // sy const, ry const, ty keyed from trans[0]: 2 keys, mode 0
            {1, 0, 0}, {2, 1, 1}, {1, 0, 0}}; // sz const, rz keyed from rots[1]: 2 keys, mode 1
        for (int i = 0; i < 9; i++) {
            b.putShort(j + i * 6, comp[i][0]).putShort(j + i * 6 + 2, comp[i][1]).putShort(j + i * 6 + 4, comp[i][2]);
        }
        b.putFloat(a + scales, 2f);
        b.putShort(a + rots, (short) 0);
        // rz keys: (time 0, value 0, in 0, out 0), (time 10, value 16384, in 0, out 0)
        short[] rz = {0, 0, 0, 0, 10, 16384, 0, 0};
        for (int i = 0; i < rz.length; i++) b.putShort(a + rots + 2 + i * 2, rz[i]);
        // ty keys (mode 0: time, value, tangent): (0, 0, 0), (10, 10, 0)
        float[] ty = {0, 0, 0, 10, 10, 0};
        for (int i = 0; i < ty.length; i++) b.putFloat(a + trans + i * 4, ty[i]);
        // tx const trans[0] = 0 is the first key's time (0): fine for the constant.
        return b.array();
    }

    @Test void constantsAndKeysAreSampled() {
        BckReader.Anim anim = BckReader.read(sample());
        assertEquals(10, anim.duration());
        assertEquals(1, anim.joints());
        float[] o = new float[9];
        anim.sample(0, 0, o);
        assertEquals(2f, o[0], 1e-6, "x scale is constant 2");
        assertEquals(0f, o[7], 1e-6, "y translation at frame 0");
        assertEquals(0f, o[5], 1e-6, "z rotation at frame 0");
        anim.sample(0, 5, o);
        assertEquals(5f, o[7], 1e-5, "y translation halfway (zero tangents)");
        assertEquals(Math.PI / 4, o[5], 1e-5, "z rotation halfway is 45 degrees");
        anim.sample(0, 9.9999f, o);
        assertEquals(10f, o[7], 1e-3, "just before the end");
        assertEquals(Math.PI / 2, o[5], 1e-3, "16384 shorts are 90 degrees");
        anim.sample(0, 10, o);
        assertEquals(0f, o[7], 1e-5, "a looping animation is back at its start at its duration");
    }

    @Test void aLoopingAnimationWraps() {
        BckReader.Anim anim = BckReader.read(sample());
        float[] a = new float[9], b = new float[9];
        anim.sample(0, 5, a);
        anim.sample(0, 15, b);
        assertArrayEquals(a, b, 1e-5f);
    }

    @Test void notABckIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BckReader.read(new byte[64]));
    }
}
