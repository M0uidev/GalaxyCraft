package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.GalaxySettingsScreen;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import org.joml.Vector3d;

/**
 * The pause menu, Minecraft's movement and /skin in the real game, only with
 * -Dgalaxycraft.movement=true (tools/gxvoxel.sh movement): Esc's menu has GalaxyCraft's two
 * buttons (its settings, SMG2's own menu); with Minecraft's movement the player walks and jumps on
 * a planet by Minecraft's physics and Mario keeps up with it; a skin looked up by name lands on
 * Mario's model in guest RAM. Screenshots: move-*.png.
 */
public final class MovementProbe implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    private static final Pattern SKIN_WRITES = Pattern.compile("mario_skin_writes=(\\d+)");
    private static final Pattern IN_GAME = Pattern.compile("in_game=(\\w+)");
    /** GxcMailbox.anchor_pos: Mario's position. */
    private static final int MBX_MARIO = 36;
    private static final String SKIN = System.getProperty("galaxycraft.skinName", "jeb_");
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.movement")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);

            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("movement", 48)));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 2000);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(200);
            log(String.format("Mario movement, landed: Mario %.0f units from the player", marioToPlayer(ctx)));

            pauseMenu(ctx);
            minecraftMovement(ctx, s);
            skin(ctx);

            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log(failed ? "FAIL" : "PASS");
        }
    }

    // ---- Esc's menu ----

    private void pauseMenu(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.setScreen(new PauseScreen(true)));
        ctx.waitTicks(10);
        List<String> labels = ctx.computeOnClient(mc -> Screens.getWidgets(mc.gui.screen()).stream()
                .map(w -> w.getMessage().getString()).toList());
        log("pause menu: " + labels);
        check(labels.contains("GalaxyCraft...") && labels.contains("SMG2 Menu"), "the pause menu has GalaxyCraft's buttons");
        gxdev("ctl", "shot move-pause");
        ctx.takeScreenshot("move-pause-menu");
        ctx.waitTicks(10);

        click(ctx, "GalaxyCraft...");
        ctx.waitTicks(10);
        check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof GalaxySettingsScreen), "GalaxyCraft... opens its settings");
        gxdev("ctl", "shot move-settings");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.gui.screen().onClose());
        ctx.waitTicks(5);
        check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof PauseScreen), "Done goes back to the pause menu");

        String before = status();
        click(ctx, "SMG2 Menu");
        ctx.waitTicks(60);
        check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "SMG2 Menu closes Minecraft's menu");
        String after = status();
        gxdev("ctl", "shot move-smg2-menu");
        log("SMG2 Menu: in_game " + group(IN_GAME, before) + " -> " + group(IN_GAME, after));
        check("true".equals(group(IN_GAME, before)) && !"true".equals(group(IN_GAME, after)),
                "SMG2's pause menu opened (the game stopped)");
        // Close it: in SMG2's menus Escape is its + button.
        gxdev("ctl", "keys esc");
        ctx.waitTicks(4);
        gxdev("ctl", "keys");
        ctx.waitTicks(60);
        log("closed SMG2's menu: in_game " + group(IN_GAME, status()));
    }

    private static void click(ClientGameTestContext ctx, String label) {
        ctx.runOnClient(mc -> {
            for (AbstractWidget w : Screens.getWidgets(mc.gui.screen()))
                if (w instanceof Button b && b.getMessage().getString().equals(label)) {
                    b.onPress(null);
                    return;
                }
            throw new AssertionError("no " + label + " button");
        });
    }

    // ---- Minecraft's movement ----

    private void minecraftMovement(ClientGameTestContext ctx, PlanetSession s) {
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
        ctx.runOnClient(mc -> PlanetClient.teleport());
        ctx.waitTicks(100);
        ctx.runOnClient(mc -> mc.player.setXRot(10));
        double h0 = height(ctx, s);
        boolean ground = ctx.computeOnClient(mc -> mc.player.onGround());
        log(String.format("Minecraft movement, landed: %.2f blocks from the planet's center (surface %.0f), on ground %s, Mario %.0f units off",
                h0, s.planet().surface(), ground, marioToPlayer(ctx)));
        check(ground, "the player stands on the planet");
        check(marioToPlayer(ctx) < units(1), "Mario is where the player is");
        gxdev("ctl", "shot move-mc-first");
        ctx.waitTicks(5);

        // W for two seconds: Minecraft walks the player over the planet's collision.
        Vector3d p0 = playerGal(ctx);
        ctx.getInput().holdKeyFor(o -> o.keyUp, 40);
        ctx.waitTicks(5);
        double walked = playerGal(ctx).distance(p0) / units(1), h1 = height(ctx, s), off = marioToPlayer(ctx);
        log(String.format("walked %.1f blocks, now %.2f from the center, on ground %s, Mario %.0f units off",
                walked, h1, ctx.computeOnClient(mc -> mc.player.onGround()), off));
        check(walked > 4, "W walks the player (" + String.format("%.1f", walked) + " blocks)");
        check(Math.abs(h1 - h0) < 3, "on the ground, not through it");
        check(off < units(1), "Mario kept up");

        // A block ahead, a jump onto it: walls are where Minecraft has them, not higher.
        Vector3d ahead = new Vector3d(playerGal(ctx)).sub(p0);
        boolean placed = ctx.computeOnClient(mc -> {
            Vector3d feet = GalaxyCraftClient.galaxyPos().orElseThrow();
            Vector3d up = new Vector3d(feet).sub(s.center()).normalize();
            Vector3d dir = new Vector3d(ahead).sub(new Vector3d(up).mul(ahead.dot(up))).normalize();
            Vector3d eye = new Vector3d(up).mul(2.5).add(new Vector3d(dir).mul(2)).mul(units(1)).add(feet);
            return s.placeBlock(eye, new Vector3d(up).negate(), Material.STONE, feet);
        });
        check(placed, "a stone block two blocks ahead");
        ctx.waitTicks(30);
        double stepBase = height(ctx, s);
        Vector3d stepFrom = playerGal(ctx);
        ctx.getInput().holdKey(o -> o.keyUp);
        // W and Space held, as a player would: up onto it (and off its far side).
        ctx.getInput().holdKey(o -> o.keyJump);
        double stoodOn = stepBase;
        for (int i = 0; i < 30; i++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> mc.player.onGround())) stoodOn = Math.max(stoodOn, height(ctx, s));
        }
        ctx.getInput().releaseKey(o -> o.keyJump);
        ctx.getInput().releaseKey(o -> o.keyUp);
        ctx.waitTicks(20);
        log(String.format("onto the block: stood %.2f blocks higher, %.1f blocks along", stoodOn - stepBase,
                playerGal(ctx).distance(stepFrom) / units(1)));
        check(stoodOn - stepBase > 0.8 && stoodOn - stepBase < 1.3, "a jump climbs onto one block");

        // A jump: about 1.25 blocks.
        double[] top = {height(ctx, s)};
        double base = top[0];
        ctx.getInput().holdKeyFor(o -> o.keyJump, 2);
        for (int i = 0; i < 16; i++) {
            ctx.waitTicks(1);
            top[0] = Math.max(top[0], height(ctx, s));
        }
        ctx.waitTicks(20);
        log(String.format("jump: %.2f blocks", top[0] - base));
        check(top[0] - base > 0.9 && top[0] - base < 1.6, "a Minecraft jump");

        // Third person: Steve drawn in the game, by Minecraft's player model.
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        ctx.waitTicks(20);
        gxdev("ctl", "shot move-mc-back");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        ctx.waitTicks(20);
        gxdev("ctl", "shot move-mc-front");
        ctx.waitTicks(5);
    }

    // ---- /skin ----

    private void skin(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> GalaxyOptions.SKIN.set(SKIN));
        int writes = 0;
        for (int i = 0; i < 40 && writes == 0; i++) {
            ctx.waitTicks(10);
            writes = Integer.parseInt(group(SKIN_WRITES, status()));
        }
        log("skin " + SKIN + ": " + writes + " copies of Mario's texture written");
        check(writes > 0, "the skin is written over Mario's model");
        gxdev("ctl", "shot move-skin-steve");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        ctx.waitTicks(20);
        gxdev("ctl", "shot move-skin-mario-front");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        ctx.waitTicks(20);
        gxdev("ctl", "shot move-skin-mario-back");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> GalaxyOptions.SKIN.set(""));
        ctx.waitTicks(40);
        gxdev("ctl", "shot move-skin-steve-again");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
    }

    // ---- helpers ----

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private static double units(double blocks) {
        return blocks / GravityFrame.SCALE;
    }

    /** The player's feet from the planet's center, blocks. */
    private static double height(ClientGameTestContext ctx, PlanetSession s) {
        return playerGal(ctx).distance(s.center()) / units(1);
    }

    private static Vector3d playerGal(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
    }

    private static double marioToPlayer(ClientGameTestContext ctx) {
        return peekAt(mailbox() + MBX_MARIO).distance(playerGal(ctx));
    }

    private static String status() {
        return gxdev("ctl", "status");
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "?";
    }

    private static long mailbox() {
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        if (!m.find()) throw new AssertionError("no mailbox");
        return Long.parseLong(m.group(1), 16);
    }

    private static Vector3d peekAt(long at) {
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 12").strip().split("\\s+");
        float[] f = new float[3];
        for (int k = 0; k < 3; k++) {
            int v = 0;
            for (int i = 0; i < 4; i++) v = (v << 8) | Integer.parseInt(parts[parts.length - 12 + 4 * k + i], 16);
            f[k] = Float.intBitsToFloat(v);
        }
        return new Vector3d(f[0], f[1], f[2]);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft movement] " + msg);
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
