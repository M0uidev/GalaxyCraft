package dev.moui.galaxycraft.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The host shadow's entities near a guest, as one message body: for each its id, kind (registry id), where it
 * stands on the planet (the frame {@code ShadowMap.frame} gives: origin and the planet's step along the entity's
 * x, y and z), how it faces and walks, and Minecraft's synced data (opaque bytes here). Pure, so it is tested
 * without Minecraft. Every size is bounded on read.
 */
public final class MobsCodec {
    public static final int MAX_MOBS = 128;
    public static final int MAX_DATA = 1024;
    /** A whole body at most. */
    public static final int MAX_WIRE = 96 * 1024;

    private MobsCodec() {}

    /** frame: 12 numbers (origin, then three axes). */
    public record Mob(int id, int type, float[] frame, float yaw, float pitch, float headYaw, float walkSpeed, int hurt, int death,
            byte[] data) {}

    public static byte[] encode(int planet, List<Mob> mobs) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(bo)) {
            o.writeShort(planet);
            int n = Math.min(mobs.size(), MAX_MOBS);
            o.writeShort(n);
            for (int i = 0; i < n; i++) {
                Mob m = mobs.get(i);
                o.writeInt(m.id);
                o.writeShort(m.type);
                for (int k = 0; k < 12; k++) o.writeFloat(m.frame[k]);
                o.writeFloat(m.yaw);
                o.writeFloat(m.pitch);
                o.writeFloat(m.headYaw);
                o.writeFloat(m.walkSpeed);
                o.writeByte(m.hurt);
                o.writeByte(m.death);
                int len = Math.min(m.data.length, MAX_DATA);
                o.writeShort(len);
                o.write(m.data, 0, len);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bo.toByteArray();
    }

    /** A decoded body: the planet and its mobs. */
    public record Body(int planet, List<Mob> mobs) {}

    /** Null if the bytes are not a well formed body. */
    public static Body decode(byte[] bytes) {
        if (bytes.length > MAX_WIRE) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int planet = in.readUnsignedShort(), n = in.readUnsignedShort();
            if (n > MAX_MOBS) return null;
            List<Mob> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int id = in.readInt(), type = in.readUnsignedShort();
                float[] f = new float[12];
                for (int k = 0; k < 12; k++) {
                    f[k] = in.readFloat();
                    if (!Float.isFinite(f[k]) || Math.abs(f[k]) > 1e7f) return null;
                }
                float yaw = in.readFloat(), pitch = in.readFloat(), head = in.readFloat(), ws = in.readFloat();
                if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(head) || !Float.isFinite(ws)) return null;
                int hurt = in.readUnsignedByte(), death = in.readUnsignedByte();
                int len = in.readUnsignedShort();
                if (len > MAX_DATA || len > in.available()) return null;
                byte[] d = new byte[len];
                in.readFully(d);
                out.add(new Mob(id, type, f, yaw, pitch, head, ws, hurt, death, d));
            }
            return in.available() == 0 ? new Body(planet, out) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
