package dev.moui.galaxycraft.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Shm;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeClientTest {
    static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @TempDir Path dir;

    static class Recorder implements BridgeClient.PartListener {
        final List<Integer> scenes = new ArrayList<>();
        final Map<Integer, byte[]> kcl = new HashMap<>();
        final Map<Integer, double[]> mtx = new HashMap<>();
        int upserts;
        @Override public void onUpsert(int id, double[] m, byte[] k) { upserts++; kcl.put(id, k); mtx.put(id, m); }
        @Override public void onRemove(int id) { kcl.remove(id); }
        @Override public void onScene(int id) { scenes.add(id); }
    }

    @Test void missingShmIsNotLinkedAndDoesNotThrow() {
        var c = new BridgeClient(dir.resolve("absent"), () -> 10_000L, new Recorder());
        c.poll();
        assertFalse(c.linked());
        assertTrue(c.world().isEmpty());
    }

    @Test void hostFlagSaysWhetherTheGameIsHandedOver() throws Exception {
        // Dolphin's link toggle (Ctrl+G) keeps the heartbeat but clears host_flags bit 0.
        Path p = dir.resolve("shm");
        var seg = Shm.create(p).seg();
        seg.set(I, 0, Layout.MAGIC);
        seg.set(I, 4, Layout.VERSION);
        seg.set(L, 16, 10_000L);
        seg.set(I, Layout.H_HOST_FLAGS, 1);
        var c = new BridgeClient(p, () -> 10_100L, new Recorder());
        c.poll();
        assertTrue(c.linked() && c.gameLinked());
        seg.set(I, Layout.H_HOST_FLAGS, 0);
        c.poll();
        assertTrue(c.linked());
        assertFalse(c.gameLinked());
    }

    @Test void inWorldIsReportedInModFlags() throws Exception {
        // Dolphin shows Minecraft's menus over the game while the mod is not in a world.
        Path p = dir.resolve("shm");
        var seg = Shm.create(p).seg();
        seg.set(I, 0, Layout.MAGIC);
        seg.set(I, 4, Layout.VERSION);
        seg.set(L, 16, 10_000L);
        var c = new BridgeClient(p, () -> 10_100L, new Recorder());
        c.poll();
        assertEquals(0, seg.get(I, Layout.H_MOD_FLAGS) & Layout.MOD_IN_WORLD);
        c.setInWorld(true);
        c.poll();
        assertEquals(Layout.MOD_IN_WORLD, seg.get(I, Layout.H_MOD_FLAGS) & Layout.MOD_IN_WORLD);
        c.setInWorld(false);
        c.poll();
        assertEquals(0, seg.get(I, Layout.H_MOD_FLAGS) & Layout.MOD_IN_WORLD);
    }

    @Test void enteringIsReportedOnlyInAWorld() throws Exception {
        // Dolphin keeps the game silent while a world is entered behind Minecraft's screen.
        Path p = dir.resolve("shm");
        var seg = Shm.create(p).seg();
        seg.set(I, 0, Layout.MAGIC);
        seg.set(I, 4, Layout.VERSION);
        seg.set(L, 16, 10_000L);
        var c = new BridgeClient(p, () -> 10_100L, new Recorder());
        c.setEntering(true);
        c.poll();
        assertEquals(0, seg.get(I, Layout.H_MOD_FLAGS));
        c.setInWorld(true);
        c.poll();
        assertEquals(Layout.MOD_IN_WORLD | Layout.MOD_ENTERING, seg.get(I, Layout.H_MOD_FLAGS));
        c.setEntering(false);
        c.poll();
        assertEquals(Layout.MOD_IN_WORLD, seg.get(I, Layout.H_MOD_FLAGS));
    }

    @Test void staleHeartbeatIsNotLinked() throws Exception {
        Path p = dir.resolve("shm");
        var seg = Shm.create(p).seg();
        seg.set(I, 0, Layout.MAGIC);
        seg.set(I, 4, Layout.VERSION);
        seg.set(L, 16, 1_000L);
        AtomicLong now = new AtomicLong(1_500L);
        var c = new BridgeClient(p, now::get, new Recorder());
        c.poll();
        assertTrue(c.linked());
        now.set(1_000L + Layout.HEARTBEAT_TIMEOUT_MS + 1);
        c.poll();
        assertFalse(c.linked());
    }

    @Test void receivesPartsFromFakeGalaxy() throws Exception {
        Path p = dir.resolve("stub");
        Path repo = Path.of(System.getProperty("galaxycraft.repoRoot"));
        Process stub = new ProcessBuilder("python3", repo.resolve("tools/fake_galaxy.py").toString(),
                "--once", "--shm", p.toString()).redirectErrorStream(true).start();
        try {
            var rec = new Recorder();
            var c = new BridgeClient(p, () -> System.nanoTime() / 1_000_000L, rec);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && rec.kcl.size() < 2) {
                c.poll();
                Thread.sleep(10);
            }
            assertTrue(c.linked());
            assertEquals(1, rec.scenes.get(0));
            assertEquals(2, rec.kcl.size());
            assertTrue(rec.kcl.get(1).length > 1000);
            assertEquals(2600.0, rec.mtx.get(2)[7], 1e-6);   // planet 2 translation y
            assertTrue(c.world().isPresent());
        } finally {
            stub.destroy();
            stub.waitFor();
            Files.deleteIfExists(p);
        }
    }
}
