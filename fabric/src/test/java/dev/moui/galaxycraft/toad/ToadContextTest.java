package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.universe.StarType;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToadContextTest {
    @Test void homeIsTierZeroYellowAndNothingIsAsked() {
        ToadContext h = ToadContext.home();
        assertEquals(0, h.systemTier());
        assertEquals("yellow", h.star());
        assertTrue(h.visited());
        assertNull(h.missingModule());
    }

    @Test void aSystemGivesItsTierAndStarName() {
        assertEquals("red", ToadContext.forSystem(2, StarType.RED_DWARF).star());
        assertEquals("lava", ToadContext.forSystem(3, StarType.LAVA).star());
        assertEquals("blue", ToadContext.forSystem(4, StarType.BLUE).star());
        assertEquals("yellow", ToadContext.forSystem(1, StarType.YELLOW).star());
        assertEquals("yellow", ToadContext.forSystem(1, null).star());
        assertEquals(5, ToadContext.forSystem(9, StarType.BLUE).systemTier()); // clamped
        assertEquals(0, ToadContext.forSystem(-3, StarType.BLUE).systemTier());
    }

    @Test void whatAToadSaysChangesWithTierAndStar() {
        for (ToadType type : ToadType.values()) {
            Set<String> byTier = new HashSet<>();
            for (int tier = 0; tier <= 5; tier++)
                byTier.add(ToadDialogue.lines(type, ToadContext.forSystem(tier, StarType.YELLOW)).toString());
            assertEquals(6, byTier.size(), type + ": one thing to say per tier");
            assertNotEquals(ToadDialogue.lines(type, ToadContext.forSystem(3, StarType.YELLOW)).toString(),
                    ToadDialogue.lines(type, ToadContext.forSystem(3, StarType.LAVA)).toString(), type + ": the star shows");
            assertNotEquals(ToadDialogue.lines(type, ToadContext.forSystem(3, StarType.BLUE)).toString(),
                    ToadDialogue.lines(type, ToadContext.forSystem(3, StarType.RED_DWARF)).toString());
        }
    }

    @Test void homeSaysTheTierZeroLineOnly() {
        var lines = ToadDialogue.lines(ToadType.GUIDE, ToadContext.home());
        assertEquals(1, lines.size());
        assertEquals("toad.dialogue.guide.tier.0", lines.get(0).key());
    }
}
