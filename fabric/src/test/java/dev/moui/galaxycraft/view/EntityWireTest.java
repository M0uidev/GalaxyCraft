package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityWireTest {
    @Test
    void aModelIsQuadsInThePieceFormatPaddedTo32() {
        byte[] b = EntityWire.model(5, EntityWire.cube(3));
        ByteBuffer in = ByteBuffer.wrap(b);
        assertEquals(5, in.getInt());
        int size = in.getInt();
        assertEquals(0, size % 32);
        assertEquals(8 + size, b.length);
        assertEquals((byte) (0x80 | Layout.ENT_VTXFMT), in.get());
        assertEquals(24, in.getShort());
        assertEquals(-8 * 16, in.getShort(), "x of the first corner, 16ths of a pixel");
        assertNull(EntityWire.model(0, List.of()));
    }

    @Test
    void aFrameHoldsEachPieceIn56Bytes() {
        double[] m = new double[12];
        m[3] = 1.5;
        byte[] b = EntityWire.frame(List.of(new EntityWire.Piece(2, 3, 0xFF000066, m)));
        assertEquals(4 + Layout.ENT_BYTES, b.length);
        ByteBuffer in = ByteBuffer.wrap(b);
        assertEquals(1, in.getInt());
        assertEquals(2, in.getShort());
        assertEquals(3, in.getShort());
        assertEquals(0xFF000066, in.getInt());
        in.getFloat();
        in.getFloat();
        in.getFloat();
        assertEquals(1.5f, in.getFloat());
    }

    @Test
    void aSolidSpriteIsASlabWithFourWalls() {
        int[] argb = new int[256];
        Arrays.fill(argb, 0xFF00FF00);
        assertEquals(2 + 4, EntityWire.flatItem(argb, 1).size(), "a wall a side: inner edges hide");
        int[] dot = new int[256];
        dot[5 * 16 + 7] = 0xFFFFFFFF;
        assertEquals(2 + 4, EntityWire.flatItem(dot, 1).size());
    }

    @Test
    void skinsArePaddedToGxBlocks() {
        byte[] b = EntityWire.skin(1, 6, 4, new int[24]);
        ByteBuffer in = ByteBuffer.wrap(b);
        assertEquals(1, in.getInt());
        assertEquals(8, in.getInt());
        assertEquals(4, in.getInt());
        assertEquals(12 + 8 * 4 * 2, b.length);
    }
}
