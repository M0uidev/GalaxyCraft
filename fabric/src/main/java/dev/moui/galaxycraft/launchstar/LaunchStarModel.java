package dev.moui.galaxycraft.launchstar;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.ship.model.BdlReader;
import dev.moui.galaxycraft.ship.model.Rarc;
import dev.moui.galaxycraft.ship.model.ShipModels;
import dev.moui.galaxycraft.ship.model.ShipModels.PartModel;
import dev.moui.galaxycraft.view.EntityWire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Launch Star's look: SMG2's own SuperSpinDriver (copied by the launcher from the player's
 * disc into <data>/ship), as rigid pieces in pixels, centred, y up, about 4 blocks across. A
 * flat yellow slab stands in where the file is missing or unsupported.
 */
public final class LaunchStarModel {
    /** The star's longest side, pixels (16 a block). */
    public static final float PIXELS = 64f;
    private static volatile List<PartModel> parts;

    private LaunchStarModel() {}

    /** Reads the arc from dir; anything wrong leaves the slab. */
    public static synchronized void load(Path dir) {
        parts = null;
        Path f = dir.resolve("SuperSpinDriver.arc");
        try {
            if (!Files.isRegularFile(f)) return;
            Map<String, byte[]> files = new TreeMap<>(Rarc.read(Files.readAllBytes(f)));
            byte[] bdl = files.entrySet().stream().filter(e -> e.getKey().endsWith(".bdl")).map(Map.Entry::getValue).findFirst().orElse(null);
            if (bdl == null) return;
            BdlReader.Model m = BdlReader.read(bdl);
            parts = ShipModels.parts(m, ShipModels.fitScale(m, PIXELS), 0xFFFFFFFF);
        } catch (IOException | RuntimeException e) {
            GalaxyCraft.LOG.warn("The Launch Star's model could not be read ({}); a slab stands in", e.getMessage());
        }
    }

    /** The star's pieces (made once per load). */
    public static List<PartModel> parts() {
        List<PartModel> p = parts;
        if (p == null || p.isEmpty()) {
            p = List.of(slab());
            parts = p;
        }
        return p;
    }

    private static PartModel slab() {
        float k = PIXELS / 16f;
        List<EntityWire.Quad> q = new ArrayList<>();
        for (EntityWire.Quad c : EntityWire.cube(3)) {
            float[][] p = new float[4][], uv = new float[4][];
            for (int i = 0; i < 4; i++) {
                float[] c0 = c.pos()[3 - i];
                p[i] = new float[] {c0[0] * k, -c0[1] * k * 0.15f, c0[2] * k};
                uv[i] = c.uv()[3 - i];
            }
            q.add(new EntityWire.Quad(p, uv, c.rgba()));
        }
        int[] px = new int[16 * 48];
        java.util.Arrays.fill(px, 0xFFFFD21E);
        return new PartModel(q, new ShipModels.Skin(16, 48, px), 0xFFFFFFFF);
    }
}
