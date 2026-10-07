package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PendingGalaxy;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.UniverseClient;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.universe.GameOrigin;
import dev.moui.galaxycraft.universe.SystemIndex;
import dev.moui.galaxycraft.universe.UPos;
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

        waitReal(ctx, mc -> UniverseClient.starsSent() > 0, 30);
        log("stars on the sky: " + ctx.computeOnClient(mc -> UniverseClient.starsSent()));
        check(ctx.computeOnClient(mc -> UniverseClient.starsSent()) > 100, "the other systems are stars on the sky");
        ctx.runOnClient(mc -> mc.player.setXRot(-30));
        ctx.waitTicks(20);
        gxdev("ctl", "shot universe-stars");
        ctx.runOnClient(mc -> UniverseClient.hideStars(true));
        ctx.waitTicks(20);
        gxdev("ctl", "shot universe-nostars");
        ctx.runOnClient(mc -> UniverseClient.hideStars(false));
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.setXRot(0));
        origin(ctx);
        voidFlight(ctx);
        systems(ctx);
        travel(ctx);

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
            PlanetSession s = PlanetClient.standingOn();
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
        // Counted from home: out there it follows Mario, or goes to a system's center if he flies into one.
        int moves = ctx.computeOnClient(mc -> UniverseClient.moves());
        ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(start));
        ctx.waitTicks(60);
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
        log("before going home: " + republished());
        ctx.runOnClient(mc -> PlanetClient.travelTo(PlanetClient.catalog().getFirst().index()));
        for (int i = 0; i < 6; i++) {
            ctx.waitTicks(10);
            log(String.format("  +%d ticks: %s, landing %s, Mario at %.0f blocks from home", 10 * i + 10, republished(),
                    ctx.computeOnClient(mc -> PlanetClient.waitingToLand()),
                    ctx.computeOnClient(mc -> PlanetClient.marioUniverse() == null ? -1 : PlanetClient.marioUniverse().length() / U)));
        }
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && onSurface(), 180);
        ctx.waitTicks(100);
        check(ctx.computeOnClient(mc -> onSurface()), "back home on the first planet");
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
    }

    /**
     * Another solar system: onto its first planet (the origin goes to its center), a block broken,
     * home and back: the block is still broken, kept in the planet's own file.
     */
    private void systems(ClientGameTestContext ctx) {
        Universe.Star star = ctx.computeOnClient(mc -> UniverseClient.universe().around(UPos.ZERO, 1).stream()
                .filter(s -> !s.home()).findFirst().orElseThrow());
        int index = SystemIndex.index(star.sector(), 0);
        Vector3d center = star.center().minus(UPos.ZERO);
        log(String.format("system %s, %.0f blocks from home, %d planets asked", SystemIndex.name(star.sector()), center.length() / U,
                star.planets()));
        ctx.runOnClient(mc -> PlanetClient.travelTo(index));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && onSurface() && on(index), 240);
        ctx.waitTicks(60);
        check(ctx.computeOnClient(mc -> on(index)), "Mario stands on the other system's first planet");
        check(ctx.computeOnClient(mc -> GameOrigin.offset().distance(center) < 65536), "the origin is that system's center");
        int[] t = ctx.computeOnClient(mc -> PlanetClient.tiers());
        log(String.format("there: %d planets complete, %d far", t[0], t[1]));
        check(t[0] >= 1, "its planets stream in");
        gxdev("ctl", "shot universe-system");
        // The block under his feet: which cell, what it was, broken.
        int[] cellWas = ctx.computeOnClient(mc -> {
            PlanetSession s = PlanetClient.standingOn();
            Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
            Vector3d up = GalaxyCraftClient.galaxyUp().orElseThrow();
            Vector3d eye = new Vector3d(up).mul(160).add(feet), down = new Vector3d(up).negate();
            PlanetSession.Aim aim = s.aim(eye, down);
            if (aim == null) return null;
            int was = s.planet().get(aim.cell());
            return s.breakBlock(eye, down, true) ? new int[] {aim.cell(), was, s.planet().get(aim.cell())} : null;
        });
        check(cellWas != null && cellWas[1] != cellWas[2], "a block is broken there");
        ctx.waitTicks(60);
        int settled = ctx.computeOnClient(mc -> PlanetClient.standingOn().planet().get(cellWas[0]));
        log(String.format("the cell: was %s, broken %s, 3 s later %s", name(ctx, cellWas[1]), name(ctx, cellWas[2]), name(ctx, settled)));
        cellWas[2] = settled; // what the world made of it (water may flow in): kept as that
        int home = ctx.computeOnClient(mc -> PlanetClient.catalog().getFirst().index());
        ctx.runOnClient(mc -> PlanetClient.travelTo(home));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && onSurface() && on(home), 240);
        ctx.waitTicks(100);
        check(ctx.computeOnClient(mc -> GameOrigin.offset().length() == 0), "home again, the origin is home's center");
        java.nio.file.Path file = ctx.computeOnClient(mc -> PlanetClient.planetFile(index));
        waitReal(ctx, mc -> java.nio.file.Files.isRegularFile(file), 30);
        check(file.getFileName().toString().contains(".s" + SystemIndex.name(star.sector()) + ".p0"), "its file is named by its sector: " + file.getFileName());
        ctx.runOnClient(mc -> PlanetClient.travelTo(index));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && onSurface() && on(index), 240);
        ctx.waitTicks(60);
        int again = ctx.computeOnClient(mc -> PlanetClient.standingOn().planet().get(cellWas[0]));
        check(again == cellWas[2], "back there, the block is still broken (" + again + ", broken " + cellWas[2] + ", was " + cellWas[1] + ")");
        ctx.runOnClient(mc -> PlanetClient.travelTo(home));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && onSurface() && on(home), 240);
    }

    /**
     * Pulse and warp: gliding out in the void, sprint held speeds up to hundreds of blocks a second;
     * then a warp while still gliding: Mario lands on the system's first planet and the player stops
     * flying there (it stood still, on the ground, no glide or pulse left).
     */
    private void travel(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
        ctx.waitTicks(20);
        server(ctx, "gamemode survival @a");
        server(ctx, "effect give @a resistance infinite 255 true");
        server(ctx, "item replace entity @a armor.chest with elytra");
        Vector3d out = new Vector3d(0, -1, 0).mul(4000 * U);
        ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(out));
        ctx.waitTicks(60);
        boolean gliding = false;
        for (int attempt = 0; attempt < 4 && !gliding; attempt++) {
            ctx.getInput().holdKeyFor(o -> o.keyJump, 2);
            ctx.waitTicks(5);
            ctx.getInput().holdKeyFor(o -> o.keyJump, 2);
            ctx.waitTicks(10);
            gliding = ctx.computeOnClient(mc -> mc.player.isFallFlying());
        }
        check(gliding, "the elytra open out in the void");
        Vector3d before = ctx.computeOnClient(mc -> PlanetClient.marioUniverse());
        ctx.getInput().holdKey(o -> o.keySprint);
        ctx.waitTicks(100);
        double speed = ctx.computeOnClient(mc -> dev.moui.galaxycraft.client.Flight.pulseSpeed());
        ctx.getInput().releaseKey(o -> o.keySprint);
        Vector3d after = ctx.computeOnClient(mc -> PlanetClient.marioUniverse());
        double went = before == null || after == null ? 0 : after.distance(before) / U;
        log(String.format("pulse: %.1f blocks a tick after 5 s, Mario went %.0f blocks", speed, went));
        check(speed > 10 && went > 500, "the pulse speeds up with sprint held");
        gxdev("ctl", "shot universe-pulse");

        Universe.Star star = ctx.computeOnClient(mc -> dev.moui.galaxycraft.client.Warp.nearby().stream().filter(x -> !x.home())
                .findFirst().orElseThrow());
        int index = SystemIndex.index(star.sector(), 0);
        log("warping, still gliding: " + ctx.computeOnClient(mc -> mc.player.isFallFlying()) + ", to " + SystemIndex.name(star.sector()));
        ctx.runOnClient(mc -> dev.moui.galaxycraft.client.Warp.to(mc, star));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && on(index), 240);
        ctx.waitTicks(100);
        log(String.format("after the warp: gliding %s, on ground %s, no gravity %s, pulse %.2f, screen %s",
                ctx.computeOnClient(mc -> mc.player.isFallFlying()), ctx.computeOnClient(mc -> mc.player.onGround()),
                ctx.computeOnClient(mc -> mc.player.isNoGravity()), ctx.computeOnClient(mc -> dev.moui.galaxycraft.client.Flight.pulseSpeed()),
                ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName())));
        check(ctx.computeOnClient(mc -> !mc.player.isFallFlying()), "after a warp the player is not gliding");
        String where = ctx.computeOnClient(mc -> {
            PlanetSession st = PlanetClient.standingOn();
            Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
            return st == null || feet == null ? "nowhere" : String.format("%.1f blocks above the surface of a planet of radius %.0f, %s",
                    st.localOf(feet).length() - st.planet().surface(), st.planet().surface(), on(index) ? "the one warped to" : "another one");
        });
        check(ctx.computeOnClient(mc -> onSurface() && on(index)), "it stands on the system's first planet (" + where + ")");
        double still = stillness(ctx);
        log(String.format("standing there: Mario moves %.1f units at most", still));
        check(still < 80, "it stays put (not flying off)");
        gxdev("ctl", "shot universe-warp");
        int home = ctx.computeOnClient(mc -> PlanetClient.catalog().getFirst().index());
        ctx.runOnClient(mc -> PlanetClient.travelTo(home));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && onSurface() && on(home), 240);
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
    }

    private static void server(ClientGameTestContext ctx, String command) {
        ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().getCommands()
                .performPrefixedCommand(mc.getSingleplayerServer().createCommandSourceStack(), command)));
        ctx.waitTicks(2);
    }

    private static String name(ClientGameTestContext ctx, int state) {
        return state + " " + ctx.computeOnClient(mc -> PlanetClient.stateName(state));
    }

    private static boolean on(int index) {
        PlanetSession s = PlanetClient.standingOn();
        return s != null && PlanetClient.entry(index).map(e -> e.center().distance(s.center()) < 1).orElse(false);
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
        // Generated planets have hills and valleys: anywhere on the ground near the surface.
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

    private static final Pattern REPUBLISHED = Pattern.compile("republished=(\\S+)");

    /** Why the host sent the scene again (hello, new scene, relink), from its status. */
    private static String republished() {
        Matcher m = REPUBLISHED.matcher(gxdev("ctl", "status"));
        return "republished " + (m.find() ? m.group(1) : "?");
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
