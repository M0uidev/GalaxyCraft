package dev.moui.galaxycraft.shadow;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.CubeBlocks;
import dev.moui.galaxycraft.voxel.FlatGrid;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.Station;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class StationShadowTest {
    static final char STONE = (char) CubeBlocks.INSTANCE.id(Material.STONE);

    static Station station() {
        return Station.create("cafe0005", "S", new Quaterniond().rotateY(0.4), CubeBlocks.INSTANCE, STONE, STONE);
    }

    @Test void aStationCellKeepsItsShadowPlaceAcrossARegrow() {
        Station s = station();
        ShadowMap before = ShadowMap.of(s.planet, "station-cafe0005");
        int c = s.grid().cellOf(3, 0, -2);
        int x = before.x(c), y = before.y(c), z = before.z(c);
        int edge = s.grid().cellOf(s.grid().ox + 1, 0, 0);
        s.planet.set(edge, STONE);
        assertTrue(s.changed(edge));
        s.regrow();
        ShadowMap after = ShadowMap.of(s.planet, "station-cafe0005");
        int c2 = s.grid().cellOf(3, 0, -2);
        assertEquals(x, after.x(c2));
        assertEquals(y, after.y(c2));
        assertEquals(z, after.z(c2));
        assertEquals(c2, after.cell(x, y, z));
        assertEquals(ShadowMap.STATION_Y, after.y(s.grid().cellOf(0, 0, 0)));
    }

    @Test void theWholeStationFitsTheShadowDimension() {
        FlatGrid g = new FlatGrid(288, 128, -144, -48, -144, new Quaterniond());
        ShadowMap m = ShadowMap.of(dev.moui.galaxycraft.voxel.VoxelPlanet.flat(g, new char[g.cellCount()], CubeBlocks.INSTANCE), "station-x");
        int lo = g.index(0, 0, 0, 0), hi = g.index(0, 287, 287, 127);
        assertTrue(m.x(lo) > 0 && m.x(hi) < ShadowMap.STRIDE);
        assertTrue(m.inStrip(m.z(lo)) && m.inStrip(m.z(hi)));
        assertEquals(0, m.y(lo));
        assertEquals(127, m.y(hi));
    }

    @Test void aStationHasNoHalos() {
        Station s = station();
        ShadowMap m = ShadowMap.of(s.planet, "station-cafe0005");
        int corner = s.grid().index(0, 0, 0, 0);
        assertEquals(0, m.halos(corner).length);
        assertNull(m.wrap(m.x(corner) - 1.5, m.y(corner), m.z(corner)));
    }
}
