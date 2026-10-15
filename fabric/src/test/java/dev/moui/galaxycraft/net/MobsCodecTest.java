package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MobsCodecTest {
    private static MobsCodec.Mob mob(int id, byte[] data) {
        float[] f = new float[12];
        for (int i = 0; i < 12; i++) f[i] = id + i * 0.5f;
        return new MobsCodec.Mob(id, 7, f, 90f, -10f, 45f, 1.5f, 0.25f, data);
    }

    @Test
    void roundTrip() {
        byte[] body = MobsCodec.encode(3, List.of(mob(1, new byte[] {1, 2, 3}), mob(2, new byte[0])));
        MobsCodec.Body b = MobsCodec.decode(body);
        assertNotNull(b);
        assertEquals(3, b.planet());
        assertEquals(2, b.mobs().size());
        assertEquals(2, b.mobs().get(1).id());
        assertEquals(7, b.mobs().get(0).type());
        assertArrayEquals(new byte[] {1, 2, 3}, b.mobs().get(0).data());
        assertEquals(2.0f, b.mobs().get(0).frame()[2], 1e-6);
        assertEquals(0.25f, b.mobs().get(1).walkSpeed());
    }

    @Test
    void capsTheCount() {
        List<MobsCodec.Mob> many = new ArrayList<>();
        for (int i = 0; i < MobsCodec.MAX_MOBS + 20; i++) many.add(mob(i, new byte[0]));
        assertEquals(MobsCodec.MAX_MOBS, MobsCodec.decode(MobsCodec.encode(0, many)).mobs().size());
    }

    @Test
    void rejectsBadBodies() {
        byte[] ok = MobsCodec.encode(0, List.of(mob(1, new byte[] {9})));
        assertNull(MobsCodec.decode(java.util.Arrays.copyOf(ok, ok.length - 1))); // cut short
        assertNull(MobsCodec.decode(java.util.Arrays.copyOf(ok, ok.length + 1))); // trailing byte
        assertNull(MobsCodec.decode(new byte[0]));
        MobsCodec.Mob nan = mob(1, new byte[0]);
        nan.frame()[0] = Float.NaN;
        assertNull(MobsCodec.decode(MobsCodec.encode(0, List.of(nan))));
    }
}
