package dev.moui.galaxycraft.kcl;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.geom.Tri;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class KclParserTest {
    static byte[] icosphere() throws Exception {
        try (InputStream in = KclParserTest.class.getResourceAsStream("/icosphere.kcl")) {
            return in.readAllBytes();
        }
    }

    @Test void parsesIcosphere() throws Exception {
        List<Tri> tris = KclParser.parse(icosphere());
        assertEquals(320, tris.size());
        for (Tri t : tris) {
            for (Vector3d v : List.of(t.a(), t.b(), t.c())) assertEquals(800, v.length(), 0.05);
            assertTrue(t.n().dot(t.a()) > 0, "normal points outwards");
            assertEquals(1.0, t.n().length(), 1e-6);
        }
    }

    @Test void truncatedThrows() throws Exception {
        byte[] data = Arrays.copyOf(icosphere(), 40);
        assertThrows(IllegalArgumentException.class, () -> KclParser.parse(data));
    }

    @Test void offsetsPastEndThrow() throws Exception {
        byte[] data = icosphere();
        ByteBuffer.wrap(data).putInt(12, data.length + 100); // octree offset beyond the file
        assertThrows(IllegalArgumentException.class, () -> KclParser.parse(data));
    }

    @Test void degeneratePrismDropped() {
        // One position, four normals where CB is perpendicular to EC: CB·EC = 0.
        ByteBuffer b = ByteBuffer.allocate(0x38 + 12 + 48 + 16 + 8);
        int pos = 0x38, nrm = pos + 12, prism = nrm + 48, oct = prism + 16;
        b.putInt(pos).putInt(nrm).putInt(prism - 0x10).putInt(oct);
        b.position(pos);
        b.putFloat(0).putFloat(0).putFloat(0);                     // v1
        b.putFloat(0).putFloat(1).putFloat(0);                     // face normal +Y
        b.putFloat(1).putFloat(0).putFloat(0);                     // EA
        b.putFloat(1).putFloat(0).putFloat(0);                     // EB -> CB = EB×F = (0,0,1)
        b.putFloat(1).putFloat(0).putFloat(0);                     // EC: CB·EC = 0
        b.putFloat(100).putShort((short) 0).putShort((short) 0)
                .putShort((short) 1).putShort((short) 2).putShort((short) 3).putShort((short) 0);
        assertEquals(0, KclParser.parse(b.array()).size());
    }
}
