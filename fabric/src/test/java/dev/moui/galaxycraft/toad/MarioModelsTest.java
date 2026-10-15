package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.moui.galaxycraft.net.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MarioModelsTest {
    @Test
    void animationFollowsTheState() {
        int g = Protocol.F_GROUND;
        assertEquals("Wait", MarioModels.animation(g, 0f));
        assertEquals("Walk", MarioModels.animation(g, 0.3f));
        assertEquals("Run", MarioModels.animation(g | Protocol.F_SPRINT, 1f));
        assertEquals("SquatWait", MarioModels.animation(g | Protocol.F_SNEAK, 0f));
        assertEquals("SquatWalk", MarioModels.animation(g | Protocol.F_SNEAK, 0.4f));
        assertEquals("Fall", MarioModels.animation(0, 0.5f));
        assertEquals("Fall", MarioModels.animation(Protocol.F_GLIDE, 0f));
        assertEquals("SwimWait", MarioModels.animation(Protocol.F_SWIM, 0f));
        assertEquals("RideWait", MarioModels.animation(Protocol.F_RIDE | g, 1f));
        assertEquals("RideWait", MarioModels.animation(Protocol.F_SHIP, 0f));
    }

    @Test
    void missingArcsGiveNullNotACrash(@org.junit.jupiter.api.io.TempDir Path empty) {
        assertNull(MarioModels.read(empty));
    }

    @Test
    void theDiscsMarioIsReadWhenThereIsOne() {
        Path dir = Path.of(System.getProperty("user.home"), ".local/share/galaxycraft/ship");
        assumeTrue(Files.isRegularFile(dir.resolve("Mario.arc")) && Files.isRegularFile(dir.resolve("MarioAnime.arc")));
        ToadModels.Loaded m = MarioModels.read(dir);
        assertNotNull(m);
        assertEquals(30, m.rest().length);
        assertTrue(m.parts().size() > 10);
        for (String a : new String[] {"Wait", "Walk", "Run", "SquatWait", "SquatWalk", "Fall", "SwimWait", "SwimFlutterSurface", "RideWait"})
            assertNotNull(m.anims().get(a), a);
    }
}
