package dev.moui.galaxycraft.map;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.universe.StarType;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;
import org.junit.jupiter.api.Test;

class SystemInfoTest {
    static final double U = 80;

    @Test void homeIsTierZeroAndYellow() {
        var u = new Universe(5, U);
        var home = u.star(Universe.Sector.HOME).orElseThrow();
        assertEquals(new SystemInfo(0, StarType.YELLOW), SystemInfo.of(u, home));
        assertTrue(SystemInfo.visited(null, home));
    }

    @Test void infoIsTheUniverses() {
        var u = new Universe(9, U);
        var kinds = new java.util.HashSet<StarType>();
        for (var s : u.around(UPos.ZERO, 6)) {
            var info = SystemInfo.of(u, s);
            assertEquals(u.tier(s), info.tier());
            assertEquals(u.starType(s), info.star());
            kinds.add(info.star());
        }
        assertFalse(kinds.isEmpty());
    }

    @Test void visitedSystemsAreMarked() throws Exception {
        var u = new Universe(9, U);
        var other = u.around(UPos.ZERO, 3).stream().filter(s -> !s.home()).findFirst().orElseThrow();
        assertFalse(SystemInfo.visited(null, other));
        var file = java.nio.file.Files.createTempFile("visited", ".json");
        java.nio.file.Files.delete(file);
        var v = dev.moui.galaxycraft.universe.Visited.load(file);
        assertFalse(SystemInfo.visited(v, other));
        v.mark(other.sector());
        assertTrue(SystemInfo.visited(v, other));
    }
}
