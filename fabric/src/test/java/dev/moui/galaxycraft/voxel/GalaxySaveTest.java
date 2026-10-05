package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GalaxySaveTest {
    @TempDir Path world;

    @Test void aNewWorldHasNoGalaxyYet() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        assertTrue(g.isNew());
        assertTrue(g.spot().isEmpty());
        g.markMade();
        assertFalse(g.isNew());
        assertTrue(Files.isDirectory(g.planets()));
        assertEquals(world.resolve("galaxycraft/planets"), g.planets());
    }

    @Test void theSpotComesBack() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        GalaxySave.Spot spot = new GalaxySave.Spot(2, 0.5, -0.25, 0.75, 91.5f, -12f);
        g.writeSpot(spot);
        assertEquals(spot, GalaxySave.of(world).spot().orElseThrow());
    }

    @Test void aBrokenSpotIsNoSpot() throws Exception {
        GalaxySave g = GalaxySave.of(world);
        g.markMade();
        Files.writeString(world.resolve("galaxycraft/player.json"), "{not json");
        assertTrue(g.spot().isEmpty());
        Files.writeString(world.resolve("galaxycraft/player.json"), "{\"planet\": 0, \"dx\": 0, \"dy\": 0, \"dz\": 0}");
        assertTrue(g.spot().isEmpty()); // no direction: nowhere to stand
    }
}
