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
 * The ship's pieces: each module's SMG2 model (from the arcs the launcher copied from the player's
 * disc) as quads and skins in the form {@link EntityWire} sends to the game, sized to the module's
 * slot, with tinted cubes where a model is missing or unsupported. Quads keep the model's own axes
 * (y up) and are centred; the caller's matrix maps pixels to the galaxy without mirroring.
 */
public final class ShipModels {
    /** Quads per piece: a display list holds at most ENT_DL_MAX bytes (1170 quads), with room to spare. */
    public static final int MAX_QUADS = 1000;
    private static final int[] WHITE = {0xFFFFFFFF};

    public enum Slot {
        HULL("MiniPlayerRocket.arc", 64f, new float[] {0, 0, 0}, 0xFFFFFFFF),
        ENGINE("MarioFaceShipJet.arc", 24f, new float[] {0, 0, -40}, 0xE03030FF),
        RUDDER("MarioFaceShipRudder.arc", 40f, new float[] {0, -16, -56}, 0xE03030FF),
        CABIN("MarioFaceShipChimney.arc", 32f, new float[] {0, -18, 6}, 0xFFFFFFFF);

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
    }

    /** The pieces of a module at a tier (1..3): its model, or a tinted cube. */
    public static List<PartModel> parts(Slot slot, int tier) {
        int t = Math.max(1, Math.min(ShipModules.MAX_TIER, tier));
        return CACHE.computeIfAbsent(slot.ordinal() * 10 + t, k -> {
            float grow = 1f + 0.1f * (t - 1);
            BdlReader.Model m = models.get(slot);
            if (m == null) return List.of(cube(slot, grow));
            List<PartModel> p = parts(m, fitScale(m, slot.pixels) * grow, 0xFFFFFFFF);
            return p.isEmpty() ? List.of(cube(slot, grow)) : p;
        });
    }

    /** Every piece of the modules a ship has. */
    public static List<Placed> layout(ShipModules m) {
        int[] tiers = {m.hull(), m.engine(), m.rudder(), m.cabin()};
        List<Placed> out = new ArrayList<>();
        for (Slot s : Slot.values()) {
            int tier = tiers[s.ordinal()];
            if (tier <= 0) continue;
            for (PartModel p : parts(s, tier)) out.add(new Placed(s, p, s.offset));
        }
        return out;
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
