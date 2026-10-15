package dev.moui.galaxycraft.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The host's stations as a guest is told of them (the body of a StationList message): for each one its slot,
 * id, name, where it is and how it is turned. A <em>full</em> list replaces everything the guest knew; a
 * delta changes only the stations in it, and a station with no stage (null) is packed or gone. Pure; every
 * size is bounded on read.
 */
public final class StationList {
    public static final int MAX_HEADS = 256, MAX_TEXT = 64, MAX_WIRE = 64 * 1024;

    private StationList() {}

    /** stage: null when the station is packed. Center in universe units, rotation a quaternion (x, y, z, w). */
    public record Head(int slot, String id, String name, String stage, double cx, double cy, double cz, double rx, double ry, double rz,
            double rw, int spanX, int spanY, int spanZ) {}

    public record Msg(boolean full, List<Head> heads) {}

    public static byte[] encode(Msg m) {
        if (m.heads().size() > MAX_HEADS) throw new IllegalArgumentException("too many stations");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeBoolean(m.full());
            out.writeShort(m.heads().size());
            for (Head h : m.heads()) {
                out.writeInt(h.slot());
                text(out, h.id());
                text(out, h.name());
                out.writeBoolean(h.stage() != null);
                if (h.stage() != null) text(out, h.stage());
                for (double v : new double[] {h.cx(), h.cy(), h.cz(), h.rx(), h.ry(), h.rz(), h.rw()}) out.writeDouble(v);
                out.writeInt(h.spanX());
                out.writeInt(h.spanY());
                out.writeInt(h.spanZ());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return raw.toByteArray();
    }

    private static void text(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length > MAX_TEXT) throw new IllegalArgumentException("text too long");
        out.writeByte(b.length);
        out.write(b);
    }

    private static String text(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        if (n > MAX_TEXT) throw new IOException("text");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Null when the bytes are not a list (a bad peer): the caller ignores them. */
    public static Msg decode(byte[] wire) {
        if (wire.length > MAX_WIRE) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire))) {
            boolean full = in.readBoolean();
            int n = in.readUnsignedShort();
            if (n > MAX_HEADS) return null;
            List<Head> heads = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int slot = in.readInt();
                if (!StationSlots.isStation(slot)) return null;
                String id = text(in), name = text(in);
                String stage = in.readBoolean() ? text(in) : null;
                double[] d = new double[7];
                for (int k = 0; k < 7; k++) {
                    d[k] = in.readDouble();
                    if (!Double.isFinite(d[k]) || Math.abs(d[k]) > Protocol.MAX_COORD) return null;
                }
                int sx = in.readInt(), sy = in.readInt(), sz = in.readInt();
                heads.add(new Head(slot, id, name, stage, d[0], d[1], d[2], d[3], d[4], d[5], d[6], sx, sy, sz));
            }
            if (in.available() != 0) return null;
            return new Msg(full, heads);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * What a guest knows, after a message: a full list replaces it; a delta changes the stations in it (a packed
     * or gone one, stage null, is removed). Returns the new map, in slot order.
     */
    public static java.util.Map<Integer, Head> merge(java.util.Map<Integer, Head> known, Msg m) {
        java.util.Map<Integer, Head> out = new java.util.TreeMap<>(m.full() ? java.util.Map.of() : known);
        for (Head h : m.heads()) {
            if (h.stage() == null) out.remove(h.slot());
            else out.put(h.slot(), h);
        }
        return out;
    }

    /** What changed from the list last sent to the one now: heads that are new or different, and the packed/gone as stage null. */
    public static List<Head> diff(java.util.Map<Integer, Head> before, java.util.Map<Integer, Head> now) {
        List<Head> out = new ArrayList<>();
        for (Head h : now.values()) if (!h.equals(before.get(h.slot()))) out.add(h);
        for (Head h : before.values())
            if (!now.containsKey(h.slot()))
                out.add(new Head(h.slot(), h.id(), h.name(), null, h.cx(), h.cy(), h.cz(), h.rx(), h.ry(), h.rz(), h.rw(), h.spanX(), h.spanY(), h.spanZ()));
        return out;
    }
}
