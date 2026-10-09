package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DotColorTest {
    @Test void aPlanetsDotIsItsGroundsColor() {
        assertEquals(DotColor.AUTO, DotColor.generated(null));
        assertNotEquals(DotColor.generated("minecraft:desert"), DotColor.generated("minecraft:snowy_plains"));
        assertEquals(DotColor.generated("minecraft:no_such"), DotColor.generated("minecraft:plains"), "unknown: as plains");
        assertEquals(0x123456, DotColor.blueprint(0x123456));
        assertEquals(DotColor.AUTO, DotColor.blueprint(-1), "a top without a color");
    }
}
