package dev.moui.galaxycraft.ship.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BdlReaderTest {
    @Test
    void readsOneTriangleWithItsUvsAndTexture() {
        BdlReader.Model m = BdlReader.read(Fixture.oneTriangle());
        assertEquals(1, m.shapes().size());
        BdlReader.Shape s = m.shapes().get(0);
        assertEquals(0, s.texture());
        assertEquals(1, s.tris().size());
        assertArrayEquals(new float[] {10, 0, 0}, s.tris().get(0).pos()[1]);
        assertArrayEquals(new float[] {1, 0}, s.tris().get(0).uv()[1]);
    }

    @Test
    void aJointsTransformMovesItsShape() {
        BdlReader.Model m = BdlReader.read(Fixture.oneTriangleOnAJointMovedBy(100, 0, 0));
        assertArrayEquals(new float[] {100, 0, 0}, m.shapes().get(0).tris().get(0).pos()[0], 1e-4f);
        assertArrayEquals(new float[] {110, 0, 0}, m.shapes().get(0).tris().get(0).pos()[1], 1e-4f);
    }

    @Test
    void decodesAnRgb565Texture() {
        BdlReader.Model m = BdlReader.read(Fixture.oneTriangle());
        assertEquals(1, m.textures().size());
        BdlReader.Texture t = m.textures().get(0);
        assertEquals(4, t.width());
        assertEquals(4, t.height());
        assertEquals(0xFFFF0000, t.argb()[0], "pure red");
    }

    @Test
    void rejectsAFileThatIsNotABdl() {
        assertThrows(IllegalArgumentException.class, () -> BdlReader.read(new byte[64]));
    }

    @Test
    void aTruncatedFileIsRejectedNotACrash() {
        byte[] whole = Fixture.oneTriangle();
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length / 2);
        assertThrows(IllegalArgumentException.class, () -> BdlReader.read(cut));
    }

    @Test
    void rarcReturnsFilesByName() {
        Map<String, byte[]> f = Rarc.read(Fixture.rarcWith("a.bdl", new byte[] {1, 2, 3}));
        assertArrayEquals(new byte[] {1, 2, 3}, f.get("a.bdl"));
    }

    @Test
    void aFileThatIsNotAnArchiveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Rarc.read(new byte[64]));
        assertThrows(IllegalArgumentException.class, () -> Rarc.read("Yaz0".getBytes()));
    }

    /** Hand-built files in the layouts the readers document (mirror of the real ones). */
    static final class Fixture {
        private Fixture() {}

        static byte[] oneTriangle() {
            return bdl(0, 0, 0, false);
        }

        static byte[] oneTriangleOnAJointMovedBy(float x, float y, float z) {
            return bdl(x, y, z, true);
        }

        private static byte[] bdl(float tx, float ty, float tz, boolean unused) {
            ByteBuffer inf = chunk("INF1", 0x40);
            inf.putShort(8, (short) 0).putInt(0x0C, 1).putInt(0x10, 3).putInt(0x14, 0x18);
            int[][] nodes = {{0x10, 0}, {1, 0}, {0x11, 0}, {1, 0}, {0x12, 0}, {2, 0}, {2, 0}, {0, 0}};
            for (int i = 0; i < nodes.length; i++) inf.putShort(0x18 + 4 * i, (short) nodes[i][0]).putShort(0x1A + 4 * i, (short) nodes[i][1]);

            ByteBuffer vtx = chunk("VTX1", 0xC0);
            vtx.putInt(8, 0x40);
            vtx.putInt(0x0C, 0x70);          // positions
            vtx.putInt(0x0C + 5 * 4, 0x94);  // TEX0
            int f = 0x40;
            vtx.putInt(f, 9).putInt(f + 4, 1).putInt(f + 8, 4).put(f + 12, (byte) 0);
            vtx.putInt(f + 16, 13).putInt(f + 20, 1).putInt(f + 24, 4).put(f + 28, (byte) 0);
            vtx.putInt(f + 32, 0xFF);
            float[] p = {0, 0, 0, 10, 0, 0, 0, 10, 0};
            for (int i = 0; i < 9; i++) vtx.putFloat(0x70 + 4 * i, p[i]);
            float[] uv = {0, 0, 1, 0, 0, 1};
            for (int i = 0; i < 6; i++) vtx.putFloat(0x94 + 4 * i, uv[i]);

            ByteBuffer drw = chunk("DRW1", 0x20);
            drw.putShort(8, (short) 1).putInt(0x0C, 0x14).putInt(0x10, 0x18);
            drw.put(0x14, (byte) 0).putShort(0x18, (short) 0);

            ByteBuffer jnt = chunk("JNT1", 0x60);
            jnt.putShort(8, (short) 1).putInt(0x0C, 0x18);
            int j = 0x18;
            jnt.putFloat(j + 4, 1).putFloat(j + 8, 1).putFloat(j + 12, 1);
            jnt.putFloat(j + 0x18, tx).putFloat(j + 0x1C, ty).putFloat(j + 0x20, tz);

            ByteBuffer shp = chunk("SHP1", 0xA0);
            shp.putShort(8, (short) 1);
            shp.putInt(0x0C, 0x2C).putInt(0x10, 0).putInt(0x14, 0).putInt(0x18, 0x54).putInt(0x1C, 0x6C)
                    .putInt(0x20, 0x80).putInt(0x24, 0x70).putInt(0x28, 0x78);
            int e = 0x2C; // shape init: type, groups, desc index, first matrix data, first packet
            shp.put(e, (byte) 0).putShort(e + 2, (short) 1).putShort(e + 4, (short) 0).putShort(e + 6, (short) 0).putShort(e + 8, (short) 0);
            shp.putInt(0x54, 9).putInt(0x58, 2).putInt(0x5C, 13).putInt(0x60, 2).putInt(0x64, 0xFF).putInt(0x68, 0);
            shp.putShort(0x6C, (short) 0);                                   // matrix table: drw 0
            shp.putShort(0x70, (short) 0).putShort(0x72, (short) 1).putInt(0x74, 0); // matrix data
            shp.putInt(0x78, 10).putInt(0x7C, 0);                            // packet: size, offset
            int d = 0x80;
            shp.put(d, (byte) 0x90).putShort(d + 1, (short) 3);
            for (int i = 0; i < 3; i++) shp.put(d + 3 + 2 * i, (byte) i).put(d + 4 + 2 * i, (byte) i);
            shp.put(d + 9, (byte) 0);

            ByteBuffer mat = chunk("MAT3", 0x1E0);
            mat.putShort(8, (short) 1);
            mat.putInt(0x0C, 0x84).putInt(0x10, 0x1D0);        // init, remap
            mat.putInt(0x0C + 15 * 4, 0x1D4);                  // texture index table
            mat.putShort(0x84 + 0x84, (short) 0);              // material 0: texture slot 0 -> table[0]
            for (int k = 1; k < 8; k++) mat.putShort(0x84 + 0x84 + 2 * k, (short) -1);
            mat.putShort(0x1D0, (short) 0).putShort(0x1D4, (short) 0);

            ByteBuffer tex = chunk("TEX1", 0x60);
            tex.putShort(8, (short) 1).putInt(0x0C, 0x20);
            tex.put(0x20, (byte) 4).put(0x21, (byte) 0).putShort(0x22, (short) 4).putShort(0x24, (short) 4).putInt(0x3C, 0x20);
            for (int i = 0; i < 16; i++) tex.putShort(0x40 + 2 * i, (short) 0xF800);

            ByteBuffer[] cs = {inf, vtx, drw, jnt, shp, mat, tex};
            int total = 0x20;
            for (ByteBuffer c : cs) total += c.capacity();
            ByteBuffer out = ByteBuffer.allocate(total);
            out.put("J3D2bdl4".getBytes()).putInt(total).putInt(cs.length);
            out.position(0x20);
            for (ByteBuffer c : cs) out.put(c.array());
            return out.array();
        }

        private static ByteBuffer chunk(String tag, int size) {
            ByteBuffer b = ByteBuffer.allocate(size);
            b.put(tag.getBytes()).putInt(size);
            return b;
        }

        static byte[] rarcWith(String name, byte[] data) {
            // header 0x20, info 0x20..0x40, nodes at 0x40 (1 x 16), entries at 0x50 (3 x 20), strings, data.
            int nodes = 0x40, ents = 0x50, strings = 0x50 + 3 * 20, dataAt = (strings + 32 + 31) & ~31;
            ByteBuffer b = ByteBuffer.allocate(dataAt + ((data.length + 31) & ~31));
            b.put("RARC".getBytes()).putInt(b.capacity()).putInt(0x20).putInt(dataAt - 0x20);
            b.putInt(0x20, 1).putInt(0x24, nodes - 0x20).putInt(0x28, 3).putInt(0x2C, ents - 0x20)
                    .putInt(0x30, 32).putInt(0x34, strings - 0x20);
            b.put(nodes, "ROOT".getBytes()).putInt(nodes + 4, 0).putShort(nodes + 8, (short) 0).putShort(nodes + 10, (short) 3).putInt(nodes + 12, 0);
            String[] names = {name, ".", ".."};
            int sp = 0;
            for (int i = 0; i < 3; i++) {
                int at = ents + i * 20;
                boolean file = i == 0;
                b.putShort(at, (short) (file ? 0 : 0xFFFF)).putShort(at + 2, (short) 0)
                        .putShort(at + 4, (short) (file ? 0x1100 : 0x0200)).putShort(at + 6, (short) sp);
                b.putInt(at + 8, 0).putInt(at + 12, file ? data.length : 16);
                b.position(strings + sp);
                b.put(names[i].getBytes()).put((byte) 0);
                sp += names[i].length() + 1;
            }
            b.position(dataAt);
            b.put(data);
            return b.array();
        }
    }
}
