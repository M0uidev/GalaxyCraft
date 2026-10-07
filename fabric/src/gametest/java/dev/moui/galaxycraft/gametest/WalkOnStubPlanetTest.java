package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Shm;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.joml.Vector3d;

/**
 * End to end: tools/fake_galaxy.py publishes two spherical planets; the player must land on
 * planet 1, walk more than a full lap around it with vanilla movement, jump without escaping,
 * and survive the stub dying.
 */
public final class WalkOnStubPlanetTest implements FabricClientGameTest {
    private static final Vector3d[] CENTERS = {new Vector3d(0, 0, 0), new Vector3d(0, 2600, 0)};
    private static final double[] RADII = {800, 600};
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Dolphin owns the shared memory in the demo and in the real-galaxy test.
        if (Boolean.getBoolean("galaxycraft.demo") || Boolean.getBoolean("galaxycraft.galaxy")
                || Boolean.getBoolean("galaxycraft.blocks") || Boolean.getBoolean("galaxycraft.spawn")
                || Boolean.getBoolean("galaxycraft.launcher") || Boolean.getBoolean("galaxycraft.universeProbe")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("tp @a 0 100 0 0 0"); // far above the flat world's ground
            ctx.waitTicks(20);

            Process stub = startStub();
            try {
                ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 400); // waitFor runs on the client thread
                ctx.waitFor(mc -> mc.player.onGround(), 200);
                double alt0 = altitude(ctx);
                log("landed: altitude %.1f units", alt0);
                check(alt0 > -5 && alt0 < 20, "standing on the planet surface, altitude " + alt0);
                ctx.takeScreenshot("galaxycraft-standing");
                checkOverlayExport(ctx);
                checkHostInput(ctx);

                ctx.getInput().holdKey(o -> o.keyUp);
                double minAlt = Double.MAX_VALUE, maxAlt = -Double.MAX_VALUE, travelled = 0;
                Vector3d prev = galaxyPos(ctx);
                for (int i = 0; i < 30; i++) {
                    ctx.waitTicks(20);
                    Vector3d gal = galaxyPos(ctx);
                    double alt = altitude(ctx);
                    minAlt = Math.min(minAlt, alt);
                    maxAlt = Math.max(maxAlt, alt);
                    travelled += prev.angle(gal);
                    prev = gal;
                    log("t=%2ds gal=(%.0f, %.0f, %.0f) alt=%.1f laps=%.2f", i + 1, gal.x, gal.y, gal.z, alt,
                            travelled / (2 * Math.PI));
                }
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.takeScreenshot("galaxycraft-walked");
                check(minAlt > -30 && maxAlt < 60, "stayed on the surface while walking: " + minAlt + ".." + maxAlt);
                check(travelled > 2 * Math.PI, "walked a full lap around the planet: " + travelled + " rad");

                ctx.getInput().pressKey(o -> o.keyJump);
                ctx.waitTicks(40);
                double altJump = altitude(ctx);
                check(altJump < 150 && ctx.computeOnClient(mc -> mc.player.onGround()),
                        "jumping does not escape the planet: " + altJump);

                stub.destroy();
                stub.waitFor();
                ctx.waitTicks(60);
                check(!ctx.computeOnClient(mc -> GalaxyCraftClient.linked()), "unlinked after the stub dies");
                check(ctx.computeOnClient(mc -> mc.player != null && mc.player.isAlive()), "player survives unlink");
                log("PASS");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                stub.destroy();
            }
        }
    }

    private static Vector3d galaxyPos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos()).orElseThrow();
    }

    /** Phase 2: the frame Dolphin composites has a transparent sky and an opaque hotbar. */
    private static void checkOverlayExport(ClientGameTestContext ctx) {
        ctx.waitTicks(10);
        MemorySegment seg = Shm.open(Path.of(Layout.SHM_PATH)).orElseThrow().seg();
        int latest = seg.get(INT, Layout.OFF_OVERLAY);
        int w = seg.get(INT, Layout.OFF_OVERLAY + 4), h = seg.get(INT, Layout.OFF_OVERLAY + 8);
        check(latest >= 0 && latest <= 2 && w > 0 && h > 0, "overlay published: " + w + "x" + h + " in buffer " + latest);
        long px = Layout.OFF_OVERLAY + 32 + latest * Layout.OVERLAY_FRAME_BYTES;
        int skyAlpha = Byte.toUnsignedInt(seg.get(ValueLayout.JAVA_BYTE, px + ((5L * w) + w / 2) * 4 + 3));
        int hotbarAlpha = Byte.toUnsignedInt(seg.get(ValueLayout.JAVA_BYTE, px + (((h - 12L) * w) + w / 2) * 4 + 3));
        check(skyAlpha == 0, "sky is transparent in the overlay (alpha " + skyAlpha + ")");
        check(hotbarAlpha > 128, "hotbar is opaque in the overlay (alpha " + hotbarAlpha + ")");
    }

    /** Phase 2: a key published in InputState (as Dolphin does) reaches Minecraft: E opens the inventory. */
    private static void checkHostInput(ClientGameTestContext ctx) {
        MemorySegment seg = Shm.open(Path.of(Layout.SHM_PATH)).orElseThrow().seg();
        writeInput(seg, new byte[64]);
        ctx.waitTicks(5);
        byte[] e = new byte[64];
        e[8 / 8] |= (byte) (1 << (8 % 8)); // SDL scancode for E
        writeInput(seg, e);
        ctx.waitFor(mc -> mc.gui.screen() instanceof InventoryScreen, 100);
        check(true, "host key E opened the Minecraft inventory");
        writeInput(seg, new byte[64]);
        ctx.waitTicks(5);
        writeInput(seg, e);
        ctx.waitFor(mc -> mc.gui.screen() == null, 100);
        writeInput(seg, new byte[64]);
        check(true, "host key E closed it again");
    }

    private static void writeInput(MemorySegment seg, byte[] keys) {
        long o = Layout.OFF_INPUT;
        int seq = seg.get(INT, o);
        seg.set(INT, o, seq + 1);
        MemorySegment.copy(keys, 0, seg, ValueLayout.JAVA_BYTE, o + 32, 64);
        seg.set(INT, o, seq + 2);
    }

    private static double altitude(ClientGameTestContext ctx) {
        Vector3d gal = galaxyPos(ctx);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < CENTERS.length; i++) {
            double a = gal.distance(CENTERS[i]) - RADII[i];
            if (Math.abs(a) < Math.abs(best)) best = a;
        }
        return best;
    }

    private static Process startStub() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("tools/fake_galaxy.py"))) dir = dir.getParent();
        if (dir == null) throw new AssertionError("tools/fake_galaxy.py not found above " + Path.of("").toAbsolutePath());
        try {
            return new ProcessBuilder(Python.exe(), dir.resolve("tools/fake_galaxy.py").toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        } catch (IOException e) {
            throw new AssertionError("could not start fake_galaxy.py", e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        log("ok: %s", what);
    }

    private static void log(String fmt, Object... args) {
        System.out.println("[GalaxyCraft gametest] " + String.format(fmt, args));
    }
}
