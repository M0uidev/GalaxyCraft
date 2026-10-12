package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToadDialogueTest {
    static ToadContext ctx(int tier, String star, int core, String missing, boolean visited) {
        return new ToadContext(tier, star, core, missing, visited);
    }

    @Test void neverEmptyNeverMoreThanThree() {
        for (ToadType t : ToadType.values())
            for (int tier = -1; tier <= 7; tier++)
                for (int core = -1; core <= 7; core++) {
                    var l = ToadDialogue.lines(t, ctx(tier, "yellow", core, null, false));
                    assertFalse(l.isEmpty());
                    assertTrue(l.size() <= 3);
                }
    }

    @Test void aWeakWarpCoreIsMentionedByTheGuide() {
        var l = ToadDialogue.lines(ToadType.GUIDE, ctx(3, "lava", 1, null, false));
        assertTrue(l.stream().anyMatch(x -> x.key().equals("toad.dialogue.warp_core_low")));
    }

    @Test void aMissingModuleIsNamed() {
        var l = ToadDialogue.lines(ToadType.TECHNICIAN, ctx(1, "yellow", 1, "engine", true));
        var m = l.stream().filter(x -> x.key().equals("toad.dialogue.missing_module")).findFirst();
        assertTrue(m.isPresent());
        assertEquals("engine", m.get().args().get(0));
    }

    @Test void theStarIsExplainedOnlyWhereItMatters() {
        var yellow = ToadDialogue.lines(ToadType.GUIDE, ctx(0, "yellow", 0, null, true));
        assertTrue(yellow.stream().noneMatch(x -> x.key().startsWith("toad.dialogue.star.")));
        var lava = ToadDialogue.lines(ToadType.GUIDE, ctx(2, "lava", 3, null, true));
        assertTrue(lava.stream().anyMatch(x -> x.key().equals("toad.dialogue.star.lava")));
    }

    @Test void sameContextSameWords() {
        var c = ctx(2, "red", 1, "cabin", false);
        assertEquals(ToadDialogue.lines(ToadType.MINER, c), ToadDialogue.lines(ToadType.MINER, c));
    }
}
