package dev.moui.galaxycraft.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What changed among the host shadow's live blocks (a sign's text, a banner, a head, a shelf, a lid) on a planet a
 * guest holds, as one message body: the cells that are new or changed (block entity kind, how open its lid is,
 * its saved data as opaque bytes) and the cells that are no longer live. {@code full}: the guest first forgets
 * every live block of that planet (it just came, or came back). Pure, so it is tested without Minecraft. Every
 * size is bounded on read.
 */
public final class LiveCodec {
    public static final int MAX_ENTRIES = 48;
    public static final int MAX_REMOVED = 512;
    public static final int MAX_DATA = 6 * 1024;
    /** A whole body at most. */
    public static final int MAX_WIRE = 160 * 1024;

    /** Lid state: not a lid, closing (or shut), opening (or open). */
    public static final int LID_NONE = 0, LID_CLOSING = 1, LID_OPENING = 2;

    private LiveCodec() {}

    /** lid: a LID_ value; open: how far the lid is, 0 to 255; data: the block entity's saved data (empty for a lid). */
    public record Entry(int cell, int type, int lid, int open, byte[] data) {}

    public record Body(int planet, boolean full, List<Entry> upserts, int[] removed) {}

    public static byte[] encode(int planet, boolean full, List<Entry> upserts, int[] removed) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(bo)) {
            o.writeShort(planet);
            o.writeBoolean(full);
            int n = Math.min(upserts.size(), MAX_ENTRIES);
            o.writeByte(n);
            for (int i = 0; i < n; i++) {
                Entry e = upserts.get(i);
                o.writeInt(e.cell);
                o.writeShort(e.type);
                o.writeByte(e.lid);
                o.writeByte(e.open);
                int len = Math.min(e.data.length, MAX_DATA);
                o.writeShort(len);
                o.write(e.data, 0, len);
            }
            int r = Math.min(removed.length, MAX_REMOVED);
            o.writeShort(r);
            for (int i = 0; i < r; i++) o.writeInt(removed[i]);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bo.toByteArray();
    }

    /** Null if the bytes are not a well formed body. */
    public static Body decode(byte[] bytes) {
        if (bytes.length > MAX_WIRE) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int planet = in.readUnsignedShort();
            boolean full = in.readBoolean();
            int n = in.readUnsignedByte();
            if (n > MAX_ENTRIES) return null;
            List<Entry> ups = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int cell = in.readInt(), type = in.readUnsignedShort(), lid = in.readUnsignedByte(), open = in.readUnsignedByte();
                int len = in.readUnsignedShort();
                if (cell < 0 || lid > LID_OPENING || len > MAX_DATA || len > in.available()) return null;
                byte[] d = new byte[len];
                in.readFully(d);
                ups.add(new Entry(cell, type, lid, open, d));
            }
            int r = in.readUnsignedShort();
            if (r > MAX_REMOVED || r * 4 > in.available()) return null;
            int[] removed = new int[r];
            for (int i = 0; i < r; i++) {
                removed[i] = in.readInt();
                if (removed[i] < 0) return null;
            }
            return in.available() == 0 ? new Body(planet, full, ups, removed) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
