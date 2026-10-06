package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PendingGalaxy;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.UniverseClient;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.universe.GameOrigin;
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
 * The infinite universe in the real game, only with -Dgalaxycraft.universeProbe=true (tools/gxvoxel.sh
 * universe, the dev Dolphin booting by itself into GalaxyCraftSpace). The floating origin: Mario
 * standing on the home planet while the origin is pinned a million blocks away (the game's floats
 * that far out) and back, the planet's collision kept; out in the void past the home system, the
 * origin following him with no jump in where he is. Screenshots universe-*.png.
 */
public final class UniverseProbe implements FabricClientGameTest {
    private static final Pattern FLAGS = Pattern.compile("flags=[0-9a-f]+/([0-9a-f]+) .*stage=(\\S+)");
    private static final double U = 80;
    private boolean ok = true;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.universeProbe")) return;
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

        origin(ctx);
        voidFlight(ctx);

        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        waitReal(ctx, mc -> mc.level == null, 60);
        check(ctx.computeOnClient(mc -> GameOrigin.offset().length() == 0), "out of the world, the origin is home again");
        ctx.setScreen(TitleScreen::new);
        log(ok ? "PASS" : "FAIL");
    }

    /** Standing on the planet, the origin a million blocks off and back: he stays standing. */
    private void origin(ClientGameTestContext ctx) {
        double still = stillness(ctx);
        log(String.format("at home, standing still: Mario moves %.3f units at most", still));
        gxdev("ctl", "shot universe-home");
        int moves = ctx.computeOnClient(mc -> UniverseClient.moves());
        // The origin a million blocks off: the game's numbers are that big.
        ctx.runOnClient(mc -> UniverseClient.pin(new Vector3d(-1_000_000, 0, 0)));
        waitReal(ctx, mc -> UniverseClient.gameEpoch() == GameOrigin.epoch() && UniverseClient.moves() > moves, 30);
        ctx.waitTicks(100);
        check(ctx.computeOnClient(mc -> onSurface()), "Mario stays on the planet with the game's numbers a million blocks out");
        double far = stillness(ctx);
        log(String.format("a million blocks out, standing still: Mario moves %.3f units at most", far));
        gxdev("ctl", "shot universe-million");
        boolean broke = ctx.computeOnClient(mc -> {
            PlanetSession s = PlanetClient.focus();
            Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
            Vector3d up = GalaxyCraftClient.galaxyUp().orElseThrow();
            return s.breakBlock(new Vector3d(up).mul(160).add(feet), new Vector3d(up).negate(), true);
        });
        check(broke, "a block is broken out there");
        ctx.waitTicks(100);
        check(ctx.computeOnClient(mc -> onSurface()), "and Mario stands in its hole, not through it");
        // Let go: Mario is 16 cells and more from it, so it comes back at once, planet or not.
        ctx.runOnClient(mc -> UniverseClient.pin(null));
        waitReal(ctx, mc -> GameOrigin.offset().length() == 0 && UniverseClient.gameEpoch() == GameOrigin.epoch(), 30);
        ctx.waitTicks(100);
        check(ctx.computeOnClient(mc -> onSurface()), "the origin home again, Mario still on the planet");
        double back = stillness(ctx);
        log(String.format("home again: Mario moves %.3f units at most", back));
        check(back < 1, "standing still at home is still");
    }

    /** Out past the home system in the void, moving on: the origin follows, Mario's place never jumps. */
    private void voidFlight(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
        ctx.waitTicks(40);
        Vector3d dir = new Vector3d(0, 1, 0);
        Vector3d start = new Vector3d(dir).mul(4500 * U);
        ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(start));
        ctx.waitTicks(60);
        int moves = ctx.computeOnClient(mc -> UniverseClient.moves());
        List<Vector3d> path = new ArrayList<>();
        // 6000 blocks more, 25 a tick: the origin should move once or twice on the way.
        for (int t = 0; t < 240; t++) {
            Vector3d at = new Vector3d(dir).mul((4500 + 25 * t) * U);
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(at));
            ctx.waitTick();
            path.add(ctx.computeOnClient(mc -> PlanetClient.marioUniverse()));
        }
        ctx.waitTicks(30);
        int made = ctx.computeOnClient(mc -> UniverseClient.moves()) - moves;
        double worst = 0;
        for (int i = 1; i < path.size(); i++)
            if (path.get(i) != null && path.get(i - 1) != null) worst = Math.max(worst, path.get(i).distance(path.get(i - 1)) / U);
        log(String.format("void flight: the origin moved %d times; Mario's biggest step %.1f blocks (25 a tick asked)", made, worst));
        check(made >= 1, "the origin follows Mario out in the void");
        check(worst < 200, "Mario's place never jumps by a cell (819 blocks)");
        Vector3d game = ctx.computeOnClient(mc -> GameOrigin.toGame(PlanetClient.marioUniverse()));
        log(String.format("Mario in the game's numbers: %.0f %.0f %.0f units", game.x, game.y, game.z));
        check(game.length() < 5 * 65536, "the game's numbers stay small out there");
        gxdev("ctl", "shot universe-void");
        // Home again.
        ctx.runOnClient(mc -> PlanetClient.travelTo(PlanetClient.catalog().getFirst().index()));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && onSurface(), 180);
        ctx.waitTicks(100);
        check(ctx.computeOnClient(mc -> onSurface()), "back home on the first planet");
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
    }

    /** How far Mario's reported position strays over two seconds standing still (units). */
    private static double stillness(ClientGameTestContext ctx) {
        Vector3d first = ctx.computeOnClient(mc -> PlanetClient.marioUniverse());
        double worst = 0;
        for (int i = 0; i < 40; i++) {
            ctx.waitTick();
            Vector3d p = ctx.computeOnClient(mc -> PlanetClient.marioUniverse());
            if (p != null && first != null) worst = Math.max(worst, p.distance(first));
        }
        return worst;
    }

    private static boolean onSurface() {
        PlanetSession s = PlanetClient.standingOn();
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (s == null || feet == null) return false;
        return Math.abs(s.localOf(feet).length() - s.planet().surface()) < 3;
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
        System.out.println("[GalaxyCraft universe] " + msg);
    }

    private static String gxdev(String... args) {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "python3";
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
