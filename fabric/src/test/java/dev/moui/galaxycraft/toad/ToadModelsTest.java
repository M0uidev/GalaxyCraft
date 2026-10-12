package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.ship.model.BdlReader;
import dev.moui.galaxycraft.ship.model.ShipModels;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToadModelsTest {
    @AfterEach void reset() {
        ToadModels.use(null);
    }

    static float[] yRange(List<ShipModels.PartModel> parts) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (var p : parts)
            for (var q : p.quads())
                for (float[] c : q.pos()) {
                    lo = Math.min(lo, c[1]);
                    hi = Math.max(hi, c[1]);
                }
        return new float[] {lo, hi};
    }

    @Test void aMissingModelIsABoxOfTheToadsHeight() {
        ToadModels.load(Path.of("no-such-dir"));
        var parts = ToadModels.parts();
        assertFalse(parts.isEmpty());
        float[] r = yRange(parts);
        assertEquals(ToadModels.HEIGHT_PX, r[1] - r[0], 0.01f);
    }

    @Test void aModelIsScaledToTheToadsHeightAndCentred() {
        float[][] a = {{0, 0, 0}, {1, 0, 0}, {0, 200, 0}};
        var tri = new BdlReader.Tri(a, new float[][] {{0, 0}, {1, 0}, {0, 1}});
        var tex = new BdlReader.Texture(4, 4, new int[16]);
        ToadModels.use(new BdlReader.Model(List.of(new BdlReader.Shape(0, List.of(tri))), List.of(tex)));
        float[] r = yRange(ToadModels.parts());
        assertEquals(ToadModels.HEIGHT_PX, r[1] - r[0], 0.01f);
        assertEquals(0f, r[0] + r[1], 0.01f, "centred, so the caller lifts it by half its height");
    }

    @Test void anEmptyModelFallsBackToTheBox() {
        ToadModels.use(new BdlReader.Model(List.of(), List.of()));
        assertFalse(ToadModels.parts().isEmpty());
    }
}
