package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.Direction;
import org.joml.Vector3d;

/**
 * Blocks Minecraft draws with a block entity renderer, on a planet in the real game, only with
 * -Dgalaxycraft.drawn=true (tools/gxvoxel.sh drawn): a row of chests, beds, signs, banners, heads,
 * shulker boxes, pots and bells in front of Mario, their fronts toward him; screenshots
 * drawn-*.png, and the emulator's speed before and after they are there.
 */
public final class DrawnBlocksProbe implements FabricClientGameTest {
    /** The row, left to right; "{f}" is the way toward the player. */
    private static final String[] ROW = {"chest[facing={f}]", "player_head[rotation={r}]", "trapped_chest[facing={f}]", "ender_chest[facing={f}]",
            "copper_chest[facing={f}]", "red_bed[facing={b},part=foot]", "oak_sign[rotation={r}]",
            "white_banner[rotation={r}]", "skeleton_skull[rotation={r}]",
            "shulker_box[facing=up]", "purple_shulker_box[facing=up]", "decorated_pot[facing={f}]", "bell[facing={f}]",
            "lectern[facing={f},has_book=true]", "conduit"};

    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.drawn")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode creative @a");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetBlueprint.standard("drawn", 48)));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 2000);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(200);
            ctx.runOnClient(mc -> {
                mc.player.setYRot(0);
                mc.player.setXRot(30);
            });
            ctx.waitTicks(20);
            String before = status();
            int placed = ctx.computeOnClient(mc -> place(s));
            log("placed " + placed + " of " + ROW.length);
            check(placed == ROW.length, "every block of the row is on the planet");
            ctx.waitFor(mc -> s.queued() == 0, 600);
            ctx.waitTicks(60);
            shot(ctx, "drawn-row");
            ctx.runOnClient(mc -> mc.player.setYRot(25));
            ctx.waitTicks(20);
            shot(ctx, "drawn-side");
            ctx.runOnClient(mc -> mc.player.setYRot(-35));
            ctx.waitTicks(20);
            shot(ctx, "drawn-left");
            String after = status();
            log("emulator before: " + before);
            log("emulator after:  " + after);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log(failed ? "FAIL" : "PASS");
        }
    }

    /** The row on the ground 4 blocks ahead of the player, a block apart; how many went down. */
    private static int place(PlanetSession s) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        GravityFrame f = GalaxyCraftClient.frame();
        Vector3d feetGal = GalaxyCraftClient.galaxyPos().orElseThrow();
        Vector3d feet = s.localOf(feetGal);
        Vector3d up = new Vector3d(feet).normalize();
        Vector3d fwd = dirLocal(s, feetGal, f.dirToGal(LookMath.direction(mc.player.getYRot(), 0)));
        Vector3d side = new Vector3d(up).cross(fwd).normalize();
        var p = s.planet();
        var blocks = p.blocks;
        int stone = blocks.parse("minecraft:stone"), done = 0;
        for (int k = 0; k < ROW.length; k++) {
            Vector3d at = new Vector3d(feet).add(new Vector3d(fwd).mul(4)).add(new Vector3d(side).mul(k - ROW.length / 2.0));
            // The ground under it found going down from 3 blocks up.
            int cell = -1;
            for (double h = 3; h > -6; h -= 0.5) {
                int c = p.grid.cellAt(new Vector3d(up).mul(h).add(at));
                if (c >= 0 && p.get(c) != Blocks.AIR && !p.info(c).replaceable()) {
                    cell = p.grid.neighbor(c, CubeSphere.TOP);
                    break;
                }
            }
            if (cell < 0) continue;
            Vector3d look = CellSpace.direction(p.grid, cell, new Vector3d(fwd).negate());
            Direction toward = Direction.getApproximateNearest(look.x, 0, look.z);
            int rotation = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(look.x, -look.z)) / 22.5f), 16);
            String name = "minecraft:" + ROW[k].replace("{f}", toward.getSerializedName())
                    .replace("{b}", toward.getOpposite().getSerializedName()).replace("{r}", Integer.toString(rotation));
            int id = blocks.parse(name);
            if (id == Blocks.AIR) continue;
            p.set(p.grid.neighbor(cell, CubeSphere.BOTTOM), stone);
            p.set(cell, id);
            if (name.contains("_bed[")) {
                int head = p.grid.neighbor(cell, CellSpace.SIDE_OF_DIRECTION[toward.getOpposite().ordinal()]);
                if (head >= 0) p.set(head, blocks.parse(name.replace("part=foot", "part=head")));
            }
            done++;
        }
        return done;
    }

    /** A galaxy direction at a galaxy point, in the planet's space (unit). */
    private static Vector3d dirLocal(PlanetSession s, Vector3d atGal, Vector3d dirGal) {
        return s.localOf(new Vector3d(atGal).add(dirGal)).sub(s.localOf(atGal)).normalize();
    }

    private void check(boolean that, String what) {
        log((that ? "ok " : "FAILED ") + what);
        failed |= !that;
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft drawn] " + msg);
    }

    /** The dev Dolphin's frame rate and spare speed. */
    private static String status() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("fps=\\S+ vps=\\S+ speed=\\S+ max_speed=\\S+")
                .matcher(gxdev("ctl", "status"));
        return m.find() ? m.group() : "?";
    }

    /** A Dolphin screenshot of the game a moment from now (test ticks outrun the game). */
    private static void shot(ClientGameTestContext ctx, String name) {
        long end = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < end) ctx.waitTicks(1);
        log(gxdev("ctl", "shot " + name).strip());
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
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
