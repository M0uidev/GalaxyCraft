package dev.moui.galaxycraft.net;

import dev.moui.galaxycraft.toad.ToadRecord;
import dev.moui.galaxycraft.toad.ToadType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The host's Toads to its guests: records that are new or changed, and ids that are gone ({@code full}: the guest
 * forgets all it had first). Pure and bounded on read.
 */
public final class ToadCodec {
    public static final int MAX_BYTES = 262_144, MAX_RECORDS = 2048, MAX_STR = 128;

    private ToadCodec() {}

    public static final class Bad extends Exception {
        public Bad(String why) {
            super(why);
        }
    }

    public record Body(boolean full, List<ToadRecord> puts, List<String> gone) {}

    public static byte[] encode(boolean full, List<ToadRecord> puts, List<String> gone) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(bo)) {
            o.writeBoolean(full);
            int n = Math.min(puts.size(), MAX_RECORDS);
            o.writeShort(n);
            for (int i = 0; i < n; i++) {
                ToadRecord r = puts.get(i);
                o.writeUTF(r.id());
                o.writeUTF(r.type().id());
                for (int k = 0; k < 3; k++) o.writeDouble(r.pos()[k]);
                for (int k = 0; k < 4; k++) o.writeDouble(r.rot()[k]);
                o.writeUTF(r.stage());
                o.writeBoolean(r.home() != null);
                if (r.home() != null) for (int k = 0; k < 3; k++) o.writeDouble(r.home()[k]);
            }
            int g = Math.min(gone.size(), MAX_RECORDS);
            o.writeShort(g);
            for (int i = 0; i < g; i++) o.writeUTF(gone.get(i));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bo.toByteArray();
    }

    public static Body decode(byte[] bytes) throws Bad {
        if (bytes.length > MAX_BYTES) throw new Bad("too long");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            boolean full = in.readBoolean();
            int n = in.readUnsignedShort();
            if (n > MAX_RECORDS) throw new Bad("too many Toads");
            List<ToadRecord> puts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String id = str(in);
                ToadType type = null;
                String t = str(in);
                for (ToadType x : ToadType.values()) if (x.id().equals(t)) type = x;
                double[] pos = new double[3], rot = new double[4];
                for (int k = 0; k < 3; k++) pos[k] = finite(in.readDouble());
                for (int k = 0; k < 4; k++) rot[k] = finite(in.readDouble());
                String stage = str(in);
                double[] home = null;
                if (in.readBoolean()) {
                    home = new double[3];
                    for (int k = 0; k < 3; k++) home[k] = finite(in.readDouble());
                }
                if (type != null) puts.add(new ToadRecord(id, type, pos, rot, stage, home)); // a type this version lacks is skipped
            }
            int g = in.readUnsignedShort();
            if (g > MAX_RECORDS) throw new Bad("too many gone");
            List<String> gone = new ArrayList<>(g);
            for (int i = 0; i < g; i++) gone.add(str(in));
            return new Body(full, puts, gone);
        } catch (IOException e) {
            throw new Bad(e.getMessage());
        }
    }

    private static String str(DataInputStream in) throws IOException, Bad {
        String s = in.readUTF();
        if (s.length() > MAX_STR) throw new Bad("a string too long");
        return s;
    }

    private static double finite(double v) throws Bad {
        if (!Double.isFinite(v)) throw new Bad("not a number");
        return v;
    }
}
