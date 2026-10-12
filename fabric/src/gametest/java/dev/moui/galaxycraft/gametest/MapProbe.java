package dev.moui.galaxycraft.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moui.galaxycraft.client.PendingGalaxy;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.UniverseClient;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

/**
 * The 3D map in the real game (tools/gxvoxel.sh map): in a new world, the map key opens it; hover
 * a star and a planet, double click into a system, Backspace back. Screenshots map-*.png (in
 * fabric/build/run/clientGameTest/screenshots). Logs what the screen holds after each step.
 */
public final class MapProbe implements FabricClientGameTest {
    private static final Pattern FLAGS = Pattern.compile("flags=[0-9a-f]+/([0-9a-f]+) .*stage=(\\S+)");
    private boolean ok = true;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.mapProbe")) return;
        ctx.waitForScreen(TitleScreen.class);
        waitReal(ctx, mc -> stage().equals("GalaxyCraftSpace"), 120);
        ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        ctx.waitForScreen(CreateWorldScreen.class);
        PendingGalaxy.set(new GalaxyCatalog.Options(8, 48, 96, GalaxyCatalog.First.generated("random", 48),
                GalaxyCatalog.Spacing.NORMAL, 0));
        ctx.clickScreenButton("selectWorld.create");
        waitReal(ctx, mc -> mc.player != null && mc.level != null, 60);
        waitReal(ctx, mc -> PlanetClient.standingOn() != null, 180);
        waitReal(ctx, mc -> mc.gui.screen() == null, 90); // the entering screen goes away by itself
        ctx.waitTicks(40);
        check(ctx.computeOnClient(mc -> UniverseClient.universe() != null), "the universe is there");

        ctx.getInput().pressKey(o -> mapKey(o.keyMappings));
        ctx.waitTicks(10);
        String name = ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
        log("screen after the map key: " + name);
        check(name.equals("MapScreen"), "the map key opens the map");
        ctx.takeScreenshot("map-universe");

        // The cursor takes window pixels, the screen lays out in gui units.
        int k = ctx.computeOnClient(mc -> (int) mc.getWindow().getGuiScale());
        int w = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth()) * k;
        int h = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight()) * k;
        log("window " + w + "x" + h + " (gui scale " + k + ")");
        // The view is centered on Mario: hover where home's star is (the middle) and a little off.
        int[][] spots = {{w / 2, h / 2}, {w / 2 + 40 * k, h / 2 - 25 * k}, {w / 2 - 60 * k, h / 2 + 30 * k}};
        for (int i = 0; i < spots.length; i++) {
            ctx.getInput().setCursorPos(spots[i][0], spots[i][1]);
            ctx.waitTicks(5);
            ctx.takeScreenshot("map-hover-" + i);
        }
        ctx.getInput().setCursorPos(w / 2, h / 2);
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTicks(3);
        ctx.takeScreenshot("map-picked");
        ctx.getInput().pressKey(257 /* Enter */);
        ctx.waitTicks(5);
        ctx.takeScreenshot("map-enter-key");
        // The panel's "Enter system" button (second button, 160 wide at the panel's left).
        ctx.getInput().setCursorPos(w - 90 * k, 116 * k);
        ctx.waitTicks(2);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTicks(5);
        ctx.takeScreenshot("map-system");
        ctx.getInput().scroll(0, 3);
        ctx.waitTicks(3);
        ctx.takeScreenshot("map-system-zoomed");
        ctx.getInput().pressKey(259 /* Backspace */);
        ctx.waitTicks(5);
        ctx.takeScreenshot("map-back");

        ctx.runOnClient(mc -> mc.gui.setScreen(null));
        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        waitReal(ctx, mc -> mc.level == null, 60);
        ctx.setScreen(TitleScreen::new);
        log(ok ? "PASS" : "FAIL");
    }

    private static KeyMapping mapKey(KeyMapping[] all) {
        for (KeyMapping k : all) if (k.getName().startsWith("key.galaxycraft") && k.getName().endsWith("map")) return k;
        throw new AssertionError("no map key mapping");
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
        System.out.println("[GalaxyCraft map] " + msg);
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
