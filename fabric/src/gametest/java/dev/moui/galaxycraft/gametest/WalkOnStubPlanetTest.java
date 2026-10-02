package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Vector3d;

/**
 * End to end: tools/fake_galaxy.py publishes two spherical planets; the player must land on
 * planet 1, walk more than a full lap around it with vanilla movement, jump without escaping,
 * and survive the stub dying.
 */
public final class WalkOnStubPlanetTest implements FabricClientGameTest {
    private static final Vector3d[] CENTERS = {new Vector3d(0, 0, 0), new Vector3d(0, 2600, 0)};
    private static final double[] RADII = {800, 600};

    @Override
    public void runTest(ClientGameTestContext ctx) {
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
            return new ProcessBuilder("python3", dir.resolve("tools/fake_galaxy.py").toString())
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
