package dev.moui.galaxycraft.ship.model;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.view.EntityWire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ship's pieces: the Starshroom, SMG2's own Toad Brigade ship (UFOKinoko.arc, copied by the
 * launcher from the player's disc), the hull's model; the other modules have none as quads and skins in the form {@link EntityWire} sends to the game, sized to the module's
 * slot, with tinted cubes where a model is missing or unsupported. Quads keep the model's own axes
 * (y up) and are centred; the caller's matrix maps pixels to the galaxy without mirroring.
 */
public final class ShipModels {
    /** Quads per piece: a display list holds at most ENT_DL_MAX bytes (1170 quads), with room to spare. */
    public static final int MAX_QUADS = 1000;
    private static final int[] WHITE = {0xFFFFFFFF};
    /** A skin is whole 4x4 texels (the game's RGB5A3 blocks): a flat colour's is all white. */
    private static final Skin WHITE_4X4 = new Skin(4, 4, new int[] {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1});

    public enum Slot {
        /** The Starshroom, the Toad Brigade's mushroom ship: the one model the ship is drawn with. */
        HULL("UFOKinoko.arc", 80f, new float[] {0, 0, 0}, 0xFFFFFFFF),
        /** The other modules live inside the Starshroom (they change stats and seats, not the look). */
        ENGINE(null, 0f, new float[] {0, 0, 0}, 0xE03030FF),
        RUDDER(null, 0f, new float[] {0, 0, 0}, 0xE03030FF),
        CABIN(null, 0f, new float[] {0, 0, 0}, 0xFFFFFFFF);

        final String arc;
        /** The longest side of the module at tier 1, in pixels (16 per block). */
        final float pixels;
        final float[] offset;
        /** RGBA tint of the cube that stands in for a missing model. */
        final int cubeTint;

        Slot(String arc, float pixels, float[] offset, int cubeTint) {
            this.arc = arc;
            this.pixels = pixels;
            this.offset = offset;
            this.cubeTint = cubeTint;
        }
    }

    public record Skin(int width, int height, int[] argb) {}

    /** One rigid piece: quads (model pixels), its skin and the RGBA tint. */
    public record PartModel(List<EntityWire.Quad> quads, Skin skin, int tint) {}

    /** A piece of a module, where the module sits on the ship (pixels from the ship's middle, nose +z). */
    public record Placed(Slot slot, PartModel part, float[] offset) {}

    private static volatile Map<Slot, BdlReader.Model> models = Map.of();
    private static final Map<Integer, List<PartModel>> CACHE = new ConcurrentHashMap<>();

    private ShipModels() {}

    /** Reads the arcs of dir (the launcher's <data>/ship); anything missing or unsupported is a cube. */
    public static synchronized void load(Path dir) {
        Map<Slot, BdlReader.Model> found = new EnumMap<>(Slot.class);
        for (Slot s : Slot.values()) {
            if (s.arc == null) continue;
            Path f = dir.resolve(s.arc);
            try {
                if (!Files.isRegularFile(f)) continue;
                Map<String, byte[]> files = new TreeMap<>(Rarc.read(Files.readAllBytes(f)));
                byte[] bdl = files.entrySet().stream()
                        .filter(e -> e.getKey().endsWith(".bdl") || e.getKey().endsWith(".bmd"))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                if (bdl != null) found.put(s, BdlReader.read(bdl));
            } catch (IOException | IllegalArgumentException e) {
                GalaxyCraft.LOG.warn("The ship's {} could not be read ({}); a cube stands in", s.arc, e.getMessage());
            }
        }
        models = found;
        CACHE.clear();
        CACHE_GLASS.clear();
    }

    /** The pieces of a module at a tier (1..3): its model, or a tinted cube. */
    public static List<PartModel> parts(Slot slot, int tier) {
        if (slot.arc == null) return List.of();
        int t = Math.max(1, Math.min(ShipModules.MAX_TIER, tier));
        return CACHE.computeIfAbsent(slot.ordinal() * 10 + t, k -> {
            float grow = 1f + 0.1f * (t - 1);
            BdlReader.Model m = models.get(slot);
            if (m == null) return List.of(cube(slot, grow));
            float scale = fitScale(m, slot.pixels) * grow;
            List<PartModel> p = slot == Slot.HULL ? starshroom(m, scale) : null;
            if (p == null) p = parts(m, scale, 0xFFFFFFFF);
            if (slot == Slot.HULL && p.stream().noneMatch(x -> x.quads().stream().anyMatch(q -> q.colors() != null)))
                p = p.stream().map(ShipModels::painted).toList();
            return p.isEmpty() ? List.of(cube(slot, grow)) : p;
        });
    }

    /** Every piece of the modules a ship has. */
    public static List<Placed> layout(ShipModules m) {
        int[] tiers = {m.hull(), m.engine(), m.rudder(), m.cabin()};
        List<Placed> out = new ArrayList<>();
        for (Slot s : Slot.values()) {
            // The Starshroom is the ship's body: drawn even where the hull module is missing (tier 0).
            int tier = s == Slot.HULL ? Math.max(1, tiers[s.ordinal()]) : tiers[s.ordinal()];
            if (tier <= 0) continue;
            for (PartModel p : parts(s, tier)) out.add(new Placed(s, p, s.offset));
        }
        return out;
    }

    // ---- the Starshroom, shaded ----

    /** What each of the Starshroom's nine shapes is (by index, with the texture it must have). */
    private static final int[] STAR_TEXTURE = {3, 8, 0, 3, 10, 9, 2, 6, 0};
    /** Flat colours (RGB) of the shapes that are only masks in the file, -1: keep the texture. */
    private static final int[] STAR_COLOR = {0xECE2C4, -1, 0x545660, 0xD6281C, -1, -1, -1, 0x8CBEE6, 0x3C3C42};
    /** Shapes drawn from outside: shaded by their (smoothed) normals; the cockpit inside is left lit. */
    private static final boolean[] STAR_OUTSIDE = {true, true, true, true, false, false, false, true, true};
    /** The portholes' glass: see-through. */
    private static final int STAR_GLASS = 7;
    private static final int GLASS_ALPHA = 0x70;

    /**
     * The Starshroom with its look rebuilt: the game's materials use the file's textures as masks and
     * sphere maps under a TEV setup this wire cannot carry, so each shape gets a flat colour (or its
     * real texture) and smooth shading and a highlight per corner from averaged normals. null if the
     * model is not the Starshroom (then the generic path draws it).
     */
    static List<PartModel> starshroom(BdlReader.Model m, float scale) {
        if (!starshroomShape(m)) return null;
        float[] b = bounds(m);
        float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
        // Normals per position, area weighted, over the outside shapes (seams between shapes match).
        java.util.HashMap<Long, float[]> normals = new java.util.HashMap<>();
        for (int s = 0; s < m.shapes().size(); s++) {
            if (!STAR_OUTSIDE[s]) continue;
            for (BdlReader.Tri t : m.shapes().get(s).tris()) {
                float[] n = faceNormal(t, cx, cy, cz);
                for (float[] p : t.pos()) {
                    float[] acc = normals.computeIfAbsent(key(p), k -> new float[3]);
                    for (int c = 0; c < 3; c++) acc[c] += n[c];
                }
            }
        }
        List<PartModel> out = new ArrayList<>();
        for (int s = 0; s < m.shapes().size(); s++) {
            if (s == STAR_GLASS) continue; // the portholes are open: the cockpit shows through, as a window does
            BdlReader.Shape sh = m.shapes().get(s);
            boolean flat = STAR_COLOR[s] >= 0;
            BdlReader.Texture tex = m.textures().get(sh.texture());
            Skin skin = flat ? WHITE_4X4 : new Skin(tex.width(), tex.height(), tex.argb());
            for (int from = 0; from < sh.tris().size(); from += MAX_QUADS) {
                var chunk = sh.tris().subList(from, Math.min(sh.tris().size(), from + MAX_QUADS));
                List<EntityWire.Quad> quads = new ArrayList<>(chunk.size());
                for (BdlReader.Tri t : chunk) {
                    float[][] pos = new float[4][], uv = new float[4][];
                    int[] cols = new int[4];
                    for (int i = 0; i < 4; i++) {
                        int k = Math.min(i, 2);
                        float[] p = t.pos()[k];
                        pos[i] = new float[] {(p[0] - cx) * scale, (p[1] - cy) * scale, (p[2] - cz) * scale};
                        uv[i] = new float[] {t.uv()[k][0], t.uv()[k][1]};
                        float[] n = STAR_OUTSIDE[s] ? normals.get(key(p)) : null;
                        cols[i] = shade(n, flat ? STAR_COLOR[s] : 0xFFFFFF, s == 3 || s == STAR_GLASS, s == STAR_GLASS ? GLASS_ALPHA : 0xFF);
                    }
                    quads.add(new EntityWire.Quad(pos, uv, 0xFFFFFFFF, cols));
                }
                out.add(new PartModel(quads, skin, 0xFFFFFFFF));
            }
        }
        return out;
    }

    /**
     * The Starshroom's portholes' glass as pieces (white, the file's own UVs over each disc) for
     * Minecraft's glass texture to be put on; none for a model that is not the Starshroom.
     */
    public static List<PartModel> glass(int hullTier) {
        int t = Math.max(1, Math.min(ShipModules.MAX_TIER, hullTier));
        return CACHE_GLASS.computeIfAbsent(t, k -> {
            BdlReader.Model m = models.get(Slot.HULL);
            if (m == null || !starshroomShape(m)) return List.of();
            float scale = fitScale(m, Slot.HULL.pixels) * (1f + 0.1f * (t - 1));
            float[] b = bounds(m);
            float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
            List<PartModel> out = new ArrayList<>();
            var tris = m.shapes().get(STAR_GLASS).tris();
            for (int from = 0; from < tris.size(); from += MAX_QUADS) {
                var chunk = tris.subList(from, Math.min(tris.size(), from + MAX_QUADS));
                out.add(new PartModel(quads(chunk, scale, cx, cy, cz), WHITE_4X4, 0xFFFFFFFF));
            }
            return out;
        });
    }

    private static final Map<Integer, List<PartModel>> CACHE_GLASS = new ConcurrentHashMap<>();

    private static long key(float[] p) {
        return ((long) Math.round(p[0] * 4) & 0x1FFFFF) << 42 | ((long) Math.round(p[1] * 4) & 0x1FFFFF) << 21
                | ((long) Math.round(p[2] * 4) & 0x1FFFFF);
    }

    /** The triangle's normal times its area, turned to face away from the model's centre. */
    private static float[] faceNormal(BdlReader.Tri t, float cx, float cy, float cz) {
        float[] a = t.pos()[0], b = t.pos()[1], c = t.pos()[2];
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float[] n = {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
        float mx = (a[0] + b[0] + c[0]) / 3 - cx, my = (a[1] + b[1] + c[1]) / 3 - cy, mz = (a[2] + b[2] + c[2]) / 3 - cz;
        if (n[0] * mx + n[1] * my + n[2] * mz < 0) for (int i = 0; i < 3; i++) n[i] = -n[i];
        return n;
    }

    /** RGBA (as the wire's colours: 0xRRGGBBAA) of a base colour lit by a key light, a fill and a highlight. */
    static int shade(float[] n, int rgb, boolean glossy) {
        return shade(n, rgb, glossy, 0xFF);
    }

    static int shade(float[] n, int rgb, boolean glossy, int alpha) {
        float lit = 0.8f, spec = 0f;
        if (n != null) {
            float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len > 1e-6f) {
                float x = n[0] / len, y = n[1] / len, z = n[2] / len;
                float key = Math.max(0f, x * 0.40f + y * 0.80f + z * 0.45f) / 1.0f;
                float fill = Math.max(0f, -x * 0.5f + y * 0.1f - z * 0.4f);
                lit = 0.42f + 0.62f * key + 0.14f * fill;
                if (glossy) {
                    float hx = 0.40f, hy = 0.95f, hz = 0.75f, hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                    float h = Math.max(0f, (x * hx + y * hy + z * hz) / hl);
                    spec = (float) Math.pow(h, 28) * 0.75f;
                }
            }
        }
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, bl = rgb & 0xFF;
        int R = Math.min(255, Math.round(r * lit + 255 * spec));
        int G = Math.min(255, Math.round(g * lit + 255 * spec));
        int B = Math.min(255, Math.round(bl * lit + 255 * spec));
        return R << 24 | G << 16 | B << 8 | alpha;
    }

    /**
     * The hull's solid skin as triangles (nine floats each, ship pixels, the same axes and scale the
     * drawn pieces have): the Starshroom's cap, stem, rim and grooves (not its glass or cockpit),
     * or a box of the cube's size where the model is missing.
     */
    public static List<float[]> hullTriangles(int tier) {
        int t = Math.max(1, Math.min(ShipModules.MAX_TIER, tier));
        float grow = 1f + 0.1f * (t - 1);
        BdlReader.Model m = models.get(Slot.HULL);
        List<float[]> out = new ArrayList<>();
        if (m != null && starshroomShape(m)) {
            float scale = fitScale(m, Slot.HULL.pixels) * grow;
            float[] b = bounds(m);
            float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
            for (int s = 0; s < m.shapes().size(); s++) {
                if (!STAR_OUTSIDE[s] || s == STAR_GLASS) continue;
                for (BdlReader.Tri tri : m.shapes().get(s).tris()) {
                    float[] f = new float[9];
                    for (int i = 0; i < 3; i++) {
                        float[] p = tri.pos()[i];
                        f[3 * i] = (p[0] - cx) * scale;
                        f[3 * i + 1] = (p[1] - cy) * scale;
                        f[3 * i + 2] = (p[2] - cz) * scale;
                    }
                    out.add(f);
                }
            }
            return out;
        }
        float h = Slot.HULL.pixels * grow / 2;
        float[][] c = new float[8][];
        for (int i = 0; i < 8; i++) c[i] = new float[] {(i & 1) == 0 ? -h : h, (i & 2) == 0 ? -h : h, (i & 4) == 0 ? -h : h};
        int[][] quads = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        for (int[] q : quads) {
            out.add(new float[] {c[q[0]][0], c[q[0]][1], c[q[0]][2], c[q[1]][0], c[q[1]][1], c[q[1]][2], c[q[2]][0], c[q[2]][1], c[q[2]][2]});
            out.add(new float[] {c[q[0]][0], c[q[0]][1], c[q[0]][2], c[q[2]][0], c[q[2]][1], c[q[2]][2], c[q[3]][0], c[q[3]][1], c[q[3]][2]});
        }
        return out;
    }

    private static boolean starshroomShape(BdlReader.Model m) {
        if (m.shapes().size() != STAR_TEXTURE.length) return false;
        for (int i = 0; i < STAR_TEXTURE.length; i++) if (m.shapes().get(i).texture() != STAR_TEXTURE[i]) return false;
        return true;
    }

    // ---- the landing pad ----

    /** A pad's radius and thickness, pixels (16 a block). */
    public static final float PAD_RADIUS = 40f, PAD_THICK = 4f;
    private static final int PAD_SIDES = 16;
    private static volatile PartModel padPart;

    /** The pad: a flat disc, its top marked with a ring, an H and lights; centred, y up, its top at +y. */
    public static PartModel pad() {
        PartModel p = padPart;
        if (p == null) padPart = p = buildPad();
        return p;
    }

    /** The pad's solid skin as triangles (nine floats each, pixels). */
    public static List<float[]> padTriangles() {
        List<float[]> out = new ArrayList<>();
        float top = PAD_THICK / 2, bottom = -PAD_THICK / 2;
        for (int i = 0; i < PAD_SIDES; i++) {
            float a0 = (float) (2 * Math.PI * i / PAD_SIDES), a1 = (float) (2 * Math.PI * (i + 1) / PAD_SIDES);
            float x0 = PAD_RADIUS * (float) Math.cos(a0), z0 = PAD_RADIUS * (float) Math.sin(a0);
            float x1 = PAD_RADIUS * (float) Math.cos(a1), z1 = PAD_RADIUS * (float) Math.sin(a1);
            out.add(new float[] {0, top, 0, x0, top, z0, x1, top, z1});
            out.add(new float[] {0, bottom, 0, x1, bottom, z1, x0, bottom, z0});
            out.add(new float[] {x0, top, z0, x0, bottom, z0, x1, bottom, z1});
            out.add(new float[] {x0, top, z0, x1, bottom, z1, x1, top, z1});
        }
        return out;
    }

    private static PartModel buildPad() {
        int size = 64;
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                double dx = (x + 0.5 - size / 2.0) / (size / 2.0), dz = (y + 0.5 - size / 2.0) / (size / 2.0);
                double r = Math.sqrt(dx * dx + dz * dz);
                int c = 0xFF3C424F; // steel
                if (((x / 4) + (y / 4)) % 2 == 0) c = 0xFF444B5A;
                if (r > 0.8 && r < 0.9) c = 0xFFF2C230; // the ring
                if (r >= 0.9) c = 0xFF2A2F3A;
                // the H: two bars and a crossbar
                boolean bar = (Math.abs(dx) > 0.26 && Math.abs(dx) < 0.4 && Math.abs(dz) < 0.5)
                        || (Math.abs(dx) <= 0.4 && Math.abs(dz) < 0.07);
                if (bar && r < 0.75) c = 0xFFF5F5F0;
                // lights on the ring
                for (int k = 0; k < 8; k++) {
                    double a = Math.PI * k / 4, lx = 0.85 * Math.cos(a), lz = 0.85 * Math.sin(a);
                    if (Math.abs(dx - lx) < 0.06 && Math.abs(dz - lz) < 0.06) c = 0xFF66E0FF;
                }
                px[y * size + x] = c;
            }
        List<EntityWire.Quad> quads = new ArrayList<>();
        float top = PAD_THICK / 2, bottom = -PAD_THICK / 2;
        float edgeU = 0.02f, edgeV = 0.02f;
        for (int i = 0; i < PAD_SIDES; i++) {
            float a0 = (float) (2 * Math.PI * i / PAD_SIDES), a1 = (float) (2 * Math.PI * (i + 1) / PAD_SIDES);
            float x0 = PAD_RADIUS * (float) Math.cos(a0), z0 = PAD_RADIUS * (float) Math.sin(a0);
            float x1 = PAD_RADIUS * (float) Math.cos(a1), z1 = PAD_RADIUS * (float) Math.sin(a1);
            float[][] c = {{0, top, 0}, {x0, top, z0}, {x1, top, z1}, {x1, top, z1}};
            float[][] uv = {{0.5f, 0.5f}, {0.5f + x0 / (2 * PAD_RADIUS), 0.5f + z0 / (2 * PAD_RADIUS)},
                    {0.5f + x1 / (2 * PAD_RADIUS), 0.5f + z1 / (2 * PAD_RADIUS)}, {0.5f + x1 / (2 * PAD_RADIUS), 0.5f + z1 / (2 * PAD_RADIUS)}};
            quads.add(new EntityWire.Quad(c, uv, 0xFFFFFFFF));
            quads.add(new EntityWire.Quad(new float[][] {c[0], c[2], c[1], c[1]}, new float[][] {uv[0], uv[2], uv[1], uv[1]}, 0xFFFFFFFF));
            float[][] s = {{x0, top, z0}, {x0, bottom, z0}, {x1, bottom, z1}, {x1, top, z1}};
            float[][] su = {{edgeU, edgeV}, {edgeU, edgeV}, {edgeU, edgeV}, {edgeU, edgeV}};
            quads.add(new EntityWire.Quad(s, su, 0xC8C8D0FF));
            quads.add(new EntityWire.Quad(new float[][] {s[3], s[2], s[1], s[0]}, su, 0xC8C8D0FF));
            float[][] b = {{0, bottom, 0}, {x1, bottom, z1}, {x0, bottom, z0}, {x0, bottom, z0}};
            quads.add(new EntityWire.Quad(b, new float[][] {{edgeU, edgeV}, {edgeU, edgeV}, {edgeU, edgeV}, {edgeU, edgeV}}, 0x909098FF));
            quads.add(new EntityWire.Quad(new float[][] {b[0], b[2], b[1], b[1]}, new float[][] {{edgeU, edgeV}, {edgeU, edgeV}, {edgeU, edgeV}, {edgeU, edgeV}}, 0x909098FF));
        }
        return new PartModel(quads, new Skin(size, size, px), 0xFFFFFFFF);
    }

    /** The Starshroom's red (its ColorChange.brk's first colour at frame 0). */
    static final int RED = 0xFFDC321E;

    /**
     * A dark skin is the Starshroom's gloss map: the game gets its red from a TEV register (the
     * colour animation's), which a textured piece cannot carry, so such skins are given that red
     * under their highlights. Light skins (the window, the stem) are left as they are.
     */
    static PartModel painted(PartModel p) {
        int[] px = p.skin().argb();
        long sum = 0;
        for (int c : px) sum += ((c >> 16 & 0xFF) + (c >> 8 & 0xFF) + (c & 0xFF)) / 3;
        if (px.length == 0 || sum / px.length > 70) return p;
        int[] out = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = Math.min(255, (RED >> 16 & 0xFF) * 9 / 10 + (c >> 16 & 0xFF) * 8 / 10);
            int g = Math.min(255, (RED >> 8 & 0xFF) * 9 / 10 + (c >> 8 & 0xFF) * 8 / 10);
            int b = Math.min(255, (RED & 0xFF) * 9 / 10 + (c & 0xFF) * 8 / 10);
            out[i] = (c & 0xFF000000) | r << 16 | g << 8 | b;
        }
        return new PartModel(p.quads(), new Skin(p.skin().width(), p.skin().height(), out), p.tint());
    }

    /** Pixels per model unit that make the model's longest side `pixels` long. */
    public static float fitScale(BdlReader.Model m, float pixels) {
        float[] b = bounds(m);
        float side = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
        return side <= 0 ? 1f : pixels / side;
    }

    /** The model's shapes as pieces: one per shape (its own texture), split to fit the wire, centred. */
    public static List<PartModel> parts(BdlReader.Model m, float scale, int tint) {
        float[] b = bounds(m);
        float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
        List<PartModel> out = new ArrayList<>();
        for (BdlReader.Shape s : m.shapes()) {
            Skin skin = s.texture() >= 0 && s.texture() < m.textures().size()
                    ? new Skin(m.textures().get(s.texture()).width(), m.textures().get(s.texture()).height(), m.textures().get(s.texture()).argb())
                    : new Skin(1, 1, WHITE);
            for (int from = 0; from < s.tris().size(); from += MAX_QUADS) {
                var chunk = s.tris().subList(from, Math.min(s.tris().size(), from + MAX_QUADS));
                out.add(new PartModel(quads(chunk, scale, cx, cy, cz), skin, tint));
            }
        }
        return out;
    }

    /** Triangles as quads (the fourth corner repeats the third), model units -> pixels. */
    public static List<EntityWire.Quad> quads(List<BdlReader.Tri> tris, float scale) {
        return quads(tris, scale, 0, 0, 0);
    }

    private static List<EntityWire.Quad> quads(List<BdlReader.Tri> tris, float scale, float cx, float cy, float cz) {
        List<EntityWire.Quad> out = new ArrayList<>(tris.size());
        for (BdlReader.Tri t : tris) {
            float[][] pos = new float[4][], uv = new float[4][];
            for (int i = 0; i < 4; i++) {
                int k = Math.min(i, 2);
                float[] p = t.pos()[k];
                pos[i] = new float[] {(p[0] - cx) * scale, (p[1] - cy) * scale, (p[2] - cz) * scale};
                uv[i] = new float[] {t.uv()[k][0], t.uv()[k][1]};
            }
            out.add(new EntityWire.Quad(pos, uv, 0xFFFFFFFF));
        }
        return out;
    }

    private static float[] bounds(BdlReader.Model m) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (BdlReader.Shape s : m.shapes())
            for (BdlReader.Tri t : s.tris())
                for (float[] p : t.pos()) {
                    any = true;
                    for (int i = 0; i < 3; i++) {
                        b[i] = Math.min(b[i], p[i]);
                        b[3 + i] = Math.max(b[3 + i], p[i]);
                    }
                }
        return any ? b : new float[6];
    }

    /** The stand-in: a cube of the slot's size in its Toad tint, on a 16x48 skin of three shades. */
    private static PartModel cube(Slot slot, float grow) {
        float k = slot.pixels * grow / 16f;
        List<EntityWire.Quad> q = new ArrayList<>();
        for (EntityWire.Quad c : EntityWire.cube(3)) {
            // Minecraft's cube is y down: flipping y mirrors it, so the corners go round the other way.
            float[][] p = new float[4][], uv = new float[4][];
            for (int i = 0; i < 4; i++) {
                float[] c0 = c.pos()[3 - i];
                p[i] = new float[] {c0[0] * k, -c0[1] * k, c0[2] * k};
                uv[i] = c.uv()[3 - i];
            }
            q.add(new EntityWire.Quad(p, uv, c.rgba()));
        }
        int[] px = new int[16 * 48];
        for (int y = 0; y < 48; y++) java.util.Arrays.fill(px, y * 16, y * 16 + 16, y < 16 ? 0xFFFFFFFF : y < 32 ? 0xFFE0E0E0 : 0xFFB0B0B0);
        return new PartModel(q, new Skin(16, 48, px), slot.cubeTint);
    }
}
