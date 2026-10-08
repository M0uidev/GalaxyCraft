package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GalaxyCatalogTest {
    static final List<String> LAND = List.of("minecraft:plains", "minecraft:desert", "minecraft:forest");
    static final double U = 80;

    static GalaxyCatalog.Options opts(int count, GalaxyCatalog.Spacing sp, long seed) {
        return new GalaxyCatalog.Options(count, 32, 96, GalaxyCatalog.First.generated("minecraft:plains", 32), sp, seed);
    }

    @Test void theSameOptionsAndSeedMakeTheSameGalaxy() {
        var a = GalaxyCatalog.make(opts(12, GalaxyCatalog.Spacing.NORMAL, 5), 32, LAND, U);
        var b = GalaxyCatalog.make(opts(12, GalaxyCatalog.Spacing.NORMAL, 5), 32, LAND, U);
        var c = GalaxyCatalog.make(opts(12, GalaxyCatalog.Spacing.NORMAL, 6), 32, LAND, U);
        assertEquals(a, b);
        assertNotEquals(a.entries(), c.entries());
    }

    @Test void theFirstPlanetIsAtTheOriginAsChosen() {
        var r = GalaxyCatalog.make(opts(5, GalaxyCatalog.Spacing.NEAR, 1), 32, LAND, U);
        GalaxyCatalog.Entry e = r.entries().getFirst();
        assertEquals(0, e.index());
        assertEquals(0, e.center().length(), 1e-9);
        assertEquals(32, e.radius());
        assertEquals(GalaxyCatalog.Kind.GENERATED, e.kind());
        assertEquals("minecraft:plains", e.biome());
    }

    @Test void aBlueprintFirstKeepsItsNameAndRadius() {
        var o = new GalaxyCatalog.Options(3, 32, 96, GalaxyCatalog.First.blueprint("Mine"), GalaxyCatalog.Spacing.NORMAL, 1);
        GalaxyCatalog.Entry e = GalaxyCatalog.make(o, 70, LAND, U).entries().getFirst();
        assertEquals(GalaxyCatalog.Kind.BLUEPRINT, e.kind());
        assertEquals("Mine", e.blueprint());
        assertEquals(70, e.radius());
    }

    @Test void planetsKeepTheirSpacingAndSizes() {
        for (GalaxyCatalog.Spacing sp : GalaxyCatalog.Spacing.values()) {
            var r = GalaxyCatalog.make(opts(20, sp, 9), 32, LAND, U);
            assertEquals(20, r.placed());
            List<GalaxyCatalog.Entry> es = r.entries();
            for (int i = 0; i < es.size(); i++) {
                GalaxyCatalog.Entry a = es.get(i);
                assertEquals(i, a.index());
                if (i > 0) {
                    assertTrue(a.radius() >= 32 && a.radius() <= 96, "radius " + a.radius());
                    assertTrue(LAND.contains(a.biome()));
                }
                for (int j = 0; j < i; j++) {
                    GalaxyCatalog.Entry b = es.get(j);
                    double need = (PlanetSession.gravityRadius(a.radius()) + PlanetSession.gravityRadius(b.radius()) + sp.blocks(1)) * U;
                    assertTrue(a.center().distance(b.center()) >= need - 1e-6, sp + ": " + i + " and " + j + " too close");
                }
            }
        }
    }

    @Test void sixtyFourBigFarPlanetsStayWithinFiveThousandBlocks() {
        var o = new GalaxyCatalog.Options(64, 200, 256, GalaxyCatalog.First.generated("random", 256), GalaxyCatalog.Spacing.FAR, 3);
        var r = GalaxyCatalog.make(o, 256, LAND, U);
        assertEquals(64, r.placed());
        for (GalaxyCatalog.Entry e : r.entries()) assertTrue(e.center().length() / U < 5000, "at " + e.center().length() / U);
    }

    @Test void optionsOutOfRangeAreBroughtIn() {
        var o = new GalaxyCatalog.Options(0, 300, 10, GalaxyCatalog.First.generated("random", 5), GalaxyCatalog.Spacing.NORMAL, 1).clamp();
        assertEquals(1, o.count());
        assertTrue(o.minRadius() >= 16 && o.maxRadius() <= 256 && o.minRadius() <= o.maxRadius());
        assertEquals(16, o.first().radius());
        assertEquals(64, new GalaxyCatalog.Options(99, 32, 64, o.first(), GalaxyCatalog.Spacing.NORMAL, 1).clamp().count());
    }

    @Test void anAddedPlanetTakesTheNextFreeIndex() {
        var es = GalaxyCatalog.make(opts(3, GalaxyCatalog.Spacing.NORMAL, 1), 32, LAND, U).entries();
        var e = GalaxyCatalog.added(es, new org.joml.Vector3d(1, 2, 3), 40, GalaxyCatalog.Kind.BLUEPRINT, null, "X", 0);
        assertEquals(3, e.index());
        assertEquals("X", e.blueprint());
    }

    @Test void spacingWidensWithTheLayout() {
        assertEquals(96, GalaxyCatalog.Spacing.NORMAL.blocks(1));
        assertEquals(600, GalaxyCatalog.Spacing.NORMAL.blocks(2));
        assertEquals(150, GalaxyCatalog.Spacing.NEAR.blocks(2));
        assertEquals(1500, GalaxyCatalog.Spacing.FAR.blocks(2));
        assertEquals(2, GalaxyCatalog.LAYOUT);
        var old = GalaxyCatalog.make(opts(12, GalaxyCatalog.Spacing.NORMAL, 5), 32, LAND, U);
        assertEquals(old, GalaxyCatalog.make(opts(12, GalaxyCatalog.Spacing.NORMAL, 5), 32, LAND, U, 1), "layout 1 is today's");
        var wide = GalaxyCatalog.make(opts(12, GalaxyCatalog.Spacing.NORMAL, 5), 32, LAND, U, 2);
        double nearest = Double.MAX_VALUE;
        for (var a : wide.entries())
            for (var b : wide.entries())
                if (a != b) nearest = Math.min(nearest, a.center().distance(b.center()) / U);
        assertTrue(nearest > 600, "nearest centers " + nearest + " blocks apart");
    }

    @Test void aBigGalaxyFarApartStillFits() {
        var o = new GalaxyCatalog.Options(64, 32, 128, GalaxyCatalog.First.generated("random", 64), GalaxyCatalog.Spacing.FAR, 3);
        var r = GalaxyCatalog.make(o, 64, LAND, U, 2);
        assertEquals(64, r.entries().size(), r.entries().size() + " of 64 placed");
    }
}
