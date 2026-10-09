package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WantTest {
    @Test void encodesAndDecodes() {
        for (Want w : new Want[] {Want.SPACE, Want.PLANET, Want.SILENCE, Want.track("galaxy02")})
            assertEquals(w, Want.decode(w.encode()));
    }

    @Test void junkIsSpace() {
        assertEquals(Want.SPACE, Want.decode(null));
        assertEquals(Want.SPACE, Want.decode("???"));
        assertEquals(Want.SPACE, Want.decode("track:"));
    }
}
