package dev.moui.galaxycraft.net;

import java.nio.ByteBuffer;

/** PeerState as 63 bytes, big endian: seq, x y z, up, yaw pitch, v, mode (byte), flags (short). */
public final class Wire {
    public static final int SIZE = 4 + 24 + 12 + 8 + 12 + 1 + 2;

    public static final class Malformed extends RuntimeException {
        public Malformed(String why) {
            super(why);
        }
    }

    private Wire() {}

    public static byte[] encode(PeerState s) {
        ByteBuffer b = ByteBuffer.allocate(SIZE);
        b.putInt(s.seq()).putDouble(s.x()).putDouble(s.y()).putDouble(s.z());
        b.putFloat(s.upX()).putFloat(s.upY()).putFloat(s.upZ());
        b.putFloat(s.yaw()).putFloat(s.pitch());
        b.putFloat(s.vx()).putFloat(s.vy()).putFloat(s.vz());
        b.put((byte) s.mode()).putShort((short) s.flags());
        return b.array();
    }

    public static PeerState decode(byte[] raw) {
        if (raw.length != SIZE) throw new Malformed("state is " + raw.length + " bytes, not " + SIZE);
        ByteBuffer b = ByteBuffer.wrap(raw);
        int seq = b.getInt();
        double x = b.getDouble(), y = b.getDouble(), z = b.getDouble();
        float ux = b.getFloat(), uy = b.getFloat(), uz = b.getFloat();
        float yaw = b.getFloat(), pitch = b.getFloat();
        float vx = b.getFloat(), vy = b.getFloat(), vz = b.getFloat();
        int mode = b.get();
        int flags = b.getShort() & 0xFFFF;
        if (mode != Protocol.MODE_STEVE && mode != Protocol.MODE_MARIO) throw new Malformed("unknown mode " + mode);
        return new PeerState(seq, x, y, z, ux, uy, uz, yaw, pitch, vx, vy, vz, mode, flags);
    }
}
