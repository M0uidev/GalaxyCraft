package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.joml.Quaterniond;
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

    /** A chunk message's slot (without the planet's id), -1 for a part of the far view. */
    static int slot(ByteBuffer chunk) {
        int w = chunk.getInt(0);
        return (w & PlanetSession.FAR_VIEW) != 0 ? -1 : w & 0x7FFFFF;
    }

    static boolean far(PlanetSession.Msg m) {
        return m.type() == Layout.MSG_CHUNK && (le(m).getInt(0) & PlanetSession.FAR_VIEW) != 0;
    }

    /** A part of the far view whose tile is chunks in the game: drawn only from afar. */
    static boolean covered(PlanetSession.Msg m) {
        return far(m) && (le(m).getInt(0) & PlanetSession.FAR_COVERED) != 0;
    }

    /** A far view message's tile. */
    static int tile(ByteBuffer far) {
        return far.getInt(0) & (PlanetSession.FAR_COVERED - 1);
    }

    /** A planet far above Mario, every chunk of it sent (no render distance). */
    static PlanetSession spawned(int radius) {
        PlanetSession s = new PlanetSession(80);
        s.setRenderDistance(Double.POSITIVE_INFINITY);
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
        assertEquals(40, p.capacity());
        assertEquals(s.id(), p.getInt(0));
        assertEquals(0, p.order(ByteOrder.BIG_ENDIAN).getInt(36), "no flags");
        p.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(6400f, p.getFloat(8));
        assertEquals(1280f, p.getFloat(16));
        assertEquals(4480f, p.getFloat(20));
        assertEquals(s.planet().chunkCount(), p.getInt(24));
        assertEquals(80f * 12, p.getFloat(28)); // the bedrock under a crust 4 deep
        assertEquals(80f * (float) PlanetSession.DEFAULT_MARIO_RADIUS, p.getFloat(32), 1e-3);
        assertTrue(msgs.stream().skip(1).allMatch(m -> m.type() == Layout.MSG_CHUNK));
        assertTrue(msgs.stream().skip(1).allMatch(m -> le(m).getInt(8) > 0), "nothing empty is sent");
        // No render distance: every tile is chunks, its far view covered (for a camera far away).
        assertTrue(msgs.stream().filter(PlanetSessionTest::far).allMatch(PlanetSessionTest::covered));
        assertEquals(PlanetLod.tileCount(s.planet()), msgs.stream().filter(PlanetSessionTest::far).count());
        msgs = msgs.stream().filter(m -> !far(m)).toList();
        // Every grass chunk, nearest to Mario (under the planet) first.
        int grass = (int) java.util.stream.IntStream.range(0, s.planet().chunkCount())
                .filter(ch -> !PlanetMesher.quads(s.planet(), ch).isEmpty()).count();
        assertEquals(1 + grass, msgs.size());
        assertTrue(msgs.stream().skip(1).allMatch(m -> le(m).getInt(0) >>> 24 == s.id()), "each chunk names its planet");
        float first = le(msgs.get(1)).getFloat(20), last = le(msgs.get(msgs.size() - 1)).getFloat(20);
        assertTrue(first < last, "nearest first: y " + first + " then " + last);
    }

    @Test void marioAtThePlanetsCoreIsStuckThere() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(32, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, new Vector3d(s.center()).add(0, 33 * 80, 0)); // on its top
        assertFalse(s.marioAtCore());
        s.update(3, 100, new Vector3d(s.center()).add(0, 10 * 80, 0)); // deep in a mine
        assertFalse(s.marioAtCore());
        s.update(3, 100, new Vector3d(s.center()).add(0, 80, 40)); // fallen through to its middle
        assertTrue(s.marioAtCore());
        s.update(3, 100, null);
        assertFalse(s.marioAtCore());
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

    /** Mario on the planet's face toward him, a block over the grass (radius r). */
    static Vector3d onTop(PlanetSession s, int r) {
        return new Vector3d(s.center()).add(0, -(r + 1) * 80, 0);
    }

    static boolean kcl(PlanetSession.Msg m) {
        return m.type() == Layout.MSG_CHUNK && le(m).getInt(12) > 0;
    }

    @Test void withoutBudgetOnlyMariosCollisionIsBuilt() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, onTop(s, 64));
        List<PlanetSession.Msg> got = new ArrayList<>();
        for (PlanetSession.Msg m; (m = s.peek(() -> false)) != null; s.sent()) got.add(m);
        assertEquals(Layout.MSG_PLANET, got.get(0).type());
        assertTrue(got.size() > 1, "the ground under Mario");
        assertTrue(got.stream().skip(1).allMatch(PlanetSessionTest::kcl), "nothing but collision: no far view, no far chunks");
        assertEquals(got.size() - 1, s.collisionChunks());
        assertTrue(s.queued() > 100, "the rest of the planet waits for time: " + s.queued());
        // With time, the rest comes.
        List<PlanetSession.Msg> rest = drain(s);
        assertTrue(rest.stream().anyMatch(PlanetSessionTest::far));
        assertTrue(rest.stream().noneMatch(PlanetSessionTest::kcl), "the collision was all there already");
    }

    @Test void collisionWhereMarioWalksJumpsAheadOfAStreamingPlanet() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        Vector3d top = onTop(s, 64);
        s.update(3, 100, top);
        // A few chunks a tick, as a budget allows: most of the planet still waits.
        for (int n = 0; n < 40 && s.peek() != null; n++) s.sent();
        assertTrue(s.queued() > 100, s.queued() + " waiting");
        // Mario runs to ground the planet has not sent yet (a quarter of the way around).
        Vector3d side = new Vector3d(s.center()).add(65 * 80, 0, 0);
        for (int i = 0; i < PlanetSession.RESIDENCY_UPDATES; i++) s.update(3, 100, side);
        int ground = s.planet().chunkOf(s.cellAt(new Vector3d(s.center()).add(63.5 * 80, 0, 0)));
        assertFalse(s.collides(ground));
        // Out of time this tick: still, the ground under him is what comes.
        for (PlanetSession.Msg m; (m = s.peek(() -> false)) != null; s.sent()) assertTrue(kcl(m) || le(m).getInt(12) == 0 && !far(m));
        assertTrue(s.collides(ground), "his ground collides before the rest of the planet is sent");
    }

    @Test void anEditUnderMarioGoesOutAheadOfTheRest() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(32, MARIO, new Vector3d(0, 1, 0));
        Vector3d top = onTop(s, 32);
        s.update(3, 100, top);
        while (s.peek(() -> false) != null) s.sent(); // the planet and Mario's collision
        assertTrue(s.queued() > 10, "the rest waits");
        int under = s.cellAt(new Vector3d(s.center()).add(0, -31.5 * 80, 0));
        int chunk = s.planet().chunkOf(under);
        assertTrue(s.collides(chunk));
        s.planet().set(under, Blocks.AIR);
        s.update(3, 100, top);
        // It (and the neighbors whose faces it changed) goes now, out of budget or not.
        List<PlanetSession.Msg> now = new ArrayList<>();
        for (PlanetSession.Msg m; (m = s.peek(() -> false)) != null; s.sent()) now.add(m);
        assertTrue(now.stream().anyMatch(m -> slot(le(m)) == chunk && kcl(m)), "the dug chunk, with its collision");
        assertTrue(now.size() < 8, now.size() + ": nothing else");
    }

    @Test void teleportLandsOnItsGroundEvenOutOfBudget() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, MARIO);
        s.teleport();
        List<PlanetSession.Msg> got = new ArrayList<>();
        for (PlanetSession.Msg m; (m = s.peek(() -> false)) != null; s.sent()) got.add(m);
        assertEquals(Layout.MSG_PLANET_TP, got.get(got.size() - 1).type(), "the teleport, last");
        assertTrue(got.stream().anyMatch(PlanetSessionTest::kcl), "after the ground where he lands");
    }

    /** Chunk messages with something to draw, by slot; far view messages by tile (dl bytes), drawn and covered. */
    record Sent(java.util.Map<Integer, Integer> chunks, java.util.Map<Integer, Integer> far, java.util.Map<Integer, Integer> covered) {}

    static Sent sent(List<PlanetSession.Msg> msgs) {
        java.util.Map<Integer, Integer> chunks = new java.util.LinkedHashMap<>(), far = new java.util.LinkedHashMap<>(),
                covered = new java.util.LinkedHashMap<>();
        for (PlanetSession.Msg m : msgs) {
            if (m.type() != Layout.MSG_CHUNK) continue;
            ByteBuffer b = le(m);
            if (covered(m)) covered.put(tile(b), b.getInt(8));
            else if (far(m)) far.put(tile(b), b.getInt(8));
            else chunks.put(slot(b), b.getInt(8));
        }
        return new Sent(chunks, far, covered);
    }

    @Test void aBigPlanetIsChunksAroundMarioAndItsFarViewElsewhere() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(128, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, onTop(s, 128));
        Sent got = sent(drain(s));
        VoxelPlanet p = s.planet();
        int tiles = PlanetLod.tileCount(p), shown = 0;
        for (int t = 0; t < tiles; t++) if (s.tileShown(t)) shown++;
        assertTrue(shown > 0 && shown < tiles / 4, shown + " of " + tiles + " tiles as chunks");
        for (int c : got.chunks().keySet()) assertTrue(s.tileShown(PlanetLod.tileOfChunk(p, c)) || s.collides(c), "chunk " + c + " out of view");
        for (int t = 0; t < tiles; t++)
            if (s.tileShown(t)) {
                assertFalse(got.far().containsKey(t), "tile " + t + " is chunks: its far view not drawn near");
                assertTrue(got.covered().getOrDefault(t, 0) > 0, "tile " + t + " is chunks: its far view kept for afar");
            }
            else assertTrue(got.far().containsKey(t), "tile " + t + " is far view");
        long bytes = got.chunks().values().stream().mapToLong(Integer::longValue).sum()
                + got.far().values().stream().mapToLong(Integer::longValue).sum()
                + got.covered().values().stream().mapToLong(Integer::longValue).sum();
        System.out.printf("radius 128, render %.0f: %d tiles of %d as chunks, %d chunks, %.1f MB%n", PlanetSession.RENDER, shown, tiles,
                got.chunks().size(), bytes / 1e6);
        assertTrue(bytes < 8_000_000, bytes + " bytes");
    }

    @Test void arrivingTheChunksAroundMarioComeBeforeTheFarViewOfTheRest() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(128, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, onTop(s, 128));
        List<PlanetSession.Msg> msgs = drain(s);
        int lastChunk = -1, firstFar = -1;
        for (int i = 0; i < msgs.size(); i++) {
            if (far(msgs.get(i))) {
                if (firstFar < 0 && !covered(msgs.get(i))) firstFar = i;
            } else if (msgs.get(i).type() == Layout.MSG_CHUNK) lastChunk = i;
        }
        assertTrue(firstFar > lastChunk, "the far view that stays drawn near waits for the chunks: " + firstFar + " vs " + lastChunk);
    }

    @Test void walkingAwayTheFarViewTakesOverBeforeTheChunksGo() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        Vector3d top = onTop(s, 64);
        s.update(3, 100, top);
        Sent first = sent(drain(s));
        VoxelPlanet p = s.planet();
        int under = PlanetLod.tileOfChunk(p, p.chunkOf(s.cellAt(new Vector3d(s.center()).add(0, -63.5 * 80, 0))));
        assertTrue(s.tileShown(under));
        assertFalse(first.far().containsKey(under));
        // To the other side of the planet.
        Vector3d other = new Vector3d(s.center()).add(0, 65 * 80, 0);
        for (int i = 0; i < PlanetSession.RESIDENCY_UPDATES; i++) s.update(3, 100, other);
        List<PlanetSession.Msg> msgs = drain(s);
        assertFalse(s.tileShown(under));
        int farAt = -1, goneAt = -1;
        for (int i = 0; i < msgs.size(); i++) {
            ByteBuffer b = le(msgs.get(i));
            if (msgs.get(i).type() != Layout.MSG_CHUNK) continue;
            if (far(msgs.get(i)) && !covered(msgs.get(i)) && tile(b) == under && b.getInt(8) > 0 && farAt < 0) farAt = i;
            if (!far(msgs.get(i)) && PlanetLod.tileOfChunk(p, slot(b)) == under && b.getInt(8) == 0 && goneAt < 0) goneAt = i;
        }
        assertTrue(farAt >= 0, "its far view comes back");
        assertTrue(goneAt > farAt, "and only then do its chunks go: " + farAt + ", " + goneAt);
        // Where he is now: chunks, and the far view there covered after them.
        int there = PlanetLod.tileOfChunk(p, p.chunkOf(s.cellAt(new Vector3d(s.center()).add(0, 63.5 * 80, 0))));
        assertTrue(s.tileShown(there));
        int chunkAt = -1, hiddenAt = -1;
        for (int i = 0; i < msgs.size(); i++) {
            ByteBuffer b = le(msgs.get(i));
            if (msgs.get(i).type() != Layout.MSG_CHUNK) continue;
            if (!far(msgs.get(i)) && PlanetLod.tileOfChunk(p, slot(b)) == there && b.getInt(8) > 0) chunkAt = i;
            if (covered(msgs.get(i)) && tile(b) == there && b.getInt(8) == 0) hiddenAt = i;
        }
        assertTrue(chunkAt >= 0 && hiddenAt > chunkAt, "its chunks, then its far view covered: " + chunkAt + ", " + hiddenAt);
    }

    @Test void aTileComingInWhileItsFarViewWaitsForRoomIsStillHidden() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        Vector3d away = new Vector3d(0, -1000 * 80, 0);
        s.update(3, 100, away); // all far view
        assertTrue(s.peek() != null);
        s.sent(); // the planet
        PlanetSession.Msg waiting = s.peek();
        assertTrue(far(waiting) && le(waiting).getInt(8) > 0, "a tile's far view, built, the ring full");
        int tile = tile(le(waiting));
        // Mario lands right on that tile before the ring has room.
        VoxelPlanet p = s.planet();
        int chunk = PlanetLod.chunksOfTile(p, tile)[p.chunkLayers() - 1];
        Vector3d c = new Vector3d();
        p.sphere(chunk, c, new double[1]);
        Vector3d on = new Vector3d(c).normalize(66).mul(80).add(s.center());
        for (int i = 0; i < PlanetSession.RESIDENCY_UPDATES; i++) s.update(3, 100, on);
        assertTrue(s.tileShown(tile));
        List<PlanetSession.Msg> rest = drain(s); // the far view goes out as built, then...
        long hides = rest.stream().filter(m -> covered(m) && tile(le(m)) == tile && le(m).getInt(8) == 0).count();
        assertEquals(1, hides, "...covered again once its chunks are there");
    }

    @Test void theBiggestPlanetCostsTheGameItsGroundAroundMario() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(VoxelPlanet.MAX_RADIUS, MARIO, new Vector3d(0, 1, 0));
        long t0 = System.nanoTime();
        s.update(3, 100, onTop(s, VoxelPlanet.MAX_RADIUS));
        Sent got = sent(drain(s));
        long bytes = got.chunks().values().stream().mapToLong(Integer::longValue).sum()
                + got.far().values().stream().mapToLong(Integer::longValue).sum()
                + got.covered().values().stream().mapToLong(Integer::longValue).sum();
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("radius 256, render %.0f: %d chunks, %d far tiles, %.1f MB, %.1f s%n", PlanetSession.RENDER, got.chunks().size(),
                got.far().size(), bytes / 1e6, secs);
        assertTrue(bytes < 12_000_000, bytes + " bytes");
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

    @Test void aTeleportRightAfterSpawningSurvivesTheFirstUpdate() {
        // A world's home planet: made and Mario sent onto it in the same tick, before the update
        // that sends the planet to the game for the first time.
        PlanetSession s = new PlanetSession(80);
        s.setRenderDistance(Double.POSITIVE_INFINITY);
        s.spawnAt(VoxelPlanet.ofRadius(16, CubeBlocks.INSTANCE), new Vector3d());
        s.teleportToward(new Vector3d(0, 1, 0));
        s.update(3, 100, new Vector3d());
        List<PlanetSession.Msg> got = drain(s);
        assertEquals(Layout.MSG_PLANET, got.get(0).type(), "the planet first");
        assertEquals(1, got.stream().filter(m -> m.type() == Layout.MSG_PLANET_TP).count(), "the teleport, once");
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
        List<PlanetSession.Msg> before = msgs.subList(0, tp).stream().filter(m -> !far(m)).toList();
        long kclBefore = before.stream().filter(m -> le(m).getInt(12) > 0).count();
        assertTrue(kclBefore > 4, kclBefore + " chunks with collision before the teleport");
        // They are where he lands: under the planet, the side facing his old position.
        assertTrue(before.stream().allMatch(m -> le(m).getFloat(20) < 0));
        assertEquals(s.id(), ByteBuffer.wrap(msgs.get(tp).payload()).getInt(4), "the teleport names its planet");
    }

    @Test void underCoverFarCavesAreSentWithTheirDarkFaces() {
        VoxelPlanet p = VoxelPlanet.standard();
        // Two sealed pockets deep under the grass, on opposite faces of the cube.
        int[] here = {p.grid.index(2, 12, 12, 2), p.grid.index(2, 12, 13, 2)};
        int[] there = {p.grid.index(5, 12, 12, 2), p.grid.index(5, 12, 13, 2)};
        for (int c : here) p.set(c, Material.AIR);
        for (int c : there) p.set(c, Material.AIR);
        p.takeDirty();
        PlanetSession s = new PlanetSession(80);
        s.spawn(p, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, MARIO);
        drain(s);
        int far = p.chunkOf(there[0]);
        assertFalse(s.underground());
        assertTrue(s.darkCut(far), "seen from outside, the far pocket's walls are left out");
        // Mario in the near pocket: the far one is sent whole.
        Vector3d inPocket = new Vector3d(p.grid.center(here[0])).mul(80).add(s.center());
        s.update(3, 100, inPocket);
        drain(s);
        assertTrue(s.underground());
        assertFalse(s.darkCut(far), "from inside a cave, other caves show their walls");
        // Out again for a while: left out again.
        for (int i = 0; i <= PlanetSession.OUTSIDE_UPDATES; i++) s.update(3, 100, MARIO);
        drain(s);
        assertFalse(s.underground());
        assertTrue(s.darkCut(far));
    }

    @Test void theLandingKeepsItsCollisionUntilMarioIsThere() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, MARIO);
        drain(s);
        s.teleport();
        drain(s);
        int landed = s.collisionChunks();
        assertTrue(landed > 0);
        // The game moves him a few frames later: meanwhile he is still far away.
        for (int i = 0; i < 4 * PlanetSession.RESIDENCY_UPDATES; i++) s.update(3, 100, MARIO);
        drain(s);
        assertEquals(landed, s.collisionChunks(), "the ground where he lands still collides");
        // There at last, and then off again: collision follows him from then on.
        Vector3d there = new Vector3d(s.center()).add(0, -64 * 80, 0);
        for (int i = 0; i < PlanetSession.RESIDENCY_UPDATES; i++) s.update(3, 100, there);
        Vector3d other = new Vector3d(s.center()).add(0, 64 * 80, 0);
        for (int i = 0; i < 2 * PlanetSession.RESIDENCY_UPDATES; i++) s.update(3, 100, other);
        drain(s);
        assertFalse(s.collides(s.planet().chunkOf(s.cellAt(new Vector3d(s.center()).add(0, -63.5 * 80, 0)))), "no longer under his old landing");
    }

    @Test void teleportLandsOnTopOfWhatIsThere() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(32, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, MARIO);
        VoxelPlanet p = s.planet();
        Vector3d mario = new Vector3d(MARIO).sub(s.center()).div(80);
        int c0 = p.grid.cellAt(new Vector3d(mario).normalize(p.sphere().core + 0.5));
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
        ByteBuffer c = msgs.stream().map(PlanetSessionTest::le).filter(b -> slot(b) == grassChunk).findFirst().orElseThrow();
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
        ByteBuffer m = msgs.stream().map(PlanetSessionTest::le).filter(b -> slot(b) == dirtChunk).findFirst().orElseThrow();
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
        assertEquals(s.id(), o.getInt(0), "visible: on this planet");
        assertEquals(12, o.getInt(4), "a cube's edges");
        assertEquals(8 + 12 * 24, o.capacity(), "only those");
        Vector3d mid = s.planet().grid.center(cell).mul(80);
        for (int e = 0; e < 12; e++) {
            Vector3d a = new Vector3d(o.getFloat(8 + 24 * e), o.getFloat(12 + 24 * e), o.getFloat(16 + 24 * e));
            Vector3d b = new Vector3d(o.getFloat(20 + 24 * e), o.getFloat(24 + 24 * e), o.getFloat(28 + 24 * e));
            assertTrue(a.distance(mid) > 40 && a.distance(mid) < 80, "edge " + e + " " + a.distance(mid));
            assertTrue(b.distance(mid) > 40 && b.distance(mid) < 80, "edge " + e + " " + b.distance(mid));
            assertTrue(a.distance(b) > 60 && a.distance(b) < 100, "a cell's side long: " + a.distance(b));
        }
        s.setOutline(cell);
        assertTrue(drain(s).isEmpty(), "same cell: nothing");
        s.setOutline(-1);
        List<PlanetSession.Msg> off = drain(s);
        assertEquals(1, off.size());
        assertEquals(0, le(off.get(0)).getInt(0));
        assertEquals(-1, s.target(eye, new Vector3d(0, 1, 0), false), "sky: nothing in reach");
    }

    @Test void cracksGoOutWhenTheirStageOrBlockChanges() {
        PlanetSession s = spawned(16);
        drain(s);
        Vector3d eye = new Vector3d(s.center()).add(0, 17.6 * 80, 0); // over the grass, looking down
        int cell = s.target(eye, new Vector3d(0, -1, 0), false);
        float[] uv = {0.25f, 0.5f, 0.3125f, 0.5625f};
        s.setCrack(cell, 3, uv);
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(1, msgs.size());
        assertEquals(Layout.MSG_CRACK, msgs.get(0).type());
        ByteBuffer c = le(msgs.get(0));
        assertEquals(120, c.capacity());
        assertEquals(s.id(), c.getInt(0), "visible: on this planet");
        assertEquals(3, c.getInt(4));
        assertEquals(0.25f, c.getFloat(8));
        assertEquals(0.5625f, c.getFloat(20));
        Vector3d mid = s.planet().grid.center(cell).mul(80);
        for (int m = 0; m < 8; m++) {
            Vector3d p = new Vector3d(c.getFloat(24 + 12 * m), c.getFloat(28 + 12 * m), c.getFloat(32 + 12 * m));
            assertTrue(p.distance(mid) > 40 && p.distance(mid) < 80, "corner " + m + " " + p.distance(mid));
        }
        s.setCrack(cell, 3, uv);
        assertTrue(drain(s).isEmpty(), "same stage: nothing");
        s.setCrack(cell, 4, uv);
        assertEquals(4, le(drain(s).get(0)).getInt(4));
        s.planet().set(cell, Material.STONE);
        s.setCrack(cell, 4, uv);
        assertEquals(1, drain(s).stream().filter(m -> m.type() == Layout.MSG_CRACK).count(), "another block there");
        s.setCrack(-1, 4, uv);
        List<PlanetSession.Msg> off = drain(s);
        assertEquals(1, off.size());
        assertEquals(0, le(off.get(0)).getInt(0), "none");
        s.setCrack(cell, -1, uv);
        assertTrue(drain(s).isEmpty(), "still none");
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

    @Test void removeDropsThisPlanetOnly() {
        PlanetSession s = spawned(16);
        int id = s.id();
        s.remove();
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(1, msgs.size());
        assertEquals(id, le(msgs.get(0)).getInt(0));
        assertEquals(PlanetSession.PLANET_GONE, ByteBuffer.wrap(msgs.get(0).payload()).getInt(36));
        assertFalse(s.active());
    }

    @Test void planetsHaveIdsOfTheirOwnAndANewOneEachSpawn() {
        PlanetSession a = spawned(16), b = spawned(16);
        assertNotEquals(a.id(), b.id());
        assertTrue(a.id() >= 1 && a.id() <= 255);
        int old = a.id();
        a.spawn(16, MARIO, new Vector3d(0, 1, 0));
        a.update(3, 100, MARIO);
        List<PlanetSession.Msg> msgs = drain(a);
        assertNotEquals(old, a.id(), "not the old planet's id: the game may still hold its chunks");
        assertEquals(old, le(msgs.get(0)).getInt(0), "the old one is dropped first");
        assertEquals(PlanetSession.PLANET_GONE, ByteBuffer.wrap(msgs.get(0).payload()).getInt(36));
        assertEquals(a.id(), le(msgs.get(1)).getInt(0));
    }

    @Test void anOutlineWhileAFarPartWaitsLosesNeither() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(16, MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, new Vector3d(0, -1000 * 80, 0)); // Mario far away: all of it far view
        s.peek();
        s.sent(); // the planet
        assertTrue(far(s.peek()), "a part of the far view is built and waits");
        Vector3d eye = new Vector3d(s.center()).add(0, 17.6 * 80, 0);
        s.setOutline(s.target(eye, new Vector3d(0, -1, 0), false));
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(Layout.MSG_OUTLINE, msgs.get(0).type());
        assertEquals(1, msgs.stream().filter(m -> m.type() == Layout.MSG_OUTLINE).count());
        assertEquals(PlanetLod.tileCount(s.planet()), msgs.stream().filter(PlanetSessionTest::far).count(), "every tile");
    }

    @Test void withoutDetailOnlyTheFarViewIsSent() {
        PlanetSession s = spawned(16);
        drain(s);
        s.setDetail(false);
        s.update(3, 100, MARIO);
        List<PlanetSession.Msg> msgs = drain(s);
        assertEquals(1 + 6, msgs.size(), "the planet and its far view");
        assertEquals(0, le(msgs.get(0)).getInt(24), "no chunk slots: the game drops its chunks");
        assertTrue(msgs.stream().skip(1).allMatch(PlanetSessionTest::far));
        // Edits change only the far view, once a while.
        Vector3d top = new Vector3d(s.center()).add(0, 17.5 * 80, 0);
        assertTrue(s.breakBlock(top, new Vector3d(0, -1, 0)));
        List<PlanetSession.Msg> later = new ArrayList<>();
        for (int i = 0; i < PlanetSession.FAR_UPDATES; i++) {
            s.update(3, 100, MARIO);
            later.addAll(drain(s));
        }
        assertEquals(1, later.size(), "the face dug into, once");
        assertTrue(far(later.getFirst()));
        // Back to detail: chunks again.
        s.setDetail(true);
        s.update(3, 100, MARIO);
        assertTrue(drain(s).stream().anyMatch(m -> m.type() == Layout.MSG_CHUNK && !far(m)));
    }

    @Test void nearingAPlanetSendsItsChunksNotItsFarViewAgain() {
        // From afar the game has the planet's far view; Mario comes near: detail on.
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        Vector3d top = onTop(s, 64);
        s.setDetail(false);
        s.update(3, 100, top);
        drain(s);
        s.setDetail(true);
        s.update(3, 100, top);
        List<PlanetSession.Msg> msgs = drain(s);
        assertTrue(msgs.stream().anyMatch(m -> m.type() == Layout.MSG_CHUNK && !far(m)), "its chunks");
        // Its far view, which the game has, not again ahead of them: only tiles a level finer, after.
        int lastChunk = -1, firstFar = Integer.MAX_VALUE;
        for (int i = 0; i < msgs.size(); i++) {
            if (msgs.get(i).type() == Layout.MSG_CHUNK && !far(msgs.get(i))) lastChunk = i;
            if (far(msgs.get(i)) && le(msgs.get(i)).getInt(8) > 0) firstFar = Math.min(firstFar, i);
        }
        assertTrue(firstFar > lastChunk, "chunks first: " + lastChunk + ", " + firstFar);
        // Each tile of chunks covered once (after them), the others left as they are.
        VoxelPlanet p = s.planet();
        for (int t = 0; t < PlanetLod.tileCount(p); t++) {
            int tt = t;
            long covers = msgs.stream().filter(m -> covered(m) && tile(le(m)) == tt).count();
            assertEquals(s.tileShown(t) ? 1 : 0, covers, "tile " + t);
        }
        // Away again: only the covered tiles get their far view back, uncovered.
        s.setDetail(false);
        s.update(3, 100, top);
        List<PlanetSession.Msg> back = drain(s);
        for (int t = 0; t < PlanetLod.tileCount(p); t++) {
            int tt = t;
            long sent = back.stream().filter(m -> far(m) && tile(le(m)) == tt).count();
            // Covered ones uncovered; finer ones coarse again (no chunks to hand off to); once each.
            assertEquals(PlanetLod.tilePatchColumns(p.grid.n), s.farColumns(t), "tile " + t + " coarse");
            if (wasShown(msgs, t)) assertEquals(1, sent, "tile " + t);
            else assertTrue(sent <= 1, "tile " + t);
            assertTrue(back.stream().noneMatch(m -> covered(m) && tile(le(m)) == tt), "drawn near again: not covered");
        }
    }

    @Test void theFarViewIsFinerNearTheChunksAndCoarserFartherOut() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(128, MARIO, new Vector3d(0, 1, 0));
        Vector3d top = onTop(s, 128);
        s.update(3, 100, top);
        Sent got = sent(drain(s));
        VoxelPlanet p = s.planet();
        int coarse = PlanetLod.tilePatchColumns(p.grid.n);
        int finest = Integer.MAX_VALUE, coarsest = 0;
        for (int t : got.far().keySet()) {
            finest = Math.min(finest, s.farColumns(t));
            coarsest = Math.max(coarsest, s.farColumns(t));
        }
        assertEquals(PlanetSession.FINEST_COLUMNS, finest, "next to the chunks, nearly block by block");
        assertEquals(coarse, coarsest, "far away, as before");
        // Walking to the other side: the tiles there get finer, the ones left behind coarser.
        Vector3d other = new Vector3d(s.center()).add(0, 129 * 80, 0);
        int under = PlanetLod.tileOfChunk(p, p.chunkOf(s.cellAt(new Vector3d(s.center()).add(0, 127.5 * 80, 0))));
        int wasThere = s.farColumns(under);
        for (int i = 0; i < PlanetSession.RESIDENCY_UPDATES; i++) s.update(3, 100, other);
        drain(s);
        assertTrue(s.tileShown(under) && wasThere == coarse);
        int behind = PlanetLod.tileOfChunk(p, p.chunkOf(s.cellAt(new Vector3d(s.center()).add(0, -127.5 * 80, 0))));
        assertFalse(s.tileShown(behind));
        assertEquals(coarse, s.farColumns(behind), "left behind: coarse again");
        long bytes = got.chunks().values().stream().mapToLong(Integer::longValue).sum()
                + got.far().values().stream().mapToLong(Integer::longValue).sum()
                + got.covered().values().stream().mapToLong(Integer::longValue).sum();
        System.out.printf("graded far view, radius 128: %.1f MB%n", bytes / 1e6);
        assertTrue(bytes < 10_000_000, bytes + " bytes");
    }

    @Test void chunksMeshedInParallelAreTheSameAndAnEditIsNeverLost() {
        List<byte[]> serial = new ArrayList<>(), parallel = new ArrayList<>();
        for (boolean par : new boolean[] {false, true}) {
            PlanetSession s = new PlanetSession(80);
            s.setParallelMeshing(par);
            s.spawn(64, MARIO, new Vector3d(0, 1, 0));
            Vector3d top = onTop(s, 64);
            s.update(3, 100, top);
            // A few messages, an edit under Mario, then the rest: the edited chunks as they are now.
            for (int i = 0; i < 20 && s.peek() != null; i++) s.sent();
            assertTrue(s.breakBlock(top, new Vector3d(0, 1, 0)));
            s.update(3, 100, top);
            for (PlanetSession.Msg m : drain(s)) {
                byte[] b = m.payload().clone(); // each session has its own planet id
                if (m.type() == Layout.MSG_CHUNK) b[3] = 0;
                else if (b.length >= 4) b[0] = b[1] = b[2] = b[3] = 0;
                (par ? parallel : serial).add(b);
            }
        }
        assertEquals(serial.size(), parallel.size());
        for (int i = 0; i < serial.size(); i++) assertArrayEquals(serial.get(i), parallel.get(i), "message " + i);
    }

    static boolean wasShown(List<PlanetSession.Msg> msgs, int t) {
        return msgs.stream().anyMatch(m -> covered(m) && tile(le(m)) == t);
    }

    @Test void diggingNearMarioRefreshesTheFarViewSeenFromAfar() {
        PlanetSession s = new PlanetSession(80);
        s.spawn(64, MARIO, new Vector3d(0, 1, 0));
        Vector3d top = onTop(s, 64);
        s.update(3, 100, top);
        drain(s);
        VoxelPlanet p = s.planet();
        Vector3d ground = new Vector3d(s.center()).add(0, -63.5 * 80, 0);
        int under = PlanetLod.tileOfChunk(p, p.chunkOf(s.cellAt(ground)));
        assertTrue(s.tileShown(under));
        assertTrue(s.breakBlock(top, new Vector3d(0, 1, 0)));
        List<PlanetSession.Msg> later = new ArrayList<>();
        for (int i = 0; i < PlanetSession.FAR_UPDATES; i++) {
            s.update(3, 100, top);
            later.addAll(drain(s));
        }
        assertTrue(later.stream().anyMatch(m -> covered(m) && tile(le(m)) == under && le(m).getInt(8) > 0),
                "its tile's far view again, covered: the hole shows from afar too");
        assertTrue(later.stream().noneMatch(m -> far(m) && !covered(m) && tile(le(m)) == under), "never drawn over its chunks");
    }

    @Test void aStageKeepsSeveralPlanetsEachInItsFile(@TempDir Path dir) throws Exception {
        PlanetStore store = new PlanetStore(dir);
        PlanetSession a = spawned(16), b = spawned(20);
        store.write(PlanetStore.key("BossGalaxy", 0), a.save(), CubeBlocks.INSTANCE);
        store.write(PlanetStore.key("BossGalaxy", 3), b.save(), CubeBlocks.INSTANCE);
        store.write(PlanetStore.key("BossGalaxyX", 0), b.save(), CubeBlocks.INSTANCE);
        assertEquals(List.of(0, 3), store.saved("BossGalaxy", PlanetLayout.MAX_PLANETS));
        PlanetSession t = new PlanetSession(80);
        t.load(store.read(PlanetStore.key("BossGalaxy", 3), CubeBlocks.INSTANCE).orElseThrow());
        assertEquals(20, t.planet().surface(), 1e-9);
        store.delete(PlanetStore.key("BossGalaxy", 0));
        assertEquals(List.of(3), store.saved("BossGalaxy", PlanetLayout.MAX_PLANETS));
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

    static PlanetSession station(Station st) {
        PlanetSession s = new PlanetSession(80);
        s.setRenderDistance(Double.POSITIVE_INFINITY);
        s.spawnStation(st);
        return s;
    }

    @Test void aStationsRecordCarriesItsGravityBox() {
        Station st = StationTest.station("cafe0003");
        st.center = new Vector3d(800, 0, 0);
        PlanetSession s = station(st);
        s.update(1, 1, new Vector3d(800, 160, 0));
        PlanetSession.Msg m = s.peek();
        assertEquals(Layout.MSG_PLANET, m.type());
        assertEquals(88, m.payload().length);
        ByteBuffer b = ByteBuffer.wrap(m.payload()).order(ByteOrder.BIG_ENDIAN);
        assertEquals(PlanetSession.PLANET_FLAT, b.getInt(36));
        assertEquals(1, b.getFloat(44), 1e-6);           // up = +y
        assertEquals(1, b.getFloat(60), 1e-6);           // forward = +z
        assertEquals(6.5 * 80, b.getFloat(64), 1e-3);    // half x: 9 wide, / 2, + 2
        assertEquals(12.5 * 80, b.getFloat(68), 1e-3);   // half y: from -0.5 to 24.5 above the slab's top
        assertEquals(12 * 80, b.getFloat(80), 1e-3);     // the box's center is 12 above the core's
        assertEquals(0, le(m).getFloat(28), 1e-6);       // no occluder
        assertTrue(le(m).getFloat(20) > 6.5 * 80);       // its reach holds the box
        assertSame(st, s.station());
    }

    @Test void landingOnAStationIsOnTopOfItsHighestBlock() {
        Station st = StationTest.station("cafe0004");
        PlanetSession s = station(st);
        int c = st.grid().cellOf(2, 3, 1);
        st.planet.set(c, StationTest.STONE);
        Vector3d at = s.teleportToward(new Vector3d(0, 1, 0));
        assertEquals(0, at.distance(new Vector3d(0, 0.5, 0)), 1e-9); // the core's top
        Vector3d over = s.teleportToward(new Vector3d(2, 10, 1));
        assertEquals(0, over.distance(new Vector3d(2, 3.5, 1)), 1e-9);
    }

    @Test void aStationsBodyIsItsBox() {
        Station st = StationTest.station("cafe0005");
        st.center = new Vector3d(800, 0, 0);
        var body = station(st).body(1 / 80.0);
        assertTrue(body.outside(new Vector3d(10, 1, 0)) <= 0);
        assertTrue(body.outside(new Vector3d(10, -5, 0)) > 0);
    }

    @Test void swapKeepsTheIdAndSendsTheRecordAgain() {
        Station st = StationTest.station("cafe0006");
        PlanetSession s = station(st);
        s.update(1, 1, new Vector3d(0, 80, 0));
        drain(s);
        int id = s.id();
        int c = st.grid().cellOf(st.grid().ox + 1, 0, 0);
        st.planet.set(c, StationTest.STONE);
        st.changed(c);
        st.regrow();
        s.swap(st.planet);
        s.update(1, 1, new Vector3d(0, 80, 0));
        List<PlanetSession.Msg> got = drain(s);
        assertEquals(id, s.id());
        assertEquals(Layout.MSG_PLANET, got.getFirst().type());
        assertEquals(st.planet.chunkCount(), le(got.getFirst()).getInt(24));
        assertTrue(got.stream().noneMatch(m -> m.type() == Layout.MSG_PLANET && (ByteBuffer.wrap(m.payload()).order(ByteOrder.BIG_ENDIAN).getInt(36) & PlanetSession.PLANET_GONE) != 0));
    }

    @Test void standingOnTheCoreIsNotFallingThroughIt() {
        Station st = StationTest.station("cafe0007");
        PlanetSession s = station(st);
        s.update(1, 1, new Vector3d(0, 0.6 * 80, 0)); // on the core's top
        assertFalse(s.marioAtCore());
        assertFalse(s.underground());
    }

    @Test void theLastPlacedCellFollowsARegrow() {
        Station st = StationTest.station("cafe0008");
        PlanetSession s = station(st);
        assertTrue(s.placeBlock(s.galOf(new Vector3d(3, 3, 0)), new Vector3d(0, -1, 0), Material.STONE, s.galOf(new Vector3d(-3, 1, -3))));
        FlatGrid before = st.grid();
        int c = s.lastPlaced();
        assertEquals(1, before.stationY(c));
        st.bounds = st.bounds.with(40, 0, 0);
        st.regrow();
        s.swap(st.planet);
        FlatGrid after = st.grid();
        assertNotEquals(before.n, after.n);
        int moved = s.lastPlaced();
        assertEquals(3, after.stationX(moved));
        assertEquals(1, after.stationY(moved));
        assertEquals(0, after.stationZ(moved));
    }

    @Test void noBlockGoesIntoAPlayerHangingOverTheEdge() {
        Station st = StationTest.station("cafe0009");
        PlanetSession s = station(st);
        // On the slab's edge block (4, 0, 0), its middle 0.25 past the edge (x 4.5): sneaking.
        Vector3d feet = s.galOf(new Vector3d(4.75, 0.5, 0));
        Vector3d eye = s.galOf(new Vector3d(4, 3, 0));
        assertFalse(s.placeBlock(eye, new Vector3d(0, -1, 0), Material.STONE, feet), "on top of the block stood on");
        // Away from the player it still goes down.
        assertTrue(s.placeBlock(s.galOf(new Vector3d(-3, 3, 0)), new Vector3d(0, -1, 0), Material.STONE, feet));
    }
}
