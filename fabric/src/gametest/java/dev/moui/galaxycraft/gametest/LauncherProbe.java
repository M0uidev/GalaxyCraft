package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.joml.Vector3d;

/**
 * GalaxyCraft as the player gets it, only with -Dgalaxycraft.launcher=true (tools/gxvoxel.sh
 * launch, with the dev Dolphin booting by itself into GalaxyCraftSpace): Minecraft's title screen
 * (no Multiplayer), a world made through Create World, the player put on its home planet, a block
 * broken; back to the title (Mario held, Dolphin's menu mode), and into the world again: the
 * planet as it was left and the player where they stood.
 */
public final class LauncherProbe implements FabricClientGameTest {
    private static final Pattern FLAGS = Pattern.compile("flags=[0-9a-f]+/([0-9a-f]+) .*stage=(\\S+)");
    private static final int BOOT_SPACE = 1024, HOLD = 2048;
    private boolean ok = true;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.launcher")) return;
        ctx.waitForScreen(TitleScreen.class);
        boolean multiplayer = ctx.computeOnClient(mc -> Screens.getWidgets(mc.gui.screen()).stream()
                .anyMatch(w -> key(w, "menu.multiplayer") || key(w, "menu.online")));
        check(!multiplayer, "the title screen has no Multiplayer nor Realms");
        waitReal(ctx, mc -> mbxFlags()[1].equals("GalaxyCraftSpace"), 120);
        String[] mbx = mbxFlags();
        check(mbx[1].equals("GalaxyCraftSpace"), "SMG2 booted into GalaxyCraftSpace by itself");
        check(hostFlags(mbx) == (BOOT_SPACE | HOLD), "at the title, Mario is held (host flags " + mbx[0] + ")");
        check(muted(), "at the title, the game is silent");
        ctx.takeScreenshot("launch-title");

        ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        ctx.waitForScreen(CreateWorldScreen.class);
        String type = ctx.computeOnClient(mc -> ((CreateWorldScreen) mc.gui.screen()).getUiState().getWorldType()
                .preset().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
        check(type.equals("galaxycraft:galaxy"), "a new world is of type GalaxyCraft (" + type + ")");
        ctx.takeScreenshot("launch-create");
        ctx.clickScreenButton("selectWorld.create");
        waitReal(ctx, mc -> mc.player != null && mc.level != null, 60);
        waitReal(ctx, mc -> PlanetClient.galaxy() != null && onSurface(), 180);
        check(PlanetClient.galaxy() != null, "the world has a galaxy");
        check(ctx.computeOnClient(mc -> onSurface()), "the player stands on the home planet's surface");
        String world = ctx.computeOnClient(mc -> mc.getSingleplayerServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT).normalize().getFileName().toString());
        ctx.waitTicks(60);
        mbx = mbxFlags();
        check((hostFlags(mbx) & HOLD) == 0, "in the world, Mario is not held (host flags " + mbx[0] + ")");
        ctx.takeScreenshot("launch-world");

