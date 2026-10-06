package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PendingGalaxy;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.joml.Vector3d;

/**
 * A galaxy of many planets, only with -Dgalaxycraft.galaxyProbe=true (tools/gxvoxel.sh galaxy,
 * the dev Dolphin booting by itself into GalaxyCraftSpace): Create World's GalaxyCraft tab, a world
 * of 64 planets (the most); from the first one 8 are complete and 56 drawn from afar; Mario taken to the
 * farthest, a block broken there, back to the first and to the farthest again: the edit is kept.
 * Screenshots galaxy-*.png (Dolphin's) and Dolphin's speed at each place.
 */
public final class GalaxyProbe implements FabricClientGameTest {
    private static final Pattern FLAGS = Pattern.compile("flags=[0-9a-f]+/([0-9a-f]+) .*stage=(\\S+)");
    private static final Pattern MAX_SPEED = Pattern.compile("max_speed=(\\S+)");
    private static final int COUNT = 64; // the most: far planets must never leave a complete one without room
    private boolean ok = true;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.galaxyProbe")) return;
        ctx.waitForScreen(TitleScreen.class);
        waitReal(ctx, mc -> stage().equals("GalaxyCraftSpace"), 120);

        ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        ctx.waitForScreen(CreateWorldScreen.class);
        boolean tab = ctx.computeOnClient(mc -> Screens.getWidgets(mc.gui.screen()).stream()
                .filter(w -> w instanceof TabNavigationBar).map(w -> (TabNavigationBar) w)
                .anyMatch(bar -> bar.getTabs().stream().anyMatch(t -> t.getTabTitle().getString().equals("Galaxy"))));
        check(tab, "Create World has a GalaxyCraft tab");
        ctx.runOnClient(mc -> Screens.getWidgets(mc.gui.screen()).stream().filter(w -> w instanceof TabNavigationBar)
                .forEach(w -> ((TabNavigationBar) w).selectTab(3, false)));
        ctx.waitTicks(5);
        ctx.takeScreenshot("galaxy-tab");
        check(PendingGalaxy.peek().isPresent(), "the tab hands its options on");
        PendingGalaxy.set(new GalaxyCatalog.Options(COUNT, 32, 64, GalaxyCatalog.First.generated("minecraft:plains", 40),
                GalaxyCatalog.Spacing.NORMAL, 0));
        ctx.clickScreenButton("selectWorld.create");
        waitReal(ctx, mc -> mc.player != null && mc.level != null, 60);
        waitReal(ctx, mc -> onSurface(), 180);
        List<GalaxyCatalog.Entry> catalog = ctx.computeOnClient(mc -> PlanetClient.catalog());
        check(catalog.size() == COUNT, "the galaxy has " + COUNT + " planets (" + catalog.size() + ")");
        check(catalog.getFirst().radius() == 40 && "minecraft:plains".equals(catalog.getFirst().biome()),
                "the first planet is as chosen");
        waitReal(ctx, mc -> tiers()[0] == 8 && tiers()[1] == COUNT - 8, 240);
        int[] t = ctx.computeOnClient(mc -> tiers());
        check(t[0] == 8 && t[1] == COUNT - 8, "from the first planet: 8 complete, " + (COUNT - 8) + " far (" + t[0] + ", " + t[1] + ")");
        ctx.waitTicks(100);
        place("origin");

        GalaxyCatalog.Entry far = catalog.stream().max((a, b) -> Double.compare(a.center().length(), b.center().length())).orElseThrow();
        GalaxyCatalog.Entry mid = catalog.stream().filter(e -> e != far)
                .min((a, b) -> Double.compare(Math.abs(a.center().length() - far.center().length() / 2),
                        Math.abs(b.center().length() - far.center().length() / 2))).orElseThrow();
        log(String.format("farthest planet %d at %.0f blocks, middle one %d at %.0f", far.index(), far.center().length() / 80,
                mid.index(), mid.center().length() / 80));
        travel(ctx, mid);
        place("middle");
        travel(ctx, far);
        place("far");

        boolean broke = ctx.computeOnClient(mc -> {
            PlanetSession s = PlanetClient.focus();
            Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
            Vector3d up = GalaxyCraftClient.galaxyUp().orElseThrow();
            return s.breakBlock(new Vector3d(up).mul(160).add(feet), new Vector3d(up).negate(), true);
        });
        check(broke, "a block is broken on the farthest planet");
        int cells = ctx.computeOnClient(mc -> Arrays.hashCode(PlanetClient.focus().planet().cells()));

        travel(ctx, catalog.getFirst());
        waitReal(ctx, mc -> !complete(far.index()), 60);
        check(ctx.computeOnClient(mc -> !complete(far.index())), "back at the first planet, the farthest is far again");
        place("back");
        travel(ctx, far);
        int again = ctx.computeOnClient(mc -> Arrays.hashCode(PlanetClient.focus().planet().cells()));
        check(again == cells, "the farthest planet comes back with the block broken");

        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        waitReal(ctx, mc -> mc.level == null, 60);
        check(ctx.computeOnClient(mc -> tiers()[0] == 0 && tiers()[1] == 0), "out of the world, no planet is left");
        ctx.setScreen(TitleScreen::new);
        log(ok ? "PASS" : "FAIL");
    }

    /** Mario onto that planet, waited for until he stands on it. */
    private void travel(ClientGameTestContext ctx, GalaxyCatalog.Entry e) {
        ctx.runOnClient(mc -> PlanetClient.travelTo(e.index()));
        waitReal(ctx, mc -> !PlanetClient.waitingToLand() && onSurface() && on(e), 180);
        ctx.waitTicks(60);
        int[] t = ctx.computeOnClient(mc -> tiers());
        check(on(e), "Mario stands on planet " + e.index());
        check(t[0] <= 8 && t[0] + t[1] == COUNT, "on planet " + e.index() + ": " + t[0] + " complete, " + t[1] + " far");
    }

    /** A screenshot and Dolphin's speed there. */
    private void place(String tag) {
        gxdev("ctl", "shot galaxy-" + tag);
        Matcher m = MAX_SPEED.matcher(gxdev("ctl", "status"));
        log(tag + ": max_speed " + (m.find() ? m.group(1) : "?"));
    }

    private static int[] tiers() {
        return PlanetClient.tiers();
    }

    private static boolean complete(int index) {
        return PlanetClient.planets().stream().anyMatch(s -> s.active() && PlanetClient.catalog().stream()
                .anyMatch(e -> e.index() == index && e.center().distance(s.center()) < 1));
    }

    private static boolean on(GalaxyCatalog.Entry e) {
        PlanetSession s = PlanetClient.standingOn();
        return s != null && s.center().distance(e.center()) < 1;
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
        System.out.println("[GalaxyCraft galaxy] " + msg);
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
