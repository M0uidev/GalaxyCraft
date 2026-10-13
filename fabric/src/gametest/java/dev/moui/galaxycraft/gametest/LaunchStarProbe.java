package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.LaunchStarClient;
import dev.moui.galaxycraft.client.PendingGalaxy;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.UniverseClient;
import dev.moui.galaxycraft.client.Warp;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.universe.SystemIndex;
import dev.moui.galaxycraft.universe.Universe;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.joml.Vector3d;

/**
 * Launch Stars between solar systems, only with -Dgalaxycraft.launchStarProbe=true (tools/gxvoxel.sh
 * launchstar): a star is put on the home planet and a linked one on the first planet of a nearby
 * system, then both flights are made (there and back). Each is measured tick by tick: the step the
 * player's universe position takes (smooth: no jump beyond its neighbors), the ticks that took long
 * (streaming hitches), the floating origin's moves, and where the player ends (on the partner's
 * planet, standing on it).
 */
public final class LaunchStarProbe implements FabricClientGameTest {
    private static final Pattern FLAGS = Pattern.compile("flags=[0-9a-f]+/([0-9a-f]+) .*stage=(\\S+)");
    private boolean ok = true;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.launchStarProbe")) return;
        ctx.waitForScreen(TitleScreen.class);
        waitReal(ctx, mc -> stage().equals("GalaxyCraftSpace"), 120);
        ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        ctx.waitForScreen(CreateWorldScreen.class);
        PendingGalaxy.set(new GalaxyCatalog.Options(3, 32, 48, GalaxyCatalog.First.generated("minecraft:plains", 40),
                GalaxyCatalog.Spacing.NORMAL, 0));
        ctx.clickScreenButton("selectWorld.create");
        waitReal(ctx, mc -> mc.player != null && mc.level != null, 60);
        waitReal(ctx, mc -> onSurface(), 180);
        ctx.waitTicks(100);
        check(ctx.computeOnClient(mc -> UniverseClient.universe() != null), "the world has its universe");
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));

        // A star on the home planet, where Mario stands.
        String home = ctx.computeOnClient(mc -> LaunchStarClient.placeForTest(feet().fma(2 / GravityFrame.SCALE, up()), up(), "PA"));
        Vector3d homeAt = ctx.computeOnClient(mc -> feet());
        // The nearest other system.
        Universe.Star other = ctx.computeOnClient(mc -> Warp.nearby().stream().filter(s -> !s.home()).findFirst().orElse(null));
        check(other != null, "a system nearby");
        if (other == null) {
            end(ctx);
            return;
        }
        int index = SystemIndex.index(other.sector(), 0);
        Double away = ctx.computeOnClient(mc -> other.center().minus(dev.moui.galaxycraft.universe.UPos.of(feet())).length() / GravityFrame.SCALE);
        log("to " + SystemIndex.name(other.sector()) + " planet " + index + ", " + Math.round(away) + " blocks away");
        ctx.runOnClient(mc -> GalaxyOptions.WARP_CORE.set(5));
        ctx.runOnClient(mc -> Warp.to(mc, other));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && PlanetClient.standingOn() != null, 240);
        ctx.waitTicks(60);
        ctx.runOnClient(mc -> PlanetClient.travelTo(index));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && on(index), 240);
        ctx.waitTicks(60);
        String far = ctx.computeOnClient(mc -> LaunchStarClient.placeForTest(feet().fma(2 / GravityFrame.SCALE, up()), up(), "PA"));
        Vector3d farAt = ctx.computeOnClient(mc -> feet());
        check(ctx.computeOnClient(mc -> LaunchStarClient.stars().size() == 2), "two stars are down");

        // There: from the far system's star to home, then back to the far one.
        flight(ctx, far, "far->home", homeAt);
        flight(ctx, home, "home->far", farAt);
        end(ctx);
    }

    /** One flight from that star, measured; it must end standing within 40 blocks of where `to` is. */
    private void flight(ClientGameTestContext ctx, String from, String label, Vector3d to) {
        // Stand at the star (its pull is the wind-up's, here the flight just starts).
        boolean started = ctx.computeOnClient(mc -> LaunchStarClient.launchForTest(from));
        check(started, label + ": the flight starts");
        if (!started) return;
        List<double[]> track = new ArrayList<>(); // x y z, flown, ticks, moves, wall ms
        long last = System.nanoTime(), begin = last;
        int limit = 60 * 120;
        for (int i = 0; i < limit; i++) {
            ctx.waitTicks(1);
            long now = System.nanoTime();
            double[] row = ctx.computeOnClient(mc -> {
                Vector3d p = GalaxyCraftClient.galaxyPos().orElse(new Vector3d());
                int[] pr = LaunchStarClient.progress();
                return new double[] {p.x, p.y, p.z, pr[0], pr[1], UniverseClient.moves(), 0};
            });
            row[6] = (now - last) / 1e6;
            last = now;
            if (row[4] < 0) break;
            track.add(row);
        }
        log(label + ": " + track.size() + " ticks in the air, " + Math.round((System.nanoTime() - begin) / 1e9) + " s real");
        report(label, track);
        ctx.waitTicks(200);
        waitReal(ctx, mc -> !PlanetClient.waitingToLand(), 60);
        PlanetSession standing = ctx.computeOnClient(mc -> PlanetClient.standingOn());
        Vector3d now = ctx.computeOnClient(mc -> feet());
        double d = now.distance(to) * GravityFrame.SCALE;
        log(label + ": ended " + Math.round(d) + " blocks from the partner star, standing on a planet: " + (standing != null));
        check(d < 40, label + ": lands near the partner star");
        check(standing != null, label + ": ends on a planet (its gravity holds)");
    }

    private void report(String label, List<double[]> t) {
        if (t.size() < 4) return;
        double[] step = new double[t.size() - 1];
        for (int i = 1; i < t.size(); i++) {
            double[] a = t.get(i - 1), b = t.get(i);
            step[i - 1] = Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2])) * GravityFrame.SCALE;
        }
        int irregular = 0, hitches = 0;
        double maxStep = 0;
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < step.length; i++) {
            maxStep = Math.max(maxStep, step[i]);
            double[] w = new double[Math.min(i + 3, step.length - 1) - Math.max(i - 2, 0) + 1];
            for (int k = 0; k < w.length; k++) w[k] = step[Math.max(i - 2, 0) + k];
            java.util.Arrays.sort(w);
            double med = w[w.length / 2];
            if (med > 0.05 && Math.abs(step[i] - med) / med > 0.25) {
                irregular++;
                if (bad.size() < 8) bad.add("#" + i + " step " + Math.round(step[i] * 100) / 100.0 + " (neighbors " + Math.round(med * 100) / 100.0 + ")");
            }
        }
        double wall = 0;
        List<String> slow = new ArrayList<>();
        for (double[] r : t) {
            wall = Math.max(wall, r[6]);
            if (r[6] > 100) {
                hitches++;
                slow.add("tick " + Math.round(r[3]) + "/" + Math.round(r[4]) + " " + Math.round(r[6]) + " ms");
            }
        }
        if (!slow.isEmpty()) log(label + ": slow ticks " + slow);
        log(label + ": max step " + Math.round(maxStep * 10) / 10.0 + " blocks/tick, " + irregular + " irregular steps " + bad
                + ", " + hitches + " ticks over 100 ms (longest " + Math.round(wall) + " ms), origin moves "
                + Math.round(t.get(t.size() - 1)[5] - t.get(0)[5]));
        check(irregular <= step.length / 20, label + ": the steps are smooth (" + irregular + " of " + step.length + " irregular)");
    }

    private static Vector3d feet() {
        return GalaxyCraftClient.galaxyPos().orElse(new Vector3d());
    }

    private static Vector3d up() {
        return GalaxyCraftClient.frame().upGal();
    }

    private void end(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        waitReal(ctx, mc -> mc.level == null, 60);
        ctx.setScreen(TitleScreen::new);
        log(ok ? "PASS" : "FAIL");
    }

    private static boolean on(int index) {
        PlanetSession s = PlanetClient.standingOn();
        return s != null && PlanetClient.entry(index).map(e -> e.center().distance(s.center()) < 1).orElse(false);
    }

    private static boolean onSurface() {
        PlanetSession s = PlanetClient.standingOn();
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (s == null || feet == null) return false;
        double h = s.localOf(feet).length() - s.planet().surface();
        return h > -40 && h < 64;
    }

    private static void waitReal(ClientGameTestContext ctx, java.util.function.Predicate<net.minecraft.client.Minecraft> what,
            int seconds) {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!ctx.computeOnClient(what::test)) {
            if (System.nanoTime() > end) throw new AssertionError("Timed out after " + seconds + " s");
            ctx.waitTicks(5);
        }
    }

    private void check(boolean that, String what) {
        log((that ? "ok " : "FAILED ") + what);
        ok &= that;
    }

    private static String stage() {
        Matcher m = FLAGS.matcher(gxdev("ctl", "mbx"));
        return m.find() ? m.group(2) : "-";
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft launchstar] " + msg);
    }

    private static String gxdev(String... args) {
        String[] cmd = new String[args.length + 2];
        cmd[0] = Python.exe();
        cmd[1] = Path.of(System.getProperty("galaxycraft.repoRoot"), "tools/gxdev.py").toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
