package dev.moui.galaxycraft.net;

import dev.moui.galaxycraft.launchstar.StarOps;
import dev.moui.galaxycraft.launchstar.StarRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Launch Stars as bytes for StarPut and StarList. Every size is bounded on read. */
public final class StarCodec {
    /** The most bytes a list of stars may take. */
    public static final int MAX_LIST = 65_536;

    /** The bytes are not stars. */
    public static final class Bad extends Exception {
        public Bad(String why) {
            super(why);
        }
    }

    private StarCodec() {}

    private static void utf(DataOutputStream o, String s) throws IOException {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        o.writeShort(raw.length);
        o.write(raw);
    }

    private static String utf(DataInputStream in) throws IOException, Bad {
        int n = in.readUnsignedShort();
        if (n > StarOps.MAX_NAME * 4 || n > in.available()) throw new Bad("text of " + n + " bytes");
        byte[] raw = new byte[n];
        in.readFully(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static void put(DataOutputStream o, StarRecord r) throws IOException {
        utf(o, r.id());
        for (double d : r.pos()) o.writeDouble(d);
        for (double d : r.up()) o.writeDouble(d);
        utf(o, r.stage());
        utf(o, r.body());
        utf(o, r.code());
    }

    private static StarRecord get(DataInputStream in) throws IOException, Bad {
        String id = utf(in);
        double[] pos = new double[3], up = new double[3];
        for (int i = 0; i < 3; i++) pos[i] = in.readDouble();
        for (int i = 0; i < 3; i++) up[i] = in.readDouble();
        for (double d : pos) if (!Double.isFinite(d)) throw new Bad("position");
        for (double d : up) if (!Double.isFinite(d)) throw new Bad("up");
        String stage = utf(in), body = utf(in), code = utf(in);
        return new StarRecord(id, pos, up, stage, body, code);
    }

    public static byte[] encode(StarRecord r) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            put(new DataOutputStream(bytes), r);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static StarRecord decode(byte[] raw) throws Bad {
        if (raw.length > MAX_LIST) throw new Bad("too long");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            StarRecord r = get(in);
            if (in.available() > 0) throw new Bad("extra bytes");
            return r;
        } catch (IOException e) {
            throw new Bad("short");
        }
    }

    public static byte[] encodeList(List<StarRecord> all) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bytes);
            int n = Math.min(all.size(), StarOps.MAX_STARS);
            o.writeShort(n);
            for (int i = 0; i < n; i++) put(o, all.get(i));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<StarRecord> decodeList(byte[] raw) throws Bad {
        if (raw.length > MAX_LIST) throw new Bad("too long");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            int n = in.readUnsignedShort();
            if (n > StarOps.MAX_STARS) throw new Bad(n + " stars");
            List<StarRecord> out = new ArrayList<>();
            for (int i = 0; i < n; i++) out.add(get(in));
            if (in.available() > 0) throw new Bad("extra bytes");
            return out;
        } catch (IOException e) {
            throw new Bad("short");
        }
    }
}
