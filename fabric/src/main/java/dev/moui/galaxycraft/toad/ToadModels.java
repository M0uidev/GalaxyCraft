package dev.moui.galaxycraft.toad;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.ship.model.BdlReader;
import dev.moui.galaxycraft.ship.model.Rarc;
import dev.moui.galaxycraft.ship.model.ShipModels;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Toad's pieces: SMG2's own Kinopio (Kinopio.arc, copied by the launcher from the player's disc
 * next to the ship's), in its bind pose, as the quads and skins {@link ShipModels} sends to the game.
 * Centred like the ship's pieces (the caller lifts it by half its height); a box of the same size
 * stands in where the model is missing or unreadable.
 */
public final class ToadModels {
    /** The Toad's height in pixels (16 a block): a block and a half. */
    public static final float HEIGHT_PX = 24f;
    /** The stand-in's tint, RGBA: a Toad's orange-ish cap. */
    private static final int BOX_TINT = 0xE8B040FF;
    private static final String ARC = "Kinopio.arc";

    private static volatile List<ShipModels.PartModel> parts;

    private ToadModels() {}

    /** Reads Kinopio.arc from the launcher's ship directory; anything missing is a box. */
    public static synchronized void load(Path dir) {
        BdlReader.Model m = null;
        try {
            Path f = dir.resolve(ARC);
            if (Files.isRegularFile(f)) {
                Map<String, byte[]> files = new TreeMap<>(Rarc.read(Files.readAllBytes(f)));
                byte[] bdl = files.entrySet().stream()
                        .filter(e -> e.getKey().endsWith(".bdl") || e.getKey().endsWith(".bmd"))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                if (bdl != null) m = BdlReader.read(bdl);
            }
        } catch (IOException | IllegalArgumentException e) {
            GalaxyCraft.LOG.warn("The Toad's {} could not be read ({}); a box stands in", ARC, e.getMessage());
        }
        use(m);
    }

    /** Uses a model (null: the box). Tests. */
    static synchronized void use(BdlReader.Model m) {
        List<ShipModels.PartModel> p = null;
        if (m != null) {
            float[] y = yRange(m);
            if (y[1] > y[0]) p = ShipModels.parts(m, HEIGHT_PX / (y[1] - y[0]), 0xFFFFFFFF);
        }
        parts = p == null || p.isEmpty() ? box() : p;
    }

    public static List<ShipModels.PartModel> parts() {
        List<ShipModels.PartModel> p = parts;
        if (p == null) {
            use(null);
            p = parts;
        }
        return p;
    }

    private static float[] yRange(BdlReader.Model m) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (BdlReader.Shape s : m.shapes())
            for (BdlReader.Tri t : s.tris())
                for (float[] p : t.pos()) {
                    lo = Math.min(lo, p[1]);
                    hi = Math.max(hi, p[1]);
                }
        return new float[] {lo, hi};
    }

    /** A box as wide as a Toad (a third of its height) and as tall as one. */
    private static List<ShipModels.PartModel> box() {
        float w = 1f / 3, h = 1f;
        float[][] c = new float[8][];
        for (int i = 0; i < 8; i++) c[i] = new float[] {(i & 1) == 0 ? -w : w, (i & 2) == 0 ? 0 : h, (i & 4) == 0 ? -w : w};
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}};
        List<BdlReader.Tri> tris = new ArrayList<>();
        for (int[] f : faces) {
            tris.add(new BdlReader.Tri(new float[][] {c[f[0]], c[f[1]], c[f[2]]}, uv));
            tris.add(new BdlReader.Tri(new float[][] {c[f[0]], c[f[2]], c[f[3]]}, uv));
        }
        int[] white = new int[16];
        java.util.Arrays.fill(white, 0xFFFFFFFF);
        BdlReader.Model m = new BdlReader.Model(List.of(new BdlReader.Shape(0, tris)), List.of(new BdlReader.Texture(4, 4, white)));
        return ShipModels.parts(m, HEIGHT_PX / h, BOX_TINT);
    }
}
