package dev.moui.galaxycraft.net;

import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.ShipOps;
import dev.moui.galaxycraft.ship.ShipRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Ships and landing pads as bytes for ShipPut and ShipList. Every size is bounded on read. */
public final class ShipCodec {
    /** The most bytes a list of ships may take. */
    public static final int MAX_LIST = 65_536;

    /** The bytes are not ships. */
    public static final class Bad extends Exception {
        public Bad(String why) {
            super(why);
        }
    }

    private ShipCodec() {}

    private static void utf(DataOutputStream o, String s) throws IOException {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        o.writeShort(raw.length);
        o.write(raw);
    }

    private static String utf(DataInputStream in) throws IOException, Bad {
        int n = in.readUnsignedShort();
        if (n > ShipOps.MAX_NAME * 4 || n > in.available()) throw new Bad("text of " + n + " bytes");
        byte[] raw = new byte[n];
        in.readFully(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static void put(DataOutputStream o, ShipRecord r) throws IOException {
        utf(o, r.id());
        o.writeByte(r.modules().hull());
        o.writeByte(r.modules().engine());
        o.writeByte(r.modules().rudder());
        o.writeByte(r.modules().cabin());
        for (double d : r.pos()) o.writeDouble(d);
        for (double d : r.rot()) o.writeDouble(d);
        utf(o, r.stage());
    }

    private static ShipRecord get(DataInputStream in) throws IOException, Bad {
        String id = utf(in);
        ShipModules m = new ShipModules(in.readByte(), in.readByte(), in.readByte(), in.readByte());
        if (!m.valid()) throw new Bad("modules");
        double[] pos = new double[3], rot = new double[4];
        for (int i = 0; i < 3; i++) pos[i] = in.readDouble();
        for (int i = 0; i < 4; i++) rot[i] = in.readDouble();
        for (double d : pos) if (!Double.isFinite(d)) throw new Bad("position");
        for (double d : rot) if (!Double.isFinite(d)) throw new Bad("rotation");
        return new ShipRecord(id, m, pos, rot, utf(in));
    }

    public static byte[] encode(ShipRecord r) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            put(new DataOutputStream(bytes), r);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ShipRecord decode(byte[] raw) throws Bad {
        if (raw.length > MAX_LIST) throw new Bad("too long");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            ShipRecord r = get(in);
            if (in.available() > 0) throw new Bad("extra bytes");
            return r;
        } catch (IOException e) {
            throw new Bad("short");
        }
    }

    public static byte[] encodeList(List<ShipRecord> all) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bytes);
            int n = Math.min(all.size(), ShipOps.MAX_SHIPS);
            o.writeShort(n);
            for (int i = 0; i < n; i++) put(o, all.get(i));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<ShipRecord> decodeList(byte[] raw) throws Bad {
        if (raw.length > MAX_LIST) throw new Bad("too long");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            int n = in.readUnsignedShort();
            if (n > ShipOps.MAX_SHIPS) throw new Bad(n + " ships");
            List<ShipRecord> out = new ArrayList<>();
            for (int i = 0; i < n; i++) out.add(get(in));
            if (in.available() > 0) throw new Bad("extra bytes");
            return out;
        } catch (IOException e) {
            throw new Bad("short");
        }
    }
}
