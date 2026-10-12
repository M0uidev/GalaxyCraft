package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToadVoicePlanTest {
    @Test void spacesAndPunctuationAreSilent() {
        for (char c : new char[] {' ', '.', ',', '!', '?', '-', '\n'}) assertFalse(ToadVoicePlan.blip(c, 0), "'" + c + "'");
    }

    @Test void everySecondLetterSounds() {
        String text = "Welcome!";
        int sounds = 0;
        for (int i = 0; i < text.length(); i++) if (ToadVoicePlan.blip(text.charAt(i), i)) sounds++;
        assertEquals(4, sounds); // W l o e (indexes 0, 2, 4, 6)
        assertFalse(ToadVoicePlan.blip('e', 1));
    }

    @Test void pitchStaysInRangeAndIsStable() {
        for (ToadType t : ToadType.values())
            for (char c = 32; c < 127; c++) {
                float p = ToadVoicePlan.pitch(t, c);
                assertTrue(p >= ToadVoicePlan.MIN_PITCH && p <= ToadVoicePlan.MAX_PITCH, t + " " + c + " " + p);
                assertEquals(p, ToadVoicePlan.pitch(t, c));
            }
        assertNotEquals(ToadVoicePlan.pitch(ToadType.MINER, 'a'), ToadVoicePlan.pitch(ToadType.EXPLORER, 'a'));
    }
}
