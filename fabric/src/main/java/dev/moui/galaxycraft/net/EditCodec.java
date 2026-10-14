package dev.moui.galaxycraft.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The body of an Edits message: cell changes of one planet as (cell delta, wire id) pairs, the names of
 * wire ids the receiver may not know yet, deflated when it pays. Pure: no Minecraft. Every size is bounded on read.
 */
public final class EditCodec {
    public static final int MAX_ENTRIES = 2048, MAX_DEFS = 64, MAX_NAME = 160, MAX_WIRE_IDS = 4096;
    public static final int MAX_INFLATED = 256 * 1024, MAX_WIRE = 64 * 1024, DEFLATE_OVER = 128;

    private EditCodec() {}

    public record Def(int wireId, String name) {}

    /** planet: catalog index; seq: the host's sequence (0 from a guest); origin: the author's batch number (guest -> host: its own). */
    public record Msg(int planet, int seq, int origin, List<Def> defs, int[] cells, int[] ids) {
        public Msg withOrigin(int o) {
            return o == origin ? this : new Msg(planet, seq, o, defs, cells, ids);
        }

        public Msg withDefs(List<Def> d) {
            return new Msg(planet, seq, origin, d, cells, ids);
        }
    }

    public static final class Bad extends IOException {
        Bad(String why) {
            super(why);
        }
    }

    public static byte[] encode(Msg m) {
        int n = m.cells().length;
        if (n != m.ids().length || n > MAX_ENTRIES || m.defs().size() > MAX_DEFS) throw new IllegalArgumentException("too many entries or defs");
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(m.cells()[a], m.cells()[b])); // stable: the last of a cell stays last
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varint(body, m.planet());
        varint(body, m.seq());
        varint(body, m.origin());
        varint(body, m.defs().size());
        for (Def d : m.defs()) {
            if (d.wireId() < 0 || d.wireId() >= MAX_WIRE_IDS) throw new IllegalArgumentException("wire id " + d.wireId());
            byte[] name = d.name().getBytes(StandardCharsets.UTF_8);
            if (name.length > MAX_NAME) throw new IllegalArgumentException("name too long");
            varint(body, d.wireId());
            varint(body, name.length);
            body.writeBytes(name);
        }
        int kept = 0;
        for (int i = 0; i < n; i++) if (i + 1 == n || m.cells()[order[i]] != m.cells()[order[i + 1]]) kept++;
        varint(body, kept);
        int last = 0;
        for (int i = 0; i < n; i++) {
            if (i + 1 < n && m.cells()[order[i]] == m.cells()[order[i + 1]]) continue;
            int cell = m.cells()[order[i]];
            if (m.ids()[order[i]] < 0 || m.ids()[order[i]] >= MAX_WIRE_IDS) throw new IllegalArgumentException("wire id");
            varint(body, cell - last);
            varint(body, m.ids()[order[i]]);
            last = cell;
        }
        byte[] raw = body.toByteArray();
        if (raw.length <= DEFLATE_OVER) return concat((byte) 0, raw);
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!def.finished()) z.write(buf, 0, def.deflate(buf));
        def.end();
        byte[] packed = z.toByteArray();
        return packed.length < raw.length ? concat((byte) 1, packed) : concat((byte) 0, raw);
    }

    public static Msg decode(byte[] wire) throws Bad {
        if (wire.length < 2 || wire.length > MAX_WIRE) throw new Bad("size " + wire.length);
        byte[] raw;
        if (wire[0] == 0) raw = Arrays.copyOfRange(wire, 1, wire.length);
        else if (wire[0] == 1) raw = inflate(wire);
        else throw new Bad("flag");
        int[] at = {0};
        int planet = varint(raw, at), seq = varint(raw, at), origin = varint(raw, at);
        int nd = varint(raw, at);
        if (nd > MAX_DEFS) throw new Bad("defs");
        List<Def> defs = new ArrayList<>(nd);
        for (int i = 0; i < nd; i++) {
            int id = varint(raw, at), len = varint(raw, at);
            if (id >= MAX_WIRE_IDS || len > MAX_NAME || at[0] + len > raw.length) throw new Bad("def");
            defs.add(new Def(id, new String(raw, at[0], len, StandardCharsets.UTF_8)));
            at[0] += len;
        }
        int n = varint(raw, at);
        if (n > MAX_ENTRIES) throw new Bad("entries");
        int[] cells = new int[n], ids = new int[n];
        long cell = 0;
        for (int i = 0; i < n; i++) {
            cell += varint(raw, at);
            int id = varint(raw, at);
            if (cell > Integer.MAX_VALUE || id >= MAX_WIRE_IDS) throw new Bad("entry");
            cells[i] = (int) cell;
            ids[i] = id;
        }
        if (at[0] != raw.length) throw new Bad("trailing bytes");
        return new Msg(planet, seq, origin, defs, cells, ids);
    }

    private static byte[] inflate(byte[] wire) throws Bad {
        Inflater inf = new Inflater();
        inf.setInput(wire, 1, wire.length - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (!inf.finished()) {
                int got = inf.inflate(buf);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) throw new Bad("truncated");
                out.write(buf, 0, got);
                if (out.size() > MAX_INFLATED) throw new Bad("inflates too big");
            }
        } catch (DataFormatException e) {
            throw new Bad("not deflate");
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte flag, byte[] rest) {
        byte[] out = new byte[rest.length + 1];
        out[0] = flag;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    static void varint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) {
            o.write(v & 0x7F | 0x80);
            v >>>= 7;
        }
        o.write(v);
    }

    /** A non-negative varint (at most 31 bits). */
    static int varint(byte[] b, int[] at) throws Bad {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (at[0] >= b.length) throw new Bad("truncated");
            int x = b[at[0]++];
            v |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                if (v < 0) throw new Bad("varint");
                return v;
            }
        }
        throw new Bad("varint");
    }
}
