package dev.moui.galaxycraft.ship.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.model.ShipModels.Placed;
import dev.moui.galaxycraft.ship.model.ShipModels.Slot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShipModelsTest {
    private static BdlReader.Tri tri(float x) {
        return new BdlReader.Tri(new float[][] {{x, 0, 0}, {x + 89, 0, 0}, {x, 89, 0}}, new float[][] {{0, 0}, {1, 0}, {0, 1}});
    }

    @Test
    void withoutArcsEverySlotIsATintedCube(@TempDir Path none) {
        ShipModels.load(none.resolve("missing"));
        for (Slot s : Slot.values()) {
            List<ShipModels.PartModel> parts = ShipModels.parts(s, 1);
            assertFalse(parts.isEmpty(), s.name());
            for (var p : parts) {
                assertFalse(p.quads().isEmpty(), s.name());
                assertNotNull(p.skin());
            }
        }
    }

    @Test
    void layoutHasPiecesOfEveryPresentModuleAndOnlyThose(@TempDir Path none) {
        ShipModels.load(none.resolve("missing"));
        assertEquals(EnumSet.of(Slot.ENGINE, Slot.CABIN), slots(ShipModels.layout(new ShipModules(0, 1, 0, 1))));
        assertEquals(EnumSet.allOf(Slot.class), slots(ShipModels.layout(new ShipModules(1, 2, 3, 1))));
    }

    private static Set<Slot> slots(List<Placed> l) {
        Set<Slot> s = EnumSet.noneOf(Slot.class);
        for (Placed p : l) s.add(p.slot());
        return s;
    }

    @Test
    void aBdlTriangleBecomesADegenerateQuadScaledToPixels() {
        var quads = ShipModels.quads(List.of(tri(0)), 16f / 89f);
        assertEquals(1, quads.size());
        float[][] p = quads.get(0).pos();
        assertEquals(4, p.length);
        assertArrayEquals(p[2], p[3], "the 4th corner repeats the 3rd");
        assertEquals(16f, p[1][0], 0.01f, "89 units = 16 pixels = 1 block");
        assertEquals(16f, p[2][1], 0.01f);
    }

    @Test
    void aBigShapeIsSplitIntoPiecesTheWireCanCarry() {
        List<BdlReader.Tri> tris = new ArrayList<>();
        for (int i = 0; i < 2500; i++) tris.add(tri(i));
        var m = new BdlReader.Model(List.of(new BdlReader.Shape(-1, tris)), List.of());
        var parts = ShipModels.parts(m, 1f, 0xFFFFFFFF);
        assertEquals(3, parts.size());
        for (var p : parts) assertTrue(p.quads().size() <= ShipModels.MAX_QUADS);
    }

    @Test
    void aModelIsSizedToItsSlotNotToItsOwnUnits() {
        var huge = new BdlReader.Model(List.of(new BdlReader.Shape(-1, List.of(new BdlReader.Tri(
                new float[][] {{0, 0, 0}, {2000, 0, 0}, {0, 100, 0}}, new float[][] {{0, 0}, {1, 0}, {0, 1}})))), List.of());
        float s = ShipModels.fitScale(huge, 64f);
        assertEquals(64f / 2000f, s, 1e-6f, "the longest side fits 64 pixels");
    }

    @Test
    void anArcWithAModelIsUsedWithItsTexture(@TempDir Path dir) throws Exception {
        byte[] arc = BdlReaderTest.Fixture.rarcWith("x.bdl", BdlReaderTest.Fixture.oneTriangle());
        Files.write(dir.resolve("MiniPlayerRocket.arc"), arc);
        ShipModels.load(dir);
        var parts = ShipModels.parts(Slot.HULL, 1);
        assertEquals(1, parts.size());
        assertEquals(4, parts.get(0).skin().width());
        assertEquals(0xFFFF0000, parts.get(0).skin().argb()[0]);
    }

    @Test
    void aDamagedArcFallsBackToCubesInsteadOfThrowing(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("MiniPlayerRocket.arc"), new byte[100]);
        ShipModels.load(dir);
        assertFalse(ShipModels.parts(Slot.HULL, 1).isEmpty());
    }

    @Test
    void higherTiersAreLarger(@TempDir Path none) {
        ShipModels.load(none.resolve("missing"));
        float lo = extent(ShipModels.parts(Slot.ENGINE, 1)), hi = extent(ShipModels.parts(Slot.ENGINE, 3));
        assertTrue(hi > lo);
    }

    private static float extent(List<ShipModels.PartModel> parts) {
        float m = 0;
        for (var p : parts) for (var q : p.quads()) for (float[] c : q.pos()) m = Math.max(m, Math.abs(c[0]));
        return m;
    }
}
