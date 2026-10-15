package dev.moui.galaxycraft.ship.model;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Reads a J3D model (.bdl / .bmd, SMG2's) as triangles in its rest pose, grouped by the texture of
 * each shape's material. Only what a rigid, textured model needs: vertex positions and texture
 * coordinates, joint transforms (SRT; a weighted vertex follows its heaviest joint), and the first texture of each
 * material. Anything it does not understand is an {@link IllegalArgumentException}.
 */
public final class BdlReader {
    /** Three corners: x, y, z in the model's units (y up) and u, v (0..1). */
    public record Tri(float[][] pos, float[][] uv) {}

    /** The triangles of one shape; texture is an index into the model's textures, -1 if it has none. */
    public record Shape(int texture, List<Tri> tris) {}

    public record Texture(int width, int height, int[] argb) {}

    public record Model(List<Shape> shapes, List<Texture> textures) {}

    /**
     * A model with its skeleton: joints (parent, -1 for the root, and the rest pose's local transform
     * as sx, sy, sz, rx, ry, rz in radians, tx, ty, tz) and its triangles split by joint, in each
     * joint's own space (rigid binding only). Posing it is worlds(local transforms) then each part
     * by its joint's world matrix.
     */
    public record Rig(int[] parent, float[][] rest, List<Part> parts, List<Texture> textures) {}

    /** The triangles of one shape bound to one joint, in that joint's space. */
    public record Part(int joint, int texture, List<Tri> tris) {}

    private static final int MATERIAL_SIZE = 0x14C, MATERIAL_TEX0 = 0x84;

    private BdlReader() {}

    public static Model read(byte[] data) {
        try {
            return parse(data);
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("damaged or unsupported J3D model", e);
        }
    }

    /** The model with its skeleton; weighted vertices, or a triangle across joints, are unsupported. */
    public static Rig readRig(byte[] data) {
        try {
            Parsed p = parse(data, true);
            return p.rig;
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("damaged or unsupported J3D model", e);
        }
    }

    private record Parsed(Model model, Rig rig) {}

    private static Model parse(byte[] data) {
        return parse(data, false).model;
    }

    private static Parsed parse(byte[] data, boolean rig) {
        if (data.length < 0x20 || data[0] != 'J' || data[1] != '3' || data[2] != 'D' || data[3] != '2')
            throw new IllegalArgumentException("not a J3D model");
        String kind = new String(data, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if (!kind.equals("bdl3") && !kind.equals("bdl4") && !kind.equals("bmd3"))
            throw new IllegalArgumentException("unsupported J3D kind " + kind);
        ByteBuffer b = ByteBuffer.wrap(data);
        int count = b.getInt(0x0C);
        Map<String, Integer> ch = new HashMap<>();
        int o = 0x20;
        for (int i = 0; i < count; i++) {
            int size = b.getInt(o + 4);
            if (size < 8 || o + size > data.length) throw new IllegalArgumentException("truncated J3D model");
            ch.put(new String(data, o, 4, java.nio.charset.StandardCharsets.US_ASCII), o);
            o += size;
        }
        for (String need : new String[] {"INF1", "VTX1", "DRW1", "JNT1", "SHP1", "MAT3", "TEX1"}) // EVP1 only with weighted vertices
            if (!ch.containsKey(need)) throw new IllegalArgumentException("J3D model without " + need);

        List<Texture> textures = textures(b, ch.get("TEX1"));
        Matrix4f[] world = new Matrix4f[b.getShort(ch.get("JNT1") + 8) & 0xFFFF];
        int[] parent = new int[world.length];
        float[][] rest = new float[world.length][];
        int[] shapeMaterial = hierarchy(b, ch.get("INF1"), ch.get("JNT1"), world, parent, rest);
        boolean[] weighted = new boolean[b.getShort(ch.get("DRW1") + 8) & 0xFFFF];
        int[] drw = drawMatrices(b, ch.get("DRW1"), ch.get("EVP1"), weighted);
        int[] materialTexture = materials(b, ch.get("MAT3"), textures.size());
        List<List<Integer>> joints = rig ? new ArrayList<>() : null;
        textures = new ArrayList<>(textures);
        List<Shape> shapes = shapes(b, ch.get("VTX1"), ch.get("SHP1"), world, drw, weighted, shapeMaterial, materialTexture, joints, textures);
        if (!rig) return new Parsed(new Model(shapes, textures), null);
        List<Part> parts = new ArrayList<>();
        for (int si = 0; si < shapes.size(); si++) {
            Shape sh = shapes.get(si);
            Map<Integer, List<Tri>> byJoint = new java.util.TreeMap<>();
            for (int t = 0; t < sh.tris().size(); t++)
                byJoint.computeIfAbsent(joints.get(si).get(t), k -> new ArrayList<>()).add(sh.tris().get(t));
            byJoint.forEach((j, tris) -> parts.add(new Part(j, sh.texture(), tris)));
        }
        return new Parsed(null, new Rig(parent, rest, parts, textures));
    }

    /** INF1's tree: joints' world matrices into world[], and the material each shape is under. */
    private static int[] hierarchy(ByteBuffer b, int inf, int jnt, Matrix4f[] world, int[] parent, float[][] rest) {
        int n = world.length, eo = jnt + b.getInt(jnt + 0x0C);
        Matrix4f[] local = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            int e = eo + i * 0x40;
            float k = (float) (Math.PI / 32768.0);
            rest[i] = new float[] {b.getFloat(e + 4), b.getFloat(e + 8), b.getFloat(e + 12),
                    b.getShort(e + 0x10) * k, b.getShort(e + 0x12) * k, b.getShort(e + 0x14) * k,
                    b.getFloat(e + 0x18), b.getFloat(e + 0x1C), b.getFloat(e + 0x20)};
            local[i] = new Matrix4f()
                    .translate(b.getFloat(e + 0x18), b.getFloat(e + 0x1C), b.getFloat(e + 0x20))
                    .rotateZYX((b.getShort(e + 0x14)) * k, (b.getShort(e + 0x12)) * k, (b.getShort(e + 0x10)) * k)
                    .scale(b.getFloat(e + 4), b.getFloat(e + 8), b.getFloat(e + 12));
            world[i] = new Matrix4f(local[i]);
        }
        Map<Integer, Integer> mat = new HashMap<>();
        Deque<Matrix4f> stack = new ArrayDeque<>();
        Deque<Integer> parents = new ArrayDeque<>();
        Matrix4f cur = new Matrix4f(), last = cur;
        int material = -1, curParent = -1, lastJoint = -1;
        java.util.Arrays.fill(parent, -1);
        for (int p = inf + b.getInt(inf + 0x14); ; p += 4) {
            int type = b.getShort(p) & 0xFFFF, idx = b.getShort(p + 2) & 0xFFFF;
            if (type == 0) break;
            switch (type) {
                case 0x01 -> {
                    stack.push(cur);
                    parents.push(curParent);
                    cur = last;
                    curParent = lastJoint;
                }
                case 0x02 -> {
                    cur = stack.pop();
                    curParent = parents.pop();
                }
                case 0x10 -> {
                    last = new Matrix4f(cur).mul(local[idx]);
                    world[idx] = last;
                    parent[idx] = curParent;
                    lastJoint = idx;
                }
                case 0x11 -> {
                    material = idx;
                    last = cur;
                    lastJoint = curParent;
                }
                case 0x12 -> {
                    mat.put(idx, material);
                    last = cur;
                    lastJoint = curParent;
                }
                default -> throw new IllegalArgumentException("J3D hierarchy node " + type);
            }
        }
        int top = mat.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        int[] out = new int[top];
        java.util.Arrays.fill(out, -1);
        mat.forEach((s, m) -> out[s] = m);
        return out;
    }

    /**
     * DRW1: for each matrix a joint index. A weighted entry (EVP1: several joints with weights) is
     * taken as its heaviest joint's, rigidly, and flagged in weighted[]: its vertices are model-space
     * points (the bind pose), not that joint's.
     */
    private static int[] drawMatrices(ByteBuffer b, int r, Integer evpChunk, boolean[] weighted) {
        int n = b.getShort(r + 8) & 0xFFFF, fo = r + b.getInt(r + 0x0C), io = r + b.getInt(r + 0x10);
        int[] out = new int[n];
        int[] first = null;
        for (int i = 0; i < n; i++) {
            int v = b.getShort(io + 2 * i) & 0xFFFF;
            if (b.get(fo + i) != 0) {
                if (evpChunk == null) throw new IllegalArgumentException("weighted vertices without EVP1");
                int e = evpChunk, ne = b.getShort(e + 8) & 0xFFFF, counts = e + b.getInt(e + 0x0C),
                        idx = e + b.getInt(e + 0x10), wts = e + b.getInt(e + 0x14);
                if (first == null) {
                    first = new int[ne + 1];
                    for (int k = 0; k < ne; k++) first[k + 1] = first[k] + (b.get(counts + k) & 0xFF);
                }
                if (v >= ne) throw new IllegalArgumentException("weighted matrix out of range");
                int best = -1;
                float bw = -1;
                for (int k = first[v]; k < first[v + 1]; k++) {
                    float w = b.getFloat(wts + 4 * k);
                    if (w > bw) {
                        bw = w;
                        best = b.getShort(idx + 2 * k) & 0xFFFF;
                    }
                }
                out[i] = best;
                weighted[i] = true;
            } else out[i] = v;
        }
        return out;
    }

    /** MAT3: for each material (by its id) the TEX1 index of its first texture, -1 if none. */
    private static int[] materials(ByteBuffer b, int m, int textureCount) {
        int n = b.getShort(m + 8) & 0xFFFF;
        int init = m + b.getInt(m + 0x0C), remap = m + b.getInt(m + 0x10), table = m + b.getInt(m + 0x0C + 15 * 4);
        int[] out = new int[n];
        for (int k = 0; k < n; k++) {
            int base = init + (b.getShort(remap + 2 * k) & 0xFFFF) * MATERIAL_SIZE;
            int slot = b.getShort(base + MATERIAL_TEX0);
            int tex = slot < 0 ? -1 : b.getShort(table + 2 * slot) & 0xFFFF;
            out[k] = tex < textureCount ? tex : -1;
        }
        return out;
    }

    private static List<Shape> shapes(ByteBuffer b, int v, int s, Matrix4f[] world, int[] drw, boolean[] weighted, int[] shapeMaterial, int[] materialTexture,
            List<List<Integer>> jointsOut, List<Texture> textures) {
        float[][] pos = vertexArray(b, v, 9, 0, 3, 2);
        boolean hasUv = hasAttribute(b, v, 13);
        float[][] uv = hasUv ? vertexArray(b, v, 13, 5, 2, 1) : null;
        int[] colors = hasUv ? null : colorArray(b, v);
        int ns = b.getShort(s + 8) & 0xFFFF;
        int init = s + b.getInt(s + 0x0C), descs = s + b.getInt(s + 0x18), mtxtab = s + b.getInt(s + 0x1C),
                dl = s + b.getInt(s + 0x20), mtxdata = s + b.getInt(s + 0x24), pkt = s + b.getInt(s + 0x28);
        List<Shape> out = new ArrayList<>();
        Vector3f tmp = new Vector3f();
        for (int si = 0; si < ns; si++) {
            int e = init + si * 0x28;
            int groups = b.getShort(e + 2) & 0xFFFF, descIndex = b.getShort(e + 4) & 0xFFFF,
                    firstMd = b.getShort(e + 6) & 0xFFFF, firstPkt = b.getShort(e + 8) & 0xFFFF;
            List<int[]> desc = new ArrayList<>();
            for (int d = descs + descIndex; ; d += 8) {
                int attr = b.getInt(d), type = b.getInt(d + 4);
                if (attr == 0xFF) break;
                desc.add(new int[] {attr, type});
            }
            List<Tri> tris = new ArrayList<>();
            List<Integer> triJoints = jointsOut == null ? null : new ArrayList<>();
            int[] slots = new int[64]; // the matrix in each slot: a packet's table keeps the last one where it says 0xFFFF
            java.util.Arrays.fill(slots, -1);
            for (int g = 0; g < groups; g++) {
                int first = b.getInt(mtxdata + (firstMd + g) * 8 + 4), used = b.getShort(mtxdata + (firstMd + g) * 8 + 2) & 0xFFFF;
                for (int k = 0; k < used && k < slots.length; k++) {
                    int m = b.getShort(mtxtab + 2 * (first + k)) & 0xFFFF;
                    if (m != 0xFFFF) slots[k] = m;
                }
                int size = b.getInt(pkt + (firstPkt + g) * 8), off = b.getInt(pkt + (firstPkt + g) * 8 + 4);
                int p = dl + off, end = p + size;
                while (p < end) {
                    int op = b.get(p++) & 0xFF;
                    if (op == 0) break;
                    int cnt = b.getShort(p) & 0xFFFF;
                    p += 2;
                    float[][] pv = new float[cnt][], tv = new float[cnt][];
                    int[] vj = new int[cnt];
                    for (int i = 0; i < cnt; i++) {
                        int mtx = 0, pi = 0, ti = -1, ci = -1;
                        for (int[] a : desc) {
                            int val;
                            if (a[1] == 1) {
                                if (a[0] > 8) throw new IllegalArgumentException("direct attribute " + a[0]);
                                val = b.get(p++) & 0xFF;
                            } else if (a[1] == 2) val = b.get(p++) & 0xFF;
                            else if (a[1] == 3) {
                                val = b.getShort(p) & 0xFFFF;
                                p += 2;
                            } else throw new IllegalArgumentException("attribute type " + a[1]);
                            if (a[0] == 0) mtx = val / 3;
                            else if (a[0] == 9) pi = val;
                            else if (a[0] == 13) ti = val;
                            else if (a[0] == 11) ci = val;
                        }
                        int draw = slots[mtx];
                        if (draw < 0) throw new IllegalArgumentException("a vertex with no matrix");
                        vj[i] = drw[draw];
                        if (jointsOut == null) {
                            tmp.set(pos[pi][0], pos[pi][1], pos[pi][2]);
                            if (!weighted[draw]) world[drw[draw]].transformPosition(tmp);
                            pv[i] = new float[] {tmp.x, tmp.y, tmp.z};
                        } else if (weighted[draw]) {
                            new Matrix4f(world[drw[draw]]).invert().transformPosition(tmp.set(pos[pi][0], pos[pi][1], pos[pi][2]));
                            pv[i] = new float[] {tmp.x, tmp.y, tmp.z};
                        } else pv[i] = new float[] {pos[pi][0], pos[pi][1], pos[pi][2]};
                        if (uv != null) tv[i] = ti < 0 ? new float[] {0, 0} : new float[] {uv[ti][0], uv[ti][1]};
                        else // no texture coordinates: the vertex colour rides in the third float (split out below)
                            tv[i] = new float[] {0, 0, Float.intBitsToFloat(colors != null && ci >= 0 && ci < colors.length ? colors[ci] : 0xFFFFFFFF)};
                    }
                    int before = tris.size();
                    triangles(op & 0xF8, pv, tv, tris);
                    if (triJoints != null) {
                        // Each triangle goes to its first vertex's joint; a vertex of another joint is moved into
                        // that joint's space at rest (exact in the rest pose, it follows one joint when animated).
                        java.util.IdentityHashMap<float[], Integer> of = new java.util.IdentityHashMap<>();
                        for (int i = 0; i < cnt; i++) of.put(pv[i], vj[i]);
                        for (int t = before; t < tris.size(); t++) {
                            Tri tr = tris.get(t);
                            int ref = of.get(tr.pos()[0]);
                            float[][] np = new float[3][];
                            for (int c = 0; c < 3; c++) {
                                int jc = of.get(tr.pos()[c]);
                                if (jc == ref) np[c] = tr.pos()[c];
                                else {
                                    Matrix4f m = new Matrix4f(world[ref]).invert().mul(world[jc]);
                                    m.transformPosition(tmp.set(tr.pos()[c][0], tr.pos()[c][1], tr.pos()[c][2]));
                                    np[c] = new float[] {tmp.x, tmp.y, tmp.z};
                                }
                            }
                            tris.set(t, new Tri(np, tr.uv()));
                            triJoints.add(ref);
                        }
                    }
                }
            }
            int mat = si < shapeMaterial.length ? shapeMaterial[si] : -1;
            if (uv != null) {
                out.add(new Shape(mat >= 0 && mat < materialTexture.length ? materialTexture[mat] : -1, tris));
                if (jointsOut != null) jointsOut.add(triJoints);
                continue;
            }
            // One shape per colour, each with a flat texture of it (the models with colours and no texture).
            java.util.Map<Integer, List<Integer>> byColor = new java.util.LinkedHashMap<>();
            for (int t = 0; t < tris.size(); t++)
                byColor.computeIfAbsent(Float.floatToRawIntBits(tris.get(t).uv()[0][2]), k -> new ArrayList<>()).add(t);
            for (var ent : byColor.entrySet()) {
                int[] px = new int[16];
                java.util.Arrays.fill(px, ent.getKey());
                textures.add(new Texture(4, 4, px));
                List<Tri> part = new ArrayList<>();
                List<Integer> pj = jointsOut == null ? null : new ArrayList<>();
                for (int t : ent.getValue()) {
                    Tri tr = tris.get(t);
                    part.add(new Tri(tr.pos(), new float[][] {{0, 0}, {0, 0}, {0, 0}}));
                    if (pj != null) pj.add(triJoints.get(t));
                }
                out.add(new Shape(textures.size() - 1, part));
                if (jointsOut != null) jointsOut.add(pj);
            }
        }
        return out;
    }

    private static void triangles(int prim, float[][] p, float[][] t, List<Tri> out) {
        int n = p.length;
        switch (prim) {
            case 0x90 -> {
                for (int i = 0; i + 2 < n; i += 3) out.add(tri(p, t, i, i + 1, i + 2));
            }
            case 0x98 -> {
                for (int i = 0; i + 2 < n; i++) out.add(i % 2 == 0 ? tri(p, t, i, i + 1, i + 2) : tri(p, t, i + 1, i, i + 2));
            }
            case 0xA0 -> {
                for (int i = 1; i + 1 < n; i++) out.add(tri(p, t, 0, i, i + 1));
            }
            case 0x80 -> {
                for (int i = 0; i + 3 < n; i += 4) {
                    out.add(tri(p, t, i, i + 1, i + 2));
                    out.add(tri(p, t, i, i + 2, i + 3));
                }
            }
            default -> throw new IllegalArgumentException("primitive " + Integer.toHexString(prim));
        }
    }

    private static Tri tri(float[][] p, float[][] t, int a, int b, int c) {
        return new Tri(new float[][] {p[a], p[b], p[c]}, new float[][] {t[a], t[b], t[c]});
    }

    private static boolean hasAttribute(ByteBuffer b, int v, int attr) {
        for (int o = v + b.getInt(v + 8); ; o += 16) {
            int a = b.getInt(o);
            if (a == 0xFF) return false;
            if (a == attr) return true;
        }
    }

    /** VTX1's colour 0 array as 0xAARRGGBB, or null (none, or a format not read). */
    private static int[] colorArray(ByteBuffer b, int v) {
        int secSize = b.getInt(v + 4), fmt = v + b.getInt(v + 8);
        int[] offs = new int[13];
        for (int i = 0; i < 13; i++) offs[i] = b.getInt(v + 0x0C + 4 * i);
        for (int o = fmt; ; o += 16) {
            int a = b.getInt(o);
            if (a == 0xFF) return null;
            if (a != 11) continue;
            int ctype = b.getInt(o + 8);
            int size = ctype == 1 ? 3 : (ctype == 2 || ctype == 5) ? 4 : -1;
            if (size < 0) return null;
            int start = offs[3], next = secSize;
            for (int x : offs) if (x > start && x < next) next = x;
            int[] out = new int[(next - start) / size];
            for (int i = 0; i < out.length; i++) {
                int at = v + start + i * size;
                int r = b.get(at) & 0xFF, g = b.get(at + 1) & 0xFF, bl = b.get(at + 2) & 0xFF;
                int al = ctype == 5 ? b.get(at + 3) & 0xFF : 0xFF;
                out[i] = al << 24 | r << 16 | g << 8 | bl;
            }
            return out;
        }
    }

    /** VTX1's array for attribute attr: offsets[offsetIndex]; comps when the format's count is 1, else comps0. */
    private static float[][] vertexArray(ByteBuffer b, int v, int attr, int offsetIndex, int comps, int comps0) {
        int secSize = b.getInt(v + 4), fmt = v + b.getInt(v + 8);
        int[] offs = new int[13];
        for (int i = 0; i < 13; i++) offs[i] = b.getInt(v + 0x0C + 4 * i);
        for (int o = fmt; ; o += 16) {
            int a = b.getInt(o);
            if (a == 0xFF) break;
            if (a != attr) continue;
            int cnt = b.getInt(o + 4), ctype = b.getInt(o + 8), frac = b.get(o + 12) & 0xFF;
            int n = cnt == 0 ? comps0 : comps, size = ctype <= 1 ? 1 : ctype <= 3 ? 2 : 4;
            int start = offs[offsetIndex], next = secSize;
            for (int x : offs) if (x > start && x < next) next = x;
            int count = (next - start) / (size * n);
            float[][] out = new float[count][3];
            for (int i = 0; i < count; i++)
                for (int c = 0; c < n; c++) {
                    int at = v + start + (i * n + c) * size;
                    float val = switch (ctype) {
                        case 0 -> b.get(at) & 0xFF;
                        case 1 -> b.get(at);
                        case 2 -> b.getShort(at) & 0xFFFF;
                        case 3 -> b.getShort(at);
                        case 4 -> b.getFloat(at);
                        default -> throw new IllegalArgumentException("component type " + ctype);
                    };
                    out[i][c] = ctype == 4 ? val : val / (1 << frac);
                }
            return out;
        }
        throw new IllegalArgumentException("J3D model without attribute " + attr);
    }

    // ---- TEX1 ----

    private static List<Texture> textures(ByteBuffer b, int t) {
        int n = b.getShort(t + 8) & 0xFFFF, ho = t + b.getInt(t + 0x0C);
        List<Texture> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int h = ho + i * 0x20;
            int fmt = b.get(h) & 0xFF, w = b.getShort(h + 2) & 0xFFFF, hh = b.getShort(h + 4) & 0xFFFF;
            int image = h + b.getInt(h + 0x1C);
            out.add(w == 0 || hh == 0 || w > 1024 || hh > 1024 ? white() : decode(b, fmt, w, hh, image));
        }
        return out;
    }

    private static Texture white() {
        return new Texture(1, 1, new int[] {0xFFFFFFFF});
    }

    private static Texture decode(ByteBuffer b, int fmt, int w, int h, int at) {
        int bw, bh;
        switch (fmt) {
            case 0 -> { bw = 8; bh = 8; }                 // I4
            case 1, 2 -> { bw = 8; bh = 4; }              // I8, IA4
            case 3, 4, 5, 6 -> { bw = 4; bh = 4; }        // IA8, RGB565, RGB5A3, RGBA32
            case 0xE -> { bw = 8; bh = 8; }               // CMPR
            default -> { return white(); }                // palettes and others: flat white
        }
        int[] px = new int[w * h];
        int p = at;
        for (int ty = 0; ty < h; ty += bh)
            for (int tx = 0; tx < w; tx += bw) {
                if (fmt == 0xE) {
                    for (int sub = 0; sub < 4; sub++) {
                        decodeDxt(b, p, px, w, h, tx + (sub & 1) * 4, ty + (sub >> 1) * 4);
                        p += 8;
                    }
                } else if (fmt == 6) {
                    for (int y = 0; y < 4; y++)
                        for (int x = 0; x < 4; x++) {
                            int ar = b.getShort(p + 2 * (y * 4 + x)) & 0xFFFF, gb = b.getShort(p + 32 + 2 * (y * 4 + x)) & 0xFFFF;
                            put(px, w, h, tx + x, ty + y, (ar >> 8) << 24 | (ar & 0xFF) << 16 | gb);
                        }
                    p += 64;
                } else {
                    for (int y = 0; y < bh; y++)
                        for (int x = 0; x < bw; x++) {
                            int c;
                            switch (fmt) {
                                case 0 -> {
                                    int v = b.get(p + (y * bw + x) / 2) & 0xFF;
                                    int i = ((x & 1) == 0 ? v >> 4 : v & 15) * 17;
                                    c = 0xFF000000 | i << 16 | i << 8 | i;
                                }
                                case 1 -> {
                                    int i = b.get(p + y * bw + x) & 0xFF;
                                    c = 0xFF000000 | i << 16 | i << 8 | i;
                                }
                                case 2 -> {
                                    int v = b.get(p + y * bw + x) & 0xFF, i = (v & 15) * 17, a = (v >> 4) * 17;
                                    c = a << 24 | i << 16 | i << 8 | i;
                                }
                                case 3 -> {
                                    int a = b.get(p + 2 * (y * bw + x)) & 0xFF, i = b.get(p + 2 * (y * bw + x) + 1) & 0xFF;
                                    c = a << 24 | i << 16 | i << 8 | i;
                                }
                                case 4 -> c = rgb565(b.getShort(p + 2 * (y * bw + x)) & 0xFFFF);
                                default -> c = rgb5a3(b.getShort(p + 2 * (y * bw + x)) & 0xFFFF);
                            }
                            put(px, w, h, tx + x, ty + y, c);
                        }
                    p += 32; // every other tile format is 32 bytes
                }
            }
        return new Texture(w, h, px);
    }

    private static void put(int[] px, int w, int h, int x, int y, int argb) {
        if (x < w && y < h) px[y * w + x] = argb;
    }

    private static int rgb565(int v) {
        int r = (v >> 11) & 31, g = (v >> 5) & 63, bl = v & 31;
        return 0xFF000000 | (r * 255 / 31) << 16 | (g * 255 / 63) << 8 | (bl * 255 / 31);
    }

    private static int rgb5a3(int v) {
        if ((v & 0x8000) != 0) {
            int r = (v >> 10) & 31, g = (v >> 5) & 31, bl = v & 31;
            return 0xFF000000 | (r * 255 / 31) << 16 | (g * 255 / 31) << 8 | (bl * 255 / 31);
        }
        int a = ((v >> 12) & 7) * 255 / 7, r = ((v >> 8) & 15) * 17, g = ((v >> 4) & 15) * 17, bl = (v & 15) * 17;
        return a << 24 | r << 16 | g << 8 | bl;
    }

    private static void decodeDxt(ByteBuffer b, int p, int[] px, int w, int h, int x0, int y0) {
        int c0 = b.getShort(p) & 0xFFFF, c1 = b.getShort(p + 2) & 0xFFFF;
        int[] col = new int[4];
        col[0] = rgb565(c0);
        col[1] = rgb565(c1);
        if (c0 > c1) {
            col[2] = mix(col[0], 2, col[1], 1, 3);
            col[3] = mix(col[0], 1, col[1], 2, 3);
        } else {
            col[2] = mix(col[0], 1, col[1], 1, 2);
            col[3] = 0;
        }
        for (int y = 0; y < 4; y++) {
            int row = b.get(p + 4 + y) & 0xFF;
            for (int x = 0; x < 4; x++) put(px, w, h, x0 + x, y0 + y, col[(row >> (6 - 2 * x)) & 3]);
        }
    }

    private static int mix(int a, int wa, int c, int wc, int div) {
        int out = 0xFF000000;
        for (int sh = 16; sh >= 0; sh -= 8)
            out |= ((((a >> sh) & 0xFF) * wa + ((c >> sh) & 0xFF) * wc) / div) << sh;
        return out;
    }
}
