package dev.moui.galaxycraft.proto;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class SkyMessageTest {
    private static float[] floats(byte[] msg) {
        ByteBuffer b = ByteBuffer.wrap(msg);
        float[] f = new float[b.remaining() / 4];
        for (int i = 0; i < f.length; i++) f[i] = b.getFloat();
        return f;
    }

    @Test void isThirtyTwoBytesWithTheLightThenTheFog() {
        float[] f = floats(SkyMessage.pack(new double[] {0.1, 0.2, 0.3}, new float[] {0.4f, 0.5f, 0.6f, 1f, 2f}, false));
        assertEquals(8, f.length);
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 1f, 2f}, f, 1e-6f);
    }

    @Test void noFogMeansZeros() {
        float[] f = floats(SkyMessage.pack(new double[] {1, 1, 1}, null, false));
        assertArrayEquals(new float[] {1, 1, 1, 0, 0, 0, 0, 0}, f);
    }

    @Test void aPanoramaCaptureAddsTwoToTheRedToHideTheGravityShells() {
        float[] f = floats(SkyMessage.pack(new double[] {0.25, 0.5, 0.75}, null, true));
        assertEquals(2.25f, f[0], 1e-6f);
        assertEquals(0.5f, f[1], 1e-6f);
        assertEquals(0.75f, f[2], 1e-6f);
    }

    @Test void theLightIsKeptInRangeSoTheFlagCannotBeFaked() {
        float[] f = floats(SkyMessage.pack(new double[] {3.0, -1.0, 0.5}, null, false));
        assertEquals(1f, f[0]);
        assertEquals(0f, f[1]);
    }
}
