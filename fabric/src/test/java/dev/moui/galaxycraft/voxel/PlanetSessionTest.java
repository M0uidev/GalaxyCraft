package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PlanetSessionTest {
    static List<PlanetSession.Msg> drain(PlanetSession s) {
        List<PlanetSession.Msg> out = new ArrayList<>();
        for (PlanetSession.Msg m; (m = s.peek()) != null; s.sent()) out.add(m);
        return out;
    }

    static PlanetSession spawned() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(new Vector3d(0, 0, 0), new Vector3d(0, 1, 0));
        s.update(3, 100);
        return s;
    }

    @Test void spawnSendsPlanetThenEveryChunkAboveThePlayer() {
        PlanetSession s = spawned();
        assertEquals(new Vector3d(0, 80 * (16 + 64), 0), s.center());
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(Layout.MSG_PLANET, msgs.get(0).type());
        ByteBuffer p = ByteBuffer.wrap(msgs.get(0).payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, p.getInt());
        assertEquals(6400f, p.getFloat(8));
        assertEquals(1280f, p.getFloat(16));
        assertEquals(4480f, p.getFloat(20));
        assertEquals(1 + 162, msgs.size());
        assertTrue(msgs.stream().skip(1).allMatch(m -> m.type() == Layout.MSG_CHUNK));
    }

    @Test void newSceneResendsEverything() {
        PlanetSession s = spawned();
        drain(s);
        s.update(3, 100);
        assertEquals(0, s.queued());
        s.update(4, 100);
        assertEquals(163, drain(s).size());
        s.update(4, 101); // Dolphin restarted
        assertEquals(163, drain(s).size());
    }

    @Test void breakingFromAboveSendsTheChangedChunks() {
        PlanetSession s = spawned();
        drain(s);
        Vector3d top = new Vector3d(s.center()).add(0, 17.5 * 80, 0);
        assertTrue(s.breakBlock(top, new Vector3d(0, -1, 0)));
        s.update(3, 100);
        List<PlanetSession.Msg> msgs = drain(s);
        assertFalse(msgs.isEmpty());
        ByteBuffer c = ByteBuffer.wrap(msgs.get(0).payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertTrue(c.getInt(4) >= 2, "version bumped");
        assertEquals(0, c.getInt(8) % 32);
        assertEquals(c.capacity(), 16 + c.getInt(8) + c.getInt(12));
        // The grass is gone: the next thing below is dirt.
        int hit = PlanetRaycast.cast(s.planet(), new Vector3d(0, 17.5, 0), new Vector3d(0, -1, 0), 4.5).hit();
        assertEquals(Material.DIRT, s.planet().get(hit));
    }

    @Test void bedrockStaysAndNothingOutOfReach() {
        PlanetSession s = spawned();
        assertFalse(s.breakBlock(new Vector3d(s.center()).add(0, 30 * 80, 0), new Vector3d(0, -1, 0)));
        // Dig down to the bedrock, then it refuses.
        Vector3d eye = new Vector3d(s.center()).add(0, 8.5 * 80, 0); // inside the last stone layer
        assertTrue(s.breakBlock(eye, new Vector3d(0, -1, 0)));
        assertFalse(s.breakBlock(eye, new Vector3d(0, -1, 0)), "bedrock right under");
    }

    @Test void placingNeverBuriesMario() {
        PlanetSession s = spawned();
        Vector3d feet = new Vector3d(s.center()).add(0, 16.0 * 80, 0);
        Vector3d eye = new Vector3d(feet).add(0, 1.6 * 80, 0);
        assertFalse(s.placeBlock(eye, new Vector3d(0, -1, 0), Material.DIRT, feet), "his own cell");
        Vector3d aside = new Vector3d(eye).add(2 * 80, 0, 0);
        assertTrue(s.placeBlock(aside, new Vector3d(0, -1, 0), Material.STONE, feet));
        assertEquals(Material.STONE, s.planet().get(s.planet().grid.cellAt(new Vector3d(2, 16.5, 0))));
    }

    @Test void removeSendsPlanetZero() {
        PlanetSession s = spawned();
        s.remove();
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(1, msgs.size());
        assertEquals(0, ByteBuffer.wrap(msgs.get(0).payload()).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertFalse(s.active());
    }
}
