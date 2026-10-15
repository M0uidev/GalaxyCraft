package dev.moui.galaxycraft.net;

import dev.moui.galaxycraft.map.Course;
import dev.moui.galaxycraft.map.Orbit;
import dev.moui.galaxycraft.map.Trip;
import dev.moui.galaxycraft.universe.UPos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** A ship's map trip as bytes for ShipTrip. Every number is checked on read. */
public final class TripCodec {
    /** A trip is two courses' worth of numbers: fixed size. */
    public static final int SIZE = 2 * 48 + 8 + 8 + 48 + 8 * 4;

    private TripCodec() {}

    private static void put(DataOutputStream o, UPos p) throws IOException {
        o.writeLong(p.cx());
        o.writeLong(p.cy());
        o.writeLong(p.cz());
        o.writeDouble(p.x());
        o.writeDouble(p.y());
        o.writeDouble(p.z());
    }

    private static double finite(double d) throws ShipCodec.Bad {
        if (!Double.isFinite(d)) throw new ShipCodec.Bad("number");
        return d;
    }

    private static UPos pos(DataInputStream in) throws IOException, ShipCodec.Bad {
        return new UPos(in.readLong(), in.readLong(), in.readLong(), finite(in.readDouble()), finite(in.readDouble()),
                finite(in.readDouble()));
    }

    public static byte[] encode(Trip t) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bytes);
            put(o, t.course().from());
            put(o, t.course().to());
            o.writeLong(t.course().startTick());
            o.writeDouble(t.course().speed());
            put(o, t.orbit().center());
            o.writeDouble(t.orbit().radius());
            o.writeDouble(t.orbit().phase());
            o.writeDouble(t.orbit().rate());
            o.writeLong(t.orbit().epochTick());
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Trip decode(byte[] raw) throws ShipCodec.Bad {
        if (raw.length != SIZE) throw new ShipCodec.Bad("trip of " + raw.length + " bytes");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            UPos from = pos(in), to = pos(in);
            long start = in.readLong();
            double speed = finite(in.readDouble());
            if (!(speed > 0)) throw new ShipCodec.Bad("speed");
            UPos center = pos(in);
            double radius = finite(in.readDouble()), phase = finite(in.readDouble()), rate = finite(in.readDouble());
            return new Trip(new Course(from, to, start, speed), new Orbit(center, radius, phase, rate, in.readLong()));
        } catch (IOException e) {
            throw new ShipCodec.Bad("short");
        } catch (IllegalArgumentException e) {
            throw new ShipCodec.Bad("course");
        }
    }
}
