package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.view.View;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Vector3d;

/**
 * End to end against the real game, only with -Dgalaxycraft.galaxy=true (tools/gxe2e.sh): the dev
 * Dolphin (tools/gxdev.py) runs SMG2 at the Sky Station savestate in Mario mode. F5 goes through
 * the four perspectives and SMG2's camera goes with them: Minecraft's camera offset in the first
 * three, the game's own camera (and the Galaxy view flag) in the fourth.
 */
public final class MarioPerspectivesTest implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+).* flags=(\\d+)/(\\d+) ", Pattern.MULTILINE);
    private static final int MBX_HOST_FLAGS = 52, MBX_LOOK = 68, MBX_CAM_OFFSET = 100;
    private static final int FOLLOW = 2, GALAXY_VIEW = 4;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.galaxy")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 600);
            ctx.waitTicks(40);
            check(view(ctx) == View.FIRST, "starts in first person");
            check(hostFlags() == FOLLOW, "first person: FOLLOW without the Galaxy view");
            double eye = peekVec(MBX_CAM_OFFSET).length();
            check(eye > 100 && eye < 200, "first person: camera at the eyes, " + eye + " units");
            gxdev("ctl", "shot e2e-view-first");

            f5(ctx);
            check(view(ctx) == View.BACK, "F5: third person behind");
            Vector3d back = peekVec(MBX_CAM_OFFSET);
            check(back.length() > 250, "behind: camera " + back.length() + " units from the feet");
            Vector3d lookBack = peekVec(MBX_LOOK);
            gxdev("ctl", "shot e2e-view-back");

            f5(ctx);
            check(view(ctx) == View.FRONT, "F5: third person in front");
            Vector3d lookFront = peekVec(MBX_LOOK);
            check(lookBack.dot(lookFront) < 0, "in front: the camera looks back at Steve");
            gxdev("ctl", "shot e2e-view-front");

            f5(ctx);
            check(view(ctx) == View.GALAXY, "F5: the Galaxy view");
            check(hostFlags() == (FOLLOW | GALAXY_VIEW), "Galaxy view: FOLLOW and GALAXY_VIEW in the mailbox");
            check(ctx.computeOnClient(mc -> GalaxyCraftClient.galaxyCamera(new Vector3d()).isPresent()),
                    "Galaxy view: SMG2's camera reaches Minecraft");
            gxdev("ctl", "shot e2e-view-galaxy");
            gxdev("ctl", "keys w");
            ctx.waitTicks(30);
            gxdev("ctl", "keys");
            gxdev("ctl", "shot e2e-view-galaxy-walked");

            f5(ctx);
            check(view(ctx) == View.FIRST, "F5: back to first person");
            check(hostFlags() == FOLLOW, "first person again: no Galaxy view flag");
            log("PASS");
        }
    }

    private static void f5(ClientGameTestContext ctx) {
        ctx.getInput().pressKey(o -> o.keyTogglePerspective);
        ctx.waitTicks(20);
    }

    private static View view(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> GalaxyCraftClient.view());
    }

    private static int hostFlags() {
        return (int) peekU32(MBX_HOST_FLAGS);
    }

    private static Vector3d peekVec(int offset) {
        return new Vector3d(Float.intBitsToFloat((int) peekU32(offset)), Float.intBitsToFloat((int) peekU32(offset + 4)),
                Float.intBitsToFloat((int) peekU32(offset + 8)));
    }

    /** A big-endian word of the guest mailbox. */
    private static long peekU32(int offset) {
        String out = gxdev("ctl", "mbx");
        Matcher m = MBX.matcher(out);
        check(m.find(), "mailbox summary from gxdev: " + out.strip());
        long at = Long.parseLong(m.group(1), 16) + offset;
        String dump = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 4").strip();
        String[] parts = dump.split("\\s+");
        long v = 0;
        for (int i = parts.length - 4; i < parts.length; i++) v = (v << 8) | Long.parseLong(parts[i], 16);
        return v;
    }

    /** Runs tools/gxdev.py (the dev Dolphin's control channel) and returns its output. */
    private static String gxdev(String... args) {
        Path root = Path.of(System.getProperty("galaxycraft.repoRoot", "."));
        String[] cmd = new String[args.length + 2];
        cmd[0] = "python3";
        cmd[1] = root.resolve("tools/gxdev.py").toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(p.waitFor() == 0, "gxdev " + String.join(" ", args) + ": " + out.strip());
            return out;
        } catch (IOException e) {
            throw new AssertionError("could not run gxdev.py", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        log("ok: %s", what);
    }

    private static void log(String fmt, Object... args) {
        System.out.println("[GalaxyCraft e2e] " + String.format(fmt, args));
    }
}
