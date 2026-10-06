package dev.moui.galaxycraft.proto;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtoTest {
    static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @TempDir Path dir;

    @Test void layoutMatchesHeader() {
        assertEquals(64, Layout.OFF_WORLD);
        assertEquals(128, Layout.OFF_PLAYER);
        assertEquals(224, Layout.OFF_INPUT);
        assertEquals(320, Layout.OFF_GAMECAM);
        assertEquals(4096, Layout.OFF_RING_S2M);
        assertEquals(4198416, Layout.OFF_RING_M2S);
        assertEquals(5251072, Layout.OFF_OVERLAY);
        assertEquals(30134304L, Layout.TOTAL_SIZE);
    }

    @Test void worldWithoutGravityCannotAnchor() {
        // SMG2's title screen has a Mario but no gravity: nothing to stand on, so no anchoring.
        var none = new Seqlock.WorldState(1, 1, new Vector3d(), new Vector3d(0, 0, 0), Layout.WORLD_ANCHOR);
        var tiny = new Seqlock.WorldState(1, 1, new Vector3d(0, 1e-4, 0), new Vector3d(), Layout.WORLD_ANCHOR);
        var real = new Seqlock.WorldState(3, 1, new Vector3d(0, -1, 0), new Vector3d(), Layout.WORLD_ANCHOR);
        assertFalse(none.hasGravity());
        assertFalse(tiny.hasGravity());
        assertTrue(real.hasGravity());
    }

    @Test void followFlag() {
        var g = new Vector3d(0, -1, 0);
        assertTrue(new Seqlock.WorldState(1, 1, g, new Vector3d(), Layout.WORLD_FOLLOW).follow());
        assertFalse(new Seqlock.WorldState(1, 1, g, new Vector3d(), Layout.WORLD_ANCHOR).follow());
        assertEquals(11, Layout.VERSION);
    }

    @Test void readTextFromTheRing() {
        MemorySegment s = java.lang.foreign.Arena.ofAuto().allocate(1024, 8);
        var le = java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        s.set(le, Layout.OFF_TEXT, 6);       // seq
        s.set(le, Layout.OFF_TEXT + 4, 3);   // count
        s.set(le, Layout.OFF_TEXT + 8, 'a');
        s.set(le, Layout.OFF_TEXT + 16, 'c');
        var t = Seqlock.readText(s).orElseThrow();
        assertEquals(3, t.count());
        assertEquals('a', t.codepoints()[0]);
        assertEquals('c', t.codepoints()[2]);
    }

    @Test void openMissingFileIsEmpty() {
        assertTrue(Shm.open(dir.resolve("nope")).isEmpty());
    }

    @Test void writePlayerUsesCOffsets() throws Exception {
        Shm shm = Shm.create(dir.resolve("shm"));
        MemorySegment s = shm.seg();
        Seqlock.writePlayer(s, new Seqlock.PlayerOut(42, new Vector3d(1, 2, 3), new Vector3d(0, 0, -1),
                new Vector3d(0, 1, 0), 70f, 162f, true, new Vector3d(0, 130, -320), Layout.VIEW_FRONT, 9));
        assertEquals(2, s.get(I, 128));                 // seq even after one write
        assertEquals(1, s.get(I, 128 + 4));             // on ground flag
        assertEquals(42L, s.get(L, 128 + 8));
        assertEquals(2f, s.get(F, 128 + 16 + 4));       // pos.y
        assertEquals(-1f, s.get(F, 128 + 28 + 8));      // look.z
        assertEquals(162f, s.get(F, 128 + 56));         // eye height
        assertEquals(-320f, s.get(F, 128 + 60 + 8));    // cam_offset.z
        assertEquals(Layout.VIEW_FRONT, s.get(I, 128 + 72));
        assertEquals(9, s.get(I, 128 + 76));
    }

    @Test void writePlayerFlagsWalkingAndPlus() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        Seqlock.writePlayer(s, new Seqlock.PlayerOut(1, new Vector3d(), new Vector3d(0, 0, -1), new Vector3d(0, 1, 0), 70f,
                162f, false, new Vector3d(), Layout.VIEW_FIRST, 3, false, false, false, false, true, true, false));
        assertEquals(Layout.PLAYER_WALKING | Layout.PLAYER_PLUS, s.get(I, 128 + 4));
        assertEquals(32, Layout.PLAYER_WALKING);
        assertEquals(64, Layout.PLAYER_PLUS);
    }

    @Test void writePlayerFlagsMinecraftsFeel() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        Seqlock.writePlayer(s, new Seqlock.PlayerOut(1, new Vector3d(), new Vector3d(0, 0, -1), new Vector3d(0, 1, 0), 70f,
                162f, false, new Vector3d(), Layout.VIEW_FIRST, 3, false, false, false, false, false, false, true));
        assertEquals(Layout.PLAYER_MC_FEEL, s.get(I, 128 + 4));
        assertEquals(128, Layout.PLAYER_MC_FEEL); // GXC_PLAYER_MC_FEEL
    }

    @Test void readGameCameraSlot() throws Exception {
        MemorySegment s = Shm.create(dir.resolve("shm")).seg();
        assertTrue(Seqlock.readGameCamera(s).isEmpty());
        s.set(I, 320 + 4, Layout.GAMECAM_VALID);
        s.set(F, 320 + 16 + 4, 500f);   // cam_pos.y
        s.set(F, 320 + 28 + 8, -1f);    // cam_dir.z
        s.set(F, 320 + 52, 45f);        // fov
        s.set(F, 320 + 68, 1f);         // mario_front.x
        s.set(I, 320, 2);
        var c = Seqlock.readGameCamera(s).orElseThrow();
        assertTrue(c.valid());
        assertFalse(c.demo());
        assertEquals(500, c.camPos().y);
        assertEquals(-1, c.camDir().z);
        assertEquals(45f, c.fovY());
        assertEquals(1, c.marioFront().x);
    }

    @Test void readWorldAbsentUntilWritten() throws Exception {
        Shm shm = Shm.create(dir.resolve("shm"));
        assertTrue(Seqlock.readWorld(shm.seg()).isEmpty());
        MemorySegment s = shm.seg();
        s.set(I, 64 + 4, 5);
        s.set(L, 64 + 8, 99L);
        s.set(F, 64 + 16 + 4, -1f);
        s.set(F, 64 + 28, 7f);
        s.set(I, 64 + 40, Layout.WORLD_ANCHOR);
        s.set(I, 64, 2);
        var w = Seqlock.readWorld(s).orElseThrow();
        assertEquals(5, w.sceneId());
        assertEquals(99L, w.frameId());
        assertEquals(new Vector3d(0, -1, 0), w.gravity());
        assertEquals(7.0, w.queryPos().x);
        assertTrue(w.anchor());
    }

    @Test void readWorldRejectsOddSeq() throws Exception {
        Shm shm = Shm.create(dir.resolve("shm"));
        shm.seg().set(I, 64, 3);
        assertTrue(Seqlock.readWorld(shm.seg()).isEmpty());
    }

    @Test void readInputSlot() throws Exception {
        Shm shm = Shm.create(dir.resolve("shm"));
        MemorySegment s = shm.seg();
        assertTrue(Seqlock.readInput(s).isEmpty());
        s.set(I, 224 + 4, 3);
        s.set(ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 224 + 8, 12.5);
        s.set(ValueLayout.JAVA_BYTE, 224 + 32 + 8, (byte) 0x20);   // key 69
        s.set(I, 224, 2);
        var in = Seqlock.readInput(s).orElseThrow();
        assertEquals(3, in.buttons());
        assertEquals(12.5, in.mouseX());
        assertEquals(0x20, in.keys()[8]);
    }

    @Test void ringRoundtrip() throws Exception {
        Ring r = new Ring(Shm.create(dir.resolve("shm")).seg(), Layout.OFF_RING_S2M);
        assertTrue(r.push(4, new byte[] {1, 2, 3}));
        var m = r.pop().orElseThrow();
        assertEquals(4, m.type());
        assertArrayEquals(new byte[] {1, 2, 3}, m.payload());
        assertTrue(r.pop().isEmpty());
    }

    @Test void ringWrapsAround() throws Exception {
        Ring r = new Ring(Shm.create(dir.resolve("shm")).seg(), Layout.OFF_RING_S2M);
        byte[] p = new byte[51200];
        for (int i = 0; i < p.length; i++) p[i] = (byte) i;
        for (int i = 0; i < 200; i++) {
            assertTrue(r.push(4, p));
            assertArrayEquals(p, r.pop().orElseThrow().payload());
        }
    }

    @Test void ringFullReturnsFalse() throws Exception {
        Ring r = new Ring(Shm.create(dir.resolve("shm")).seg(), Layout.OFF_RING_S2M);
        byte[] big = new byte[Layout.KCL_CHUNK_MAX];
        int n = 0;
        while (r.push(4, big)) n++;
        assertEquals(Layout.RING_S2M_CAP / (Layout.KCL_CHUNK_MAX + 8), n);
    }

    @Test void readsRingWrittenByPython() throws Exception {
        Path shm = dir.resolve("py");
        Path repo = Path.of(System.getProperty("galaxycraft.repoRoot"));
        String script = String.join("\n",
                "import sys; sys.path.insert(0, sys.argv[1])",
                "import gxproto",
                "s = gxproto.Shm(path=sys.argv[2], create=True)",
                "gxproto.Ring(s, gxproto.RING_S2M_OFF).push(7, bytes(range(10)))");
        Process p = new ProcessBuilder("python3", "-c", script, repo.resolve("tools").toString(), shm.toString())
                .inheritIO().start();
        assertEquals(0, p.waitFor());
        Ring r = new Ring(Shm.open(shm).orElseThrow().seg(), Layout.OFF_RING_S2M);
        var m = r.pop().orElseThrow();
        assertEquals(7, m.type());
        byte[] want = new byte[10];
        Arrays.setAll(new int[10], i -> want[i] = (byte) i);
        assertArrayEquals(want, m.payload());
        assertTrue(Files.size(shm) == Layout.TOTAL_SIZE);
    }
}
