package dev.moui.galaxycraft.ship.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Reads a J3D joint animation (.bck, SMG2's): for every joint, scale, rotation and translation on
 * each axis, as constants or hermite keys. Sampled per frame into {@code sx, sy, sz, rx, ry, rz,
 * tx, ty, tz} (rotations in radians), the joint's local transform at that frame. Anything it does
 * not understand is an {@link IllegalArgumentException}.
 */
public final class BckReader {
    /** One component's track: a constant, or keys (time, value, tangent in, tangent out). */
    private record Track(float[] time, float[] value, float[] tin, float[] tout) {
        float at(float frame) {
            int n = value.length;
            if (n == 1) return value[0];
            if (frame <= time[0]) return value[0];
            if (frame >= time[n - 1]) return value[n - 1];
            int k = 0;
            while (k + 2 < n && frame >= time[k + 1]) k++;
            float t0 = time[k], t1 = time[k + 1], d = t1 - t0, f = (frame - t0) / d;
            float f2 = f * f, f3 = f2 * f;
            return (2 * f3 - 3 * f2 + 1) * value[k] + (-2 * f3 + 3 * f2) * value[k + 1]
                    + (f3 - 2 * f2 + f) * d * tout[k] + (f3 - f2) * d * tin[k + 1];
        }
    }

    /** A whole animation: {@code joints()} joints of nine tracks each, {@code duration()} frames long. */
    public static final class Anim {
        private final int duration, loop;
        private final Track[][] tracks;

        Anim(int duration, int loop, Track[][] tracks) {
            this.duration = duration;
            this.loop = loop;
            this.tracks = tracks;
        }

        public int duration() {
            return duration;
        }

        public int joints() {
            return tracks.length;
        }

        /** The joint's local transform at a frame (looping ones wrap) into out[0..8]. */
        public void sample(int joint, float frame, float[] out) {
            float f = frame;
            if (duration > 0) {
                if (loop == 2 || loop == 4) {
                    f = f % duration;
                    if (f < 0) f += duration;
                } else f = Math.max(0, Math.min(duration, f));
            }
            Track[] t = tracks[joint];
            for (int i = 0; i < 9; i++) out[i] = t[i].at(f);
        }
    }

    private BckReader() {}

    public static Anim read(byte[] data) {
        try {
            return parse(data);
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("damaged or unsupported J3D animation", e);
        }
    }

    private static Anim parse(byte[] data) {
        if (data.length < 0x40 || data[0] != 'J' || data[1] != '3' || data[2] != 'D' || data[3] != '1'
                || !new String(data, 4, 4, StandardCharsets.US_ASCII).equals("bck1"))
            throw new IllegalArgumentException("not a J3D bck");
        ByteBuffer b = ByteBuffer.wrap(data);
        int count = b.getInt(0x0C), o = 0x20, a = -1;
        for (int i = 0; i < count; i++) {
            int size = b.getInt(o + 4);
            if (size < 8 || o + size > data.length) throw new IllegalArgumentException("truncated J3D bck");
            if (new String(data, o, 4, StandardCharsets.US_ASCII).equals("ANK1")) a = o;
            o += size;
        }
        if (a < 0) throw new IllegalArgumentException("J3D bck without ANK1");
        int loop = b.get(a + 8) & 0xFF;
        float angle = (float) (Math.pow(2, b.get(a + 9)) * Math.PI / 32768.0);
        int duration = b.getShort(a + 0x0A) & 0xFFFF, joints = b.getShort(a + 0x0C) & 0xFFFF;
        int nScale = b.getShort(a + 0x0E) & 0xFFFF, nRot = b.getShort(a + 0x10) & 0xFFFF, nTrans = b.getShort(a + 0x12) & 0xFFFF;
        int jo = a + b.getInt(a + 0x14), so = a + b.getInt(a + 0x18), ro = a + b.getInt(a + 0x1C), to = a + b.getInt(a + 0x20);
        Track[][] tracks = new Track[joints][9];
        // Per joint, per axis: scale, rotation, translation (count, index, tangent mode: 6 bytes each).
        for (int j = 0; j < joints; j++)
            for (int axis = 0; axis < 3; axis++)
                for (int kind = 0; kind < 3; kind++) {
                    int e = jo + j * 0x36 + (axis * 3 + kind) * 6;
                    int n = b.getShort(e) & 0xFFFF, index = b.getShort(e + 2) & 0xFFFF, mode = b.getShort(e + 4) & 0xFFFF;
                    int slot = kind * 3 + axis; // sx sy sz rx ry rz tx ty tz
                    tracks[j][slot] = kind == 1 ? shortTrack(b, ro, nRot, index, n, mode, angle) : floatTrack(b, kind == 0 ? so : to,
                            kind == 0 ? nScale : nTrans, index, n, mode);
                }
        return new Anim(duration, loop, tracks);
    }

    private static Track floatTrack(ByteBuffer b, int base, int size, int index, int n, int mode) {
        if (n < 1) throw new IllegalArgumentException("empty track");
        if (n == 1) return constant(read(b, base, size, index, false, 1f));
        int stride = mode == 0 ? 3 : 4;
        return keys(n, stride, i -> read(b, base, size, index + i, false, 1f));
    }

    private static Track shortTrack(ByteBuffer b, int base, int size, int index, int n, int mode, float angle) {
        if (n < 1) throw new IllegalArgumentException("empty track");
        if (n == 1) return constant(read(b, base, size, index, true, angle));
        int stride = mode == 0 ? 3 : 4;
        // Times are plain frame numbers, values and tangents are angles.
        return keysAngle(n, stride, i -> read(b, base, size, index + i, true, 1f), angle);
    }

    private static float read(ByteBuffer b, int base, int size, int i, boolean isShort, float k) {
        if (i < 0 || i >= size) throw new IllegalArgumentException("animation data out of range");
        return isShort ? b.getShort(base + 2 * i) * k : b.getFloat(base + 4 * i) * k;
    }

    private static Track constant(float v) {
        return new Track(new float[] {0}, new float[] {v}, new float[] {0}, new float[] {0});
    }

    private static Track keys(int n, int stride, java.util.function.IntToDoubleFunction at) {
        return keysAngle(n, stride, i -> (float) at.applyAsDouble(i), 1f);
    }

    private interface At {
        float get(int i);
    }

    private static Track keysAngle(int n, int stride, At at, float k) {
        float[] t = new float[n], v = new float[n], in = new float[n], out = new float[n];
        for (int i = 0; i < n; i++) {
            t[i] = at.get(i * stride);
            v[i] = at.get(i * stride + 1) * k;
            in[i] = at.get(i * stride + 2) * k;
            out[i] = stride == 4 ? at.get(i * stride + 3) * k : in[i];
        }
        return new Track(t, v, in, out);
    }
}
