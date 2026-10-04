package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanetSessionTest {
    static final Vector3d MARIO = new Vector3d(0, 0, 0);

    static List<PlanetSession.Msg> drain(PlanetSession s) {
        List<PlanetSession.Msg> out = new ArrayList<>();
        for (PlanetSession.Msg m; (m = s.peek()) != null; s.sent()) out.add(m);
        return out;
    }

    static ByteBuffer le(PlanetSession.Msg m) {
        return ByteBuffer.wrap(m.payload()).order(ByteOrder.LITTLE_ENDIAN);
    }

    static PlanetSession spawned(int radius) {
        PlanetSession s = new PlanetSession(80);
        s.spawn(radius, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, MARIO);
        return s;
    }

    @Test void spawnSendsPlanetThenTheChunksThatShow() {
        PlanetSession s = spawned(16);
        assertEquals(new Vector3d(0, 80 * (16 + 40 + 24), 0), s.center());
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(Layout.MSG_PLANET, msgs.get(0).type());
        ByteBuffer p = le(msgs.get(0));
        assertEquals(36, p.capacity());
        assertEquals(1, p.getInt(0));
        assertEquals(6400f, p.getFloat(8));
        assertEquals(1280f, p.getFloat(16));
        assertEquals(4480f, p.getFloat(20));
        assertEquals(s.planet().chunkCount(), p.getInt(24));
        assertEquals(80f * 12, p.getFloat(28)); // the bedrock under a crust 4 deep
        assertEquals(80f * (float) PlanetSession.DEFAULT_MARIO_RADIUS, p.getFloat(32), 1e-3);
        assertTrue(msgs.stream().skip(1).allMatch(m -> m.type() == Layout.MSG_CHUNK));
        assertTrue(msgs.stream().skip(1).allMatch(m -> le(m).getInt(8) > 0), "nothing empty is sent");
        // Every grass chunk, nearest to Mario (under the planet) first.
        int grass = (int) java.util.stream.IntStream.range(0, s.planet().chunkCount())
                .filter(ch -> !PlanetMesher.quads(s.planet(), ch).isEmpty()).count();
        assertEquals(1 + grass, msgs.size());
        float first = le(msgs.get(1)).getFloat(20), last = le(msgs.get(msgs.size() - 1)).getFloat(20);
        assertTrue(first < last, "nearest first: y " + first + " then " + last);
    }

    @Test void collisionOnlyNearMario() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        Vector3d onTop = new Vector3d(s.center()).add(0, -64 * 80, 0); // the face toward Mario
        s.update(3, 100, onTop);
        List<PlanetSession.Msg> msgs = drain(s);
        long withKcl = msgs.stream().filter(m -> m.type() == Layout.MSG_CHUNK && le(m).getInt(12) > 0).count();
        assertTrue(withKcl > 0 && withKcl <= PlanetSession.MAX_PARTS, withKcl + " chunks with collision");
        assertTrue(msgs.size() - 1 > 4 * withKcl, "the rest is drawn only");
        assertEquals(withKcl, s.collisionChunks());
        // Mario moves to the other side: collision follows him there.
        Vector3d other = new Vector3d(s.center()).add(0, 64 * 80, 0);
        for (int i = 0; i < PlanetSession.RESIDENCY_UPDATES; i++) s.update(3, 100, other);
        List<PlanetSession.Msg> moved = drain(s);
        assertFalse(moved.isEmpty());
        assertTrue(s.collisionChunks() > 0 && s.collisionChunks() <= PlanetSession.MAX_PARTS);
    }

    @Test void collisionReachesAheadOfAFastMario() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        Vector3d top = new Vector3d(s.center()).add(0, -64 * 80, 0);
        s.update(3, 100, top);
        drain(s);
        // Running along the ground, 1.5 blocks an update (falling is faster still).
        Vector3d step = new Vector3d(1.5 * 80, 0, 0), at = new Vector3d(top);
        for (int i = 0; i < 2 * PlanetSession.RESIDENCY_UPDATES; i++) {
            s.update(3, 100, at.add(step));
            drain(s);
        }
        // The grass under a point (just under the surface, radius 64).
        java.util.function.Function<Vector3d, Integer> ground = p -> s.cellAt(new Vector3d(p).sub(s.center()).normalize(63.5 * 80).add(s.center()));
        int[] ahead = {ground.apply(new Vector3d(at).fma(PlanetSession.LOOKAHEAD - 2, step)), ground.apply(new Vector3d(at).fma(-20, step))};
        assertTrue(s.collides(s.planet().chunkOf(ahead[0])), "the ground where he is headed collides");
        assertFalse(s.collides(s.planet().chunkOf(ahead[1])), "30 blocks behind him: no longer");
        assertTrue(s.collisionChunks() < 80, s.collisionChunks() + " collision parts");
    }

    @Test void biggestPlanetFitsTheGameAndSendsInSeconds() {
        long t0 = System.nanoTime();
        PlanetSession s = new PlanetSession(80);
        s.spawn(VoxelPlanet.MAX_RADIUS, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, MARIO);
        long dl = 0, kcl = 0, chunks = 0;
        for (PlanetSession.Msg m; (m = s.peek()) != null; s.sent()) {
            if (m.type() != Layout.MSG_CHUNK) continue;
            dl += le(m).getInt(8);
            kcl += le(m).getInt(12);
            chunks++;
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("radius 256: %d chunks, %.1f MB drawn, %.1f MB collision, %.1f s%n", chunks, dl / 1e6, kcl / 1e6, secs);
        assertTrue(dl + kcl < 64_000_000, "fits the game's memory: " + (dl + kcl));
        assertTrue(secs < 60, secs + " s");
    }

    @Test void teleportWaitsForTheGroundWhereMarioLands() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0)); // Mario far below it: no collision anywhere
        s.update(3, 100, MARIO);
        s.peek(); // the planet message
        s.sent();
        s.teleport();
        List<PlanetSession.Msg> msgs = drain(s);
        int tp = -1;
        for (int i = 0; i < msgs.size(); i++) if (msgs.get(i).type() == Layout.MSG_PLANET_TP) tp = i;
        assertTrue(tp > 0, "teleport sent");
        long kclBefore = msgs.subList(0, tp).stream().filter(m -> le(m).getInt(12) > 0).count();
        assertTrue(kclBefore > 4, kclBefore + " chunks with collision before the teleport");
        // They are where he lands: under the planet, the side facing his old position.
        assertTrue(msgs.subList(0, tp).stream().allMatch(m -> le(m).getFloat(20) < 0));
    }

    @Test void teleportLandsOnTopOfWhatIsThere() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(32, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, MARIO);
        VoxelPlanet p = s.planet();
        Vector3d mario = new Vector3d(MARIO).sub(s.center()).div(80);
        int c0 = p.grid.cellAt(new Vector3d(mario).normalize(p.grid.core + 0.5));
        for (int k = p.depth; k < p.depth + 5; k++) p.set(c0 + k, Material.STONE); // a tower where he lands
        s.teleport();
        PlanetSession.Msg tp = drain(s).stream().filter(m -> m.type() == Layout.MSG_PLANET_TP).findFirst().orElseThrow();
        assertEquals((p.surface() + 5) * 80, java.nio.ByteBuffer.wrap(tp.payload()).getFloat(), 1e-2);
    }

    @Test void newSceneResendsEverything() {
        PlanetSession s = spawned(16);
        int all = drain(s).size();
        s.update(3, 100, MARIO);
        assertEquals(0, s.queued());
        s.update(4, 100, MARIO);
        assertEquals(all, drain(s).size());
        s.update(4, 101, MARIO); // Dolphin restarted
        assertEquals(all, drain(s).size());
    }

    @Test void breakingFromAboveSendsTheChangedChunks() {
        PlanetSession s = spawned(16);
        drain(s);
        Vector3d top = new Vector3d(s.center()).add(0, 17.5 * 80, 0);
        assertTrue(s.breakBlock(top, new Vector3d(0, -1, 0)));
        assertTrue(s.unsaved());
        s.update(3, 100, MARIO);
        List<PlanetSession.Msg> msgs = drain(s);
        assertFalse(msgs.isEmpty());
        int grassChunk = s.planet().chunkOf(s.planet().grid.cellAt(new Vector3d(0, 15.5, 0)));
        ByteBuffer c = msgs.stream().map(PlanetSessionTest::le).filter(b -> b.getInt(0) == grassChunk).findFirst().orElseThrow();
        assertEquals(2, c.getInt(4), "the grass chunk again, next version");
        assertEquals(0, c.getInt(8) % 32);
        assertEquals(c.capacity(), 32 + c.getInt(8) + c.getInt(12));
        int hit = PlanetRaycast.cast(s.planet(), new Vector3d(0, 17.5, 0), new Vector3d(0, -1, 0), 4.5).hit();
        assertEquals(Material.DIRT, s.planet().material(hit));
    }

    @Test void dugIntoChunkUnderMarioComesWithCollision() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(16, MARIO, new Vector3d(0, 1, 0));
        Vector3d feet = new Vector3d(s.center()).add(0, 16 * 80, 0);
        s.update(3, 100, feet);
        drain(s);
        // Down to the dirt: its chunk (all solid until now) was never near, nor on the game.
        assertTrue(s.breakBlock(new Vector3d(feet).add(0, 80, 0), new Vector3d(0, -1, 0)));
        assertTrue(s.breakBlock(new Vector3d(feet).add(0, 80, 0), new Vector3d(0, -1, 0)));
        int dirtChunk = s.planet().chunkOf(s.planet().grid.cellAt(new Vector3d(0, 13.5, 0)));
        s.update(3, 100, feet);
        List<PlanetSession.Msg> msgs = drain(s);
        ByteBuffer m = msgs.stream().map(PlanetSessionTest::le).filter(b -> b.getInt(0) == dirtChunk).findFirst().orElseThrow();
        assertTrue(m.getInt(12) > 0, "with collision right away");
    }

    @Test void anEditWhileItsChunkWaitsIsNotLost() {
        PlanetSession s = spawned(16);
        drain(s);
        Vector3d top = new Vector3d(s.center()).add(0, 17.5 * 80, 0);
        s.breakBlock(top, new Vector3d(0, -1, 0));
        s.update(3, 100, MARIO);
        PlanetSession.Msg waiting = s.peek(); // built, the ring is full
        s.breakBlock(top, new Vector3d(0, -1, 0)); // the dirt below too
        s.update(3, 100, MARIO);
        s.sent();
        List<PlanetSession.Msg> rest = drain(s);
        int chunk = le(waiting).getInt(0);
        assertTrue(rest.stream().anyMatch(m -> le(m).getInt(0) == chunk && le(m).getInt(4) > le(waiting).getInt(4)));
    }

    @Test void outlineGoesOutOnlyWhenItChanges() {
        PlanetSession s = spawned(16);
        drain(s);
        Vector3d eye = new Vector3d(s.center()).add(0, 17.6 * 80, 0); // over the grass, looking down
        int cell = s.target(eye, new Vector3d(0, -1, 0), false);
        assertEquals(Material.GRASS, s.planet().material(cell));
        s.setOutline(cell);
        s.planet().set(s.planet().grid.neighbor(cell, CubeSphere.I_PLUS), Material.AIR); // a chunk to send too
        s.update(3, 100, MARIO); // same scene and host
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(Layout.MSG_OUTLINE, msgs.get(0).type(), "before the chunks");
        ByteBuffer o = le(msgs.get(0));
        assertEquals(1, o.getInt(0));
        Vector3d mid = s.planet().grid.center(cell).mul(80);
        for (int m = 0; m < 8; m++) {
            Vector3d c = new Vector3d(o.getFloat(4 + 12 * m), o.getFloat(8 + 12 * m), o.getFloat(12 + 12 * m));
            assertTrue(c.distance(mid) > 40 && c.distance(mid) < 80, "corner " + m + " " + c.distance(mid));
        }
        s.setOutline(cell);
        assertTrue(drain(s).isEmpty(), "same cell: nothing");
        s.setOutline(-1);
        List<PlanetSession.Msg> off = drain(s);
        assertEquals(1, off.size());
        assertEquals(0, le(off.get(0)).getInt(0));
        assertEquals(-1, s.target(eye, new Vector3d(0, 1, 0), false), "sky: nothing in reach");
    }

    @Test void bedrockStaysAndNothingOutOfReach() {
        PlanetSession s = spawned(16);
        assertFalse(s.breakBlock(new Vector3d(s.center()).add(0, 30 * 80, 0), new Vector3d(0, -1, 0)));
        Vector3d eye = new Vector3d(s.center()).add(0, 13.5 * 80, 0); // inside the last stone layer
        assertTrue(s.breakBlock(eye, new Vector3d(0, -1, 0)));
        assertFalse(s.breakBlock(eye, new Vector3d(0, -1, 0)), "bedrock right under");
    }

    @Test void placingNeverBuriesMario() {
        PlanetSession s = spawned(16);
        Vector3d feet = new Vector3d(s.center()).add(0, 16.0 * 80, 0);
        Vector3d eye = new Vector3d(feet).add(0, 1.6 * 80, 0);
        assertFalse(s.placeBlock(eye, new Vector3d(0, -1, 0), Material.DIRT, feet), "his own cell");
        Vector3d aside = new Vector3d(eye).add(2 * 80, 0, 0);
        assertTrue(s.placeBlock(aside, new Vector3d(0, -1, 0), Material.STONE, feet));
        assertEquals(Material.STONE, s.planet().material(s.planet().grid.cellAt(new Vector3d(2, 16.5, 0))));
    }

    @Test void removeSendsPlanetZero() {
        PlanetSession s = spawned(16);
        s.remove();
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(1, msgs.size());
        assertEquals(0, le(msgs.get(0)).getInt(0));
        assertFalse(s.active());
    }

    @Test void savedPlanetComesBackAsItWas(@TempDir Path dir) throws Exception {
        PlanetSession s = spawned(32);
        Vector3d top = new Vector3d(s.center()).add(0, 33.5 * 80, 0);
        s.breakBlock(top, new Vector3d(0, -1, 0));
        PlanetStore store = new PlanetStore(dir);
        store.write("SkyStationGalaxy", s.save(), CubeBlocks.INSTANCE);
        assertFalse(s.unsaved());
        assertTrue(Files.size(store.file("SkyStationGalaxy")) < 200_000, "compressed");

        PlanetSession t = new PlanetSession(80);
        t.load(store.read("SkyStationGalaxy", CubeBlocks.INSTANCE).orElseThrow());
        assertEquals(s.center(), t.center());
        assertEquals(s.planet().surface(), t.planet().surface());
        assertArrayEquals(s.planet().cells(), t.planet().cells());
        t.update(9, 100, MARIO);
        assertEquals(Layout.MSG_PLANET, t.peek().type());
        assertTrue(store.read("BossGalaxy", CubeBlocks.INSTANCE).isEmpty());
        store.delete("SkyStationGalaxy");
        assertTrue(store.read("SkyStationGalaxy", CubeBlocks.INSTANCE).isEmpty());
    }
}
