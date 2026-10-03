package dev.moui.galaxycraft.kcl;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.geom.Tri;
import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class KclWriterTest {
    @Test void roundTripsThroughTheParser() {
        List<Tri> in = List.of(Tri.of(new Vector3d(0, 0, 0), new Vector3d(100, 0, 0), new Vector3d(0, 0, -100)),
                Tri.of(new Vector3d(10, 5, -3), new Vector3d(130, 40, 20), new Vector3d(-20, 80, -150)));
        List<Tri> out = KclParser.parse(KclWriter.write(in));
        assertEquals(2, out.size());
        for (int i = 0; i < 2; i++) {
            assertEquals(0, in.get(i).a().distance(out.get(i).a()), 1e-3);
            assertEquals(0, in.get(i).b().distance(out.get(i).b()), 1e-3);
            assertEquals(0, in.get(i).c().distance(out.get(i).c()), 1e-3);
        }
    }

    @Test void octreeIsOneLeafTheGameCanWalk() {
        byte[] k = KclWriter.write(List.of(Tri.of(new Vector3d(0, 0, 0), new Vector3d(100, 0, 0), new Vector3d(0, 0, -100))));
        ByteBuffer b = ByteBuffer.wrap(k);
        int oct = b.getInt(12);
        assertEquals(40f, b.getFloat(16));
        assertEquals(0x80000002, b.getInt(oct));
        assertEquals(1, b.getShort(oct + 4 + 0), "list: node + 2 + 2 = right after the node");
        assertEquals(0, b.getShort(oct + 6));
        // One root block: the masks leave no bits below the area's size.
        int mask = b.getInt(32), shift = b.getInt(44);
        assertEquals(~((1 << shift) - 1), mask);
        assertTrue((1 << shift) >= 101);
        assertEquals(0, k.length % 4);
    }
}
