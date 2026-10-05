package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class FarPlanetTest {
    static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    static FarPlanet meshed() {
        FarPlanet f = new FarPlanet(new Vector3d(800, 0, -80), 48, 80);
        f.mesh(6, PlanetLod.coarse(LodSource.of(VoxelPlanet.standard()), 6, 80));
        return f;
    }

    @Test void itIsAPlanetWithNoGravityAndNoChunksThenItsSixFaces() {
        FarPlanet f = meshed();
        f.update(1, 2);
        PlanetSession.Msg m = f.peek();
        assertEquals(Layout.MSG_PLANET, m.type());
        ByteBuffer b = le(m.payload());
        assertEquals(f.id(), b.getInt());
        assertEquals(800f, b.getFloat());
        b.getFloat();
        assertEquals(-80f, b.getFloat());
        assertEquals(48f * 80, b.getFloat(), 1e-3, "surface");
        assertEquals(0f, b.getFloat(), "no gravity");
        assertEquals(0, b.getInt(), "no chunks");
        f.sent();
        for (int face = 0; face < 6; face++) {
            m = f.peek();
            assertEquals(Layout.MSG_CHUNK, m.type());
            int slot = le(m.payload()).getInt();
            assertEquals(f.id(), slot >>> 24);
            assertEquals(PlanetSession.FAR_VIEW | face, slot & 0xFFFFFF);
            f.sent();
        }
        assertNull(f.peek());
        assertEquals(0, f.queued());
    }

    @Test void aNewMeshSendsTheFacesAgainNewer() {
        FarPlanet f = meshed();
        f.update(1, 2);
        while (f.peek() != null) f.sent();
        f.mesh(3, PlanetLod.coarse(LodSource.of(VoxelPlanet.standard()), 3, 80));
        assertEquals(3, f.patches());
        assertEquals(6, f.queued());
        int version = le(f.peek().payload()).getInt(4);
        assertEquals(2, version);
    }

    @Test void aNewSceneGetsItAllAgain() {
        FarPlanet f = meshed();
        f.update(1, 2);
        while (f.peek() != null) f.sent();
        f.update(3, 2);
        assertEquals(7, f.queued());
    }

    @Test void removedItIsGoneAndItsIdFree() {
        FarPlanet f = meshed();
        f.update(1, 2);
        while (f.peek() != null) f.sent();
        int id = f.id();
        f.remove();
        PlanetSession.Msg m = f.peek();
        assertEquals(Layout.MSG_PLANET, m.type());
        assertEquals(id, le(m.payload()).getInt());
        assertEquals(PlanetSession.PLANET_GONE, ByteBuffer.wrap(m.payload()).getInt(36));
        f.sent();
        assertEquals(0, f.queued());
        assertFalse(PlanetSession.idUsed(id));
    }
}
