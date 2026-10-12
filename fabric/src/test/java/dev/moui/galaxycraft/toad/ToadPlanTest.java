package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.universe.StarType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToadPlanTest {
    @Test void theSamePlanetGetsTheSameSpots() {
        for (long seed = 1; seed < 200; seed++) {
            List<ToadPlan.Spot> a = ToadPlan.outposts("s0.0.0-3", seed, 120, 4, StarType.LAVA);
            List<ToadPlan.Spot> b = ToadPlan.outposts("s0.0.0-3", seed, 120, 4, StarType.LAVA);
            assertEquals(a.size(), b.size());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i).id(), b.get(i).id());
                assertEquals(a.get(i).type(), b.get(i).type());
                assertArrayEquals(a.get(i).dir(), b.get(i).dir());
            }
        }
    }

    @Test void tierZeroAndSmallPlanetsHaveNone() {
        for (long seed = 1; seed < 200; seed++) {
            assertTrue(ToadPlan.outposts("k", seed, 200, 0, StarType.YELLOW).isEmpty());
            assertTrue(ToadPlan.outposts("k", seed, ToadPlan.MIN_RADIUS - 1, 5, StarType.BLUE).isEmpty());
        }
    }

    @Test void tiersOnlyProduceTheirCountsAndTypes() {
        boolean lowMax = false, highMax = false, mailman = false;
        for (long seed = 1; seed < 2000; seed++) {
            for (int tier = 1; tier <= 5; tier++) {
                List<ToadPlan.Spot> s = ToadPlan.outposts("p" + seed, seed, 100, tier, StarType.YELLOW);
                if (tier <= 2) {
                    assertTrue(s.size() <= 1, "tier " + tier);
                    lowMax |= s.size() == 1;
                } else {
                    assertTrue(s.size() <= 2, "tier " + tier);
                    highMax |= s.size() == 2;
                }
                for (ToadPlan.Spot p : s) {
                    assertNotEquals(ToadType.GUIDE, p.type());
                    assertNotEquals(ToadType.TECHNICIAN, p.type());
                    if (tier <= 2) assertNotEquals(ToadType.MAILMAN, p.type());
                    mailman |= p.type() == ToadType.MAILMAN;
                    double len = Math.sqrt(p.dir()[0] * p.dir()[0] + p.dir()[1] * p.dir()[1] + p.dir()[2] * p.dir()[2]);
                    assertEquals(1.0, len, 1e-9);
                }
            }
        }
        assertTrue(lowMax && highMax && mailman, "every count and the Mailman do happen");
    }

    @Test void idsAreUniqueAndStable() {
        Set<String> ids = new HashSet<>();
        for (long seed = 1; seed < 500; seed++)
            for (ToadPlan.Spot p : ToadPlan.outposts("planet-" + seed, seed, 100, 4, StarType.BLUE))
                assertTrue(ids.add(p.id()), p.id());
        assertFalse(ids.isEmpty());
        assertTrue(ids.iterator().next().startsWith("toad-"));
    }

    @Test void theStarFavorsATypeButNeverForbidsOne() {
        int blueMiners = 0, blueExplorers = 0, dwarfMiners = 0, dwarfExplorers = 0;
        for (long seed = 1; seed < 4000; seed++) {
            for (ToadPlan.Spot p : ToadPlan.outposts("a" + seed, seed, 100, 2, StarType.BLUE)) {
                if (p.type() == ToadType.MINER) blueMiners++; else blueExplorers++;
            }
            for (ToadPlan.Spot p : ToadPlan.outposts("a" + seed, seed, 100, 2, StarType.RED_DWARF)) {
                if (p.type() == ToadType.MINER) dwarfMiners++; else dwarfExplorers++;
            }
        }
        assertTrue(blueMiners > blueExplorers && blueExplorers > 0);
        assertTrue(dwarfExplorers > dwarfMiners && dwarfMiners > 0);
    }
}
