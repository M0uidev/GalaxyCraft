package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EditCodecTest {
    static EditCodec.Msg msg(int[] cells, int[] ids) {
        return new EditCodec.Msg(3, 17, 5, List.of(new EditCodec.Def(1, "minecraft:stone"), new EditCodec.Def(2, "minecraft:oak_log[axis=y]")), cells, ids);
    }

    @Test
    void roundTripsAndSortsByCell() throws Exception {
        var out = EditCodec.decode(EditCodec.encode(msg(new int[] {900, 5, 70000}, new int[] {1, 2, 1})));
        assertEquals(3, out.planet());
        assertEquals(17, out.seq());
        assertEquals(5, out.origin());
        assertArrayEquals(new int[] {5, 900, 70000}, out.cells());
        assertArrayEquals(new int[] {2, 1, 1}, out.ids());
        assertEquals("minecraft:oak_log[axis=y]", out.defs().get(1).name());
    }

    @Test
    void aRepeatedCellKeepsTheLastValue() throws Exception {
        var out = EditCodec.decode(EditCodec.encode(msg(new int[] {10, 10}, new int[] {1, 2})));
        assertArrayEquals(new int[] {10}, out.cells());
        assertArrayEquals(new int[] {2}, out.ids());
    }

    @Test
    void aSingleBreakIsSmall() {
        assertTrue(EditCodec.encode(new EditCodec.Msg(0, 1, 0, List.of(), new int[] {123456}, new int[] {0})).length < 16);
    }

    @Test
    void bigBatchesAreDeflated() throws Exception {
        int n = 2000;
        int[] cells = new int[n], ids = new int[n];
        for (int i = 0; i < n; i++) {
            cells[i] = 1000 + i;
            ids[i] = 1;
        }
        byte[] wire = EditCodec.encode(msg(cells, ids));
        assertTrue(wire.length < n, "deflated: " + wire.length);
        assertEquals(n, EditCodec.decode(wire).cells().length);
    }

    @Test
    void garbageAndTruncationAreRefused() {
        Random r = new Random(7);
        for (int i = 0; i < 300; i++) {
            byte[] junk = new byte[r.nextInt(80)];
            r.nextBytes(junk);
            try {
                EditCodec.decode(junk); // may happen to be valid; must never throw anything else
            } catch (EditCodec.Bad expected) {
            }
        }
        byte[] ok = EditCodec.encode(msg(new int[] {1, 2, 3}, new int[] {1, 1, 2}));
        for (int cut = 0; cut < ok.length; cut++) {
            byte[] part = java.util.Arrays.copyOf(ok, cut);
            assertThrows(EditCodec.Bad.class, () -> EditCodec.decode(part), "cut at " + cut);
        }
        assertThrows(EditCodec.Bad.class, () -> EditCodec.decode(new byte[EditCodec.MAX_WIRE + 1]));
    }

    @Test
    void boundsAreEnforced() {
        int[] cells = new int[EditCodec.MAX_ENTRIES + 1], ids = new int[EditCodec.MAX_ENTRIES + 1];
        for (int i = 0; i < cells.length; i++) cells[i] = i;
        assertThrows(IllegalArgumentException.class, () -> EditCodec.encode(msg(cells, ids)));
        assertThrows(IllegalArgumentException.class, () -> EditCodec.encode(new EditCodec.Msg(0, 1, 0,
                List.of(new EditCodec.Def(1, "x".repeat(EditCodec.MAX_NAME + 1))), new int[] {1}, new int[] {1})));
        assertThrows(IllegalArgumentException.class, () -> EditCodec.encode(new EditCodec.Msg(0, 1, 0,
                List.of(new EditCodec.Def(EditCodec.MAX_WIRE_IDS, "a")), new int[] {1}, new int[] {1})));
    }

    @Test
    void aBombThatInflatesHugeIsRefused() throws Exception {
        var o = new java.io.ByteArrayOutputStream();
        o.write(1);
        var d = new java.util.zip.DeflaterOutputStream(o);
        d.write(new byte[EditCodec.MAX_INFLATED + 1000]);
        d.close();
        assertThrows(EditCodec.Bad.class, () -> EditCodec.decode(o.toByteArray()));
    }
}
