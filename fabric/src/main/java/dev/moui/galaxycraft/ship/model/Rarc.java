package dev.moui.galaxycraft.ship.model;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Reads the files out of an uncompressed RARC archive (the launcher decompresses the disc's Yaz0). */
public final class Rarc {
    private Rarc() {}

    /** File name -> contents, every folder flattened (names in SMG2's model archives are unique). */
    public static Map<String, byte[]> read(byte[] arc) {
        try {
            if (arc.length < 0x40 || arc[0] != 'R' || arc[1] != 'A' || arc[2] != 'R' || arc[3] != 'C')
                throw new IllegalArgumentException("not a RARC archive");
            ByteBuffer b = ByteBuffer.wrap(arc);
            int data = 0x20 + b.getInt(0x0C);
            int entryCount = b.getInt(0x28), ents = 0x20 + b.getInt(0x2C), strings = 0x20 + b.getInt(0x34);
            Map<String, byte[]> out = new HashMap<>();
            for (int i = 0; i < entryCount; i++) {
                int at = ents + i * 20;
                int flags = b.getShort(at + 4) & 0xFFFF;
                if ((flags & 0x0200) != 0) continue; // a folder
                int end = strings + (b.getShort(at + 6) & 0xFFFF);
                int start = end;
                while (arc[end] != 0) end++;
                String name = new String(arc, start, end - start, java.nio.charset.StandardCharsets.US_ASCII);
                int off = b.getInt(at + 8), size = b.getInt(at + 12);
                if (name.equals(".") || name.equals("..")) continue;
                byte[] file = new byte[size];
                System.arraycopy(arc, data + off, file, 0, size);
                out.put(name, file);
            }
            return out;
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("damaged RARC archive", e);
        }
    }
}