        waitReal(ctx, mc -> onSurface(), 30); // linked again if the scene's news came after the landing
        // A block broken under the player: the planet is changed, and must come back so.
        boolean broke = ctx.computeOnClient(mc -> {
            PlanetSession s = PlanetClient.focus();
            Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
            Vector3d up = GalaxyCraftClient.galaxyUp().orElseThrow();
            Vector3d eye = new Vector3d(up).mul(160).add(feet);
            return s.breakBlock(eye, new Vector3d(up).negate(), true);
        });
        check(broke, "a block under the player is broken");
        ctx.waitTicks(120); // the spot is saved every 5 s
        waitReal(ctx, mc -> onSurface(), 30);
        int cells = ctx.computeOnClient(mc -> Arrays.hashCode(PlanetClient.focus().planet().cells()));
        Vector3d before = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElseThrow());

        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        waitReal(ctx, mc -> mc.level == null, 60);
        ctx.waitTicks(40);
        mbx = mbxFlags();
        check(hostFlags(mbx) == (BOOT_SPACE | HOLD), "back at the title, Mario is held again (host flags " + mbx[0] + ")");
        check(ctx.computeOnClient(mc -> PlanetClient.planets().stream().noneMatch(PlanetSession::active)),
                "no planet stays loaded out of the world");
        ctx.takeScreenshot("launch-back");

        ctx.runOnClient(mc -> mc.createWorldOpenFlows().openWorld(world, () -> mc.gui.setScreen(new TitleScreen())));
        waitReal(ctx, mc -> mc.player != null && mc.level != null, 60);
        // Entering, behind Minecraft's screen, the game is not heard: only once the zoom has begun.
        // The sound comes back as the screen fades into the zoom (its last 300 ms): heard only then.
        int samples = 0, loud = 0, lastQuiet = 0;
        // The entering screen comes up the tick after Minecraft's loading screen goes.
        for (int t = 0; t < 40 && ctx.computeOnClient(mc -> mc.gui.screen() == null); t++) ctx.waitTicks(1);
        long end = System.nanoTime() + 120_000_000_000L;
        while (ctx.computeOnClient(mc -> mc.gui.screen() != null) && System.nanoTime() < end) {
            samples++;
            if (muted()) lastQuiet = samples;
            else loud++;
            ctx.waitTicks(5);
        }
        check(lastQuiet > 0 && loud <= 2 && samples - lastQuiet == loud,
                "entering a world, the game is silent until the fade (" + lastQuiet + " quiet, then " + loud + " heard)");
        waitReal(ctx, mc -> onSurface(), 120);
        waitReal(ctx, mc -> mc.gui.screen() == null, 30);
        ctx.waitTicks(60);
        check(!muted(), "after the zoom from space the game is heard again");
        // GalaxyCraftSpace plays no music of its own: Minecraft's is the world's.
        int music = debugWord(DBG_MUSIC);
        check(music == 0, "SMG2's galaxy music never played (" + (music >>> 1) + " frames)");
        int again = ctx.computeOnClient(mc -> PlanetClient.focus().active()
                ? Arrays.hashCode(PlanetClient.focus().planet().cells()) : 0);
        check(again == cells, "the planet comes back as it was left");
        Vector3d after = ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyPos().orElse(new Vector3d(1e9)));
        check(after.distance(before) < 200, String.format("the player is back where they stood (%.0f units off)",
                after.distance(before)));
        ctx.takeScreenshot("launch-again");
        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        waitReal(ctx, mc -> mc.level == null, 60);
        ctx.setScreen(TitleScreen::new); // as the pause menu's Save and Quit does; game tests end there
        log(ok ? "PASS" : "FAIL");
    }

    /**
     * Waits up to that many seconds of real time (game test ticks run faster than real time, and
     * SMG2 runs at its own pace); fails the test past it.
     */
    private static void waitReal(ClientGameTestContext ctx, java.util.function.Predicate<net.minecraft.client.Minecraft> what,
            int seconds) {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!ctx.computeOnClient(what::test)) {
            if (System.nanoTime() > end) throw new AssertionError("Timed out after " + seconds + " s");
            ctx.waitTicks(5);
        }
    }

    /** The player stands on a planet: within 3 blocks of its surface (not held at its center). */
    private static boolean onSurface() {
        PlanetSession s = PlanetClient.standingOn();
        Vector3d feet = GalaxyCraftClient.galaxyPos().orElse(null);
        if (s == null || feet == null) return false;
        return Math.abs(s.localOf(feet).length() - s.planet().surface()) < 3;
    }

    private void check(boolean that, String what) {
        log((that ? "ok " : "FAILED ") + what);
        ok &= that;
    }

    private static boolean key(AbstractWidget w, String key) {
        return w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }

    /** [the mailbox's host flags (hex), the stage], from the dev Dolphin. */
    private static String[] mbxFlags() {
        Matcher m = FLAGS.matcher(gxdev("ctl", "mbx"));
        return m.find() ? new String[] {m.group(1), m.group(2)} : new String[] {"0", "-"};
    }

    private static int hostFlags(String[] mbx) {
        return Integer.parseInt(mbx[0], 16);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft launch] " + msg);
    }

    /** Debug.music (syati GalaxyCraft.cpp, BootMusicFrames): right after the mailbox (4048 bytes), 112 words in. */
    private static final int DBG_MUSIC = 4048 + 4 * 112;

    /** A word of the module's debug block, from the dev Dolphin (-1 if the mailbox is not found). */
    private static int debugWord(int offset) {
        Matcher m = java.util.regex.Pattern.compile("^at=([0-9a-f]+)", java.util.regex.Pattern.MULTILINE)
                .matcher(gxdev("ctl", "mbx"));
        if (!m.find()) return -1;
        long at = Long.parseLong(m.group(1), 16) + offset;
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 4").strip().split("\\s+");
        int v = 0;
        for (int i = parts.length - 4; i < parts.length; i++) v = (v << 8) | Integer.parseInt(parts[i], 16);
        return v;
    }

    /** Whether the dev Dolphin has the game's sound off. */
    private static boolean muted() {
        return gxdev("ctl", "status").contains("muted=true");
    }

    /** Runs tools/gxdev.py (the dev Dolphin's control channel) and returns its output. */
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
