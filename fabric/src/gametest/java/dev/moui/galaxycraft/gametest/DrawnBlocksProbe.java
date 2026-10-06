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
    /** The row, left to right; "{f}" is the way toward the player, {r} a head's rotation toward him, {s} a sign's or banner's. */
    private static final String[] ROW = {"chest[facing={f}]", "player_head[rotation={r}]", "trapped_chest[facing={f}]", "ender_chest[facing={f}]",
            "copper_chest[facing={f}]", "red_bed[facing={b},part=foot]", "oak_sign[rotation={s}]",
            "white_banner[rotation={s}]", "skeleton_skull[rotation={r}]",
            "shulker_box[facing=up]", "purple_shulker_box[facing=up]", "decorated_pot[facing={f}]", "bell[facing={f}]",
            "lectern[facing={f},has_book=true]", "conduit"};

    /**
     * Blocks showing what they hold (drawn live by their own renderer), each with its data
     * (merged into the shadow's block entity); the same order as HOLD_DATA.
     */
    private static final String[] HOLD = {"oak_sign[rotation={s}]", "birch_sign[rotation={s}]", "white_banner[rotation={s}]",
            "player_head[rotation={r}]", "oak_shelf[facing={f}]", "campfire[facing={f}]", "decorated_pot[facing={f}]",
            "chest[facing={f}]", "shulker_box[facing=up]"};
    private static final String[] HOLD_DATA = {
            "{front_text:{messages:[\"Super\",{text:\"Minecraft\",color:\"red\"},{text:\"Galaxy\",color:\"blue\",bold:1b},\"\"]}}",
            "{front_text:{messages:[\"\",{text:\"Glow\",color:\"aqua\"},\"\",\"\"],has_glowing_text:1b,color:\"light_blue\"}}",
            "{patterns:[{pattern:\"minecraft:stripe_bottom\",color:\"red\"},{pattern:\"minecraft:creeper\",color:\"black\"},"
                    + "{pattern:\"minecraft:border\",color:\"blue\"}]}",
            "{profile:{name:\"Notch\"}}",
            "{Items:[{Slot:0b,id:\"minecraft:diamond\",count:1},{Slot:1b,id:\"minecraft:apple\",count:1},{Slot:2b,id:\"minecraft:torch\",count:1}]}",
            "{Items:[{Slot:0b,id:\"minecraft:beef\",count:1},{Slot:2b,id:\"minecraft:salmon\",count:1}],"
                    + "CookingTimes:[I;0,0,0,0],CookingTotalTimes:[I;100000,100000,100000,100000]}",
            "{sherds:{front:\"minecraft:arms_up_pottery_sherd\",left:\"minecraft:skull_pottery_sherd\",right:\"minecraft:heart_pottery_sherd\"}}",
            null, null};

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
            java.util.List<Integer> row = ctx.computeOnClient(mc -> place(s, ROW, 4));
            int placed = row.size();
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
            ctx.runOnClient(mc -> {
                for (int c : row) s.planet().set(c, Blocks.AIR);
            });
            ctx.waitTicks(20);
            hold(ctx, sp, s);
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            log(failed ? "FAIL" : "PASS");
        }
    }

    /**
     * Blocks that show what they hold: placed, given their data in the shadow, then drawn live
     * (their baked shape left out of the mesh); at day, at night, then a shield raised in Steve's
     * hand seen from behind.
     */
    private void hold(ClientGameTestContext ctx, TestSingleplayerContext sp, PlanetSession s) {
        ctx.runOnClient(mc -> mc.player.setYRot(0));
        ctx.waitTicks(10);
        java.util.List<Integer> cells = ctx.computeOnClient(mc -> place(s, HOLD, 3));
        check(cells.size() == HOLD.length, "every block that holds something is on the planet (" + cells.size() + ")");
        if (cells.size() != HOLD.length) return; // Mario is not on the planet: nothing more to look at
        ctx.waitTicks(40);
        log("after the banners: " + gxdev("ctl", "mbx").strip().replace("\n", " | "));
        shot(ctx, "hold-crowd-banners");
        for (int k = 0; k < cells.size(); k++) {
            net.minecraft.core.BlockPos pos = dev.moui.galaxycraft.shadow.ShadowWorld.shadowPos(s.planet(), cells.get(k));
            if (pos == null) continue;
            String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
            if (HOLD_DATA[k] != null)
                sp.getServer().runCommand("execute in galaxycraft:shadow run data merge block " + at + " " + HOLD_DATA[k]);
            else // a lid opened as a player opening it does (the block event), kept open
                sp.getServer().runOnServer(server -> {
                    var level = server.getLevel(dev.moui.galaxycraft.shadow.ShadowWorld.KEY);
                    level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 1);
                });
        }
        ctx.waitTicks(60);
        int live = ctx.computeOnClient(mc -> {
            var l = dev.moui.galaxycraft.shadow.ShadowWorld.live();
            return l == null ? 0 : l.list().size();
        });
        long hidden = ctx.computeOnClient(mc -> cells.stream().filter(c -> s.planet().hidden(c)).count());
        log("live " + live + ", hidden " + hidden + " of " + cells.size());
        for (int k = 0; k < cells.size(); k++) {
            int c = cells.get(k);
            boolean isLive = ctx.computeOnClient(mc -> {
                var l = dev.moui.galaxycraft.shadow.ShadowWorld.live();
                return l != null && l.list().stream().anyMatch(x -> x.cell() == c);
            });
            boolean isHidden = ctx.computeOnClient(mc -> s.planet().hidden(c));
            String shadow = sp.getServer().computeOnServer(server -> {
                var pos = dev.moui.galaxycraft.shadow.ShadowWorld.shadowPos(s.planet(), c);
                var level = server.getLevel(dev.moui.galaxycraft.shadow.ShadowWorld.KEY);
                var be = level.getBlockEntity(pos);
                return level.getBlockState(pos) + " " + (be == null ? "no block entity"
                        : be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity ch ? "open " + ch.getOpenNess(1f) : be.getType().toString());
            });
            String planet = ctx.computeOnClient(mc -> net.minecraft.world.level.block.Block.stateById(s.planet().get(c)).toString());
            log(HOLD[k] + ": live " + isLive + ", baked shape hidden " + isHidden + "; planet: " + planet + "; shadow: " + shadow);
            check(isLive, HOLD[k] + " is drawn live");
        }
        ctx.waitFor(mc -> s.queued() == 0, 600);
        ctx.waitTicks(40);
        shot(ctx, "hold-day");
        ctx.runOnClient(mc -> mc.player.setYRot(30));
        ctx.waitTicks(10);
        shot(ctx, "hold-right");
        ctx.runOnClient(mc -> mc.player.setYRot(-30));
        ctx.waitTicks(10);
        shot(ctx, "hold-left");
        sp.getServer().runCommand("time set midnight");
        ctx.runOnClient(mc -> mc.player.setYRot(0));
        ctx.waitTicks(40);
        shot(ctx, "hold-night");
        sp.getServer().runCommand("time set day");
        // The chest shut again: its lid closes, then it is baked again.
        int chest = cells.get(cells.size() - 2);
        sp.getServer().runOnServer(server -> {
            var level = server.getLevel(dev.moui.galaxycraft.shadow.ShadowWorld.KEY);
            var pos = dev.moui.galaxycraft.shadow.ShadowWorld.shadowPos(s.planet(), chest);
            level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 0);
        });
        ctx.waitTicks(60);
        check(!ctx.computeOnClient(mc -> s.planet().hidden(chest)), "a shut chest is baked again");
        crowd(ctx, sp, s);
        // A shield with a banner on it, raised in the off hand, seen from behind.
        sp.getServer().runCommand("item replace entity @a weapon.offhand with shield[banner_patterns=[{pattern:\"minecraft:creeper\",color:\"lime\"}],base_color=\"black\"]");
        sp.getServer().runCommand("item replace entity @a weapon.mainhand with air");
        ctx.getInput().pressKey(o -> o.keyTogglePerspective);
        ctx.runOnClient(mc -> {
            mc.player.setYRot(180); // away from the rows: Mario walks a few steps (and wakes up)
            mc.player.setXRot(10);
        });
        ctx.waitTicks(10);
        ctx.getInput().holdKeyFor(o -> o.keyUp, 8);
        ctx.waitTicks(20);
        shot(ctx, "hold-shield");
        // Held right click, as a player blocks (Minecraft lets go of an item in use once the key is up).
        ctx.getInput().holdKey(o -> o.keyUse);
        ctx.waitTicks(15);
        boolean blocking = ctx.computeOnClient(mc -> mc.player.isBlocking());
        check(blocking, "the player blocks with the shield");
        shot(ctx, "hold-shield-raised");
        String dbg = gxdev("ctl", "status");
        log("status: " + dbg.strip());
        ctx.getInput().releaseKey(o -> o.keyUse);
        ctx.getInput().pressKey(o -> o.keyTogglePerspective);
        ctx.getInput().pressKey(o -> o.keyTogglePerspective);
    }

    /** 100 signs with text and 50 banners in view: the emulator's spare speed without and with them. */
    private void crowd(ClientGameTestContext ctx, TestSingleplayerContext sp, PlanetSession s) {
        ctx.runOnClient(mc -> {
            mc.player.setYRot(0);
            mc.player.setXRot(20);
        });
        ctx.waitTicks(40);
        String before = status();
        log("before the crowd: " + gxdev("ctl", "mbx").strip().replace("\n", " | "));
        String[] signs = new String[20], banners = new String[25];
        java.util.Arrays.fill(signs, "oak_sign[rotation={s}]");
        java.util.Arrays.fill(banners, "red_banner[rotation={s}]");
        java.util.List<Integer> cells = new java.util.ArrayList<>();
        for (int r = 0; r < 5; r++) {
            double ahead = 6 + r;
            cells.addAll(ctx.computeOnClient(mc -> place(s, signs, ahead)));
        }
        int signCount = cells.size();
        log("after the signs: " + gxdev("ctl", "mbx").strip().replace("\n", " | "));
        shot(ctx, "hold-crowd-signs");
        for (int r = 0; r < 2; r++) {
            double ahead = 12 + r;
            cells.addAll(ctx.computeOnClient(mc -> place(s, banners, ahead)));
        }
        ctx.waitTicks(40);
        for (int k = 0; k < cells.size(); k++) {
            net.minecraft.core.BlockPos pos = dev.moui.galaxycraft.shadow.ShadowWorld.shadowPos(s.planet(), cells.get(k));
            if (pos != null && k < signCount)
                sp.getServer().runCommand("execute in galaxycraft:shadow run data merge block " + pos.getX() + " " + pos.getY() + " "
                        + pos.getZ() + " " + HOLD_DATA[0]);
        }
        ctx.waitTicks(100);
        log("after the text: " + gxdev("ctl", "mbx").strip().replace("\n", " | "));
        ctx.waitFor(mc -> s.queued() == 0, 600);
        ctx.waitTicks(40);
        int live = ctx.computeOnClient(mc -> {
            var l = dev.moui.galaxycraft.shadow.ShadowWorld.live();
            return l == null ? 0 : l.list().size();
        });
        shot(ctx, "hold-crowd");
        String after = status();
        ctx.waitTicks(100); // the shot is written a moment later: the world must still be there
        log("crowd: " + signCount + " signs and " + (cells.size() - signCount) + " banners placed, " + live + " live");
        log("emulator without the crowd: " + before);
        log("emulator with the crowd:    " + after);
        ctx.getInput().pressKey(o -> o.keyTogglePerspective);
        ctx.getInput().pressKey(o -> o.keyTogglePerspective);
    }

    /** A row on the ground some blocks ahead of the player, a block apart; the cells that went down. */
    private static java.util.List<Integer> place(PlanetSession s, String[] row, double ahead) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        String[] ROW = row;
        var mc = net.minecraft.client.Minecraft.getInstance();
        GravityFrame f = GalaxyCraftClient.frame();
        Vector3d feetGal = GalaxyCraftClient.galaxyPos().orElseThrow();
        Vector3d feet = s.localOf(feetGal);
        Vector3d up = new Vector3d(feet).normalize();
        Vector3d fwd = dirLocal(s, feetGal, f.dirToGal(LookMath.direction(mc.player.getYRot(), 0)));
        Vector3d side = new Vector3d(up).cross(fwd).normalize();
        var p = s.planet();
        var blocks = p.blocks;
        int stone = blocks.parse("minecraft:stone");
        for (int t = 0, k = 0; k < ROW.length && t < 4 * ROW.length; t++) {
            Vector3d at = new Vector3d(feet).add(new Vector3d(fwd).mul(ahead)).add(new Vector3d(side).mul(t / 2.0 - ROW.length / 2.0));
            // The ground under it found going down from 3 blocks up.
            int cell = -1;
            for (double h = 3; h > -6; h -= 0.5) {
                int c = p.grid.cellAt(new Vector3d(up).mul(h).add(at));
                if (c >= 0 && p.get(c) != Blocks.AIR && !p.info(c).replaceable()) {
                    cell = p.grid.neighbor(c, CubeSphere.TOP);
                    break;
                }
            }
            // On a sphere a block's step sideways can stay in the last one's column: never on top of another.
            int below = cell < 0 ? -1 : p.grid.neighbor(cell, CubeSphere.BOTTOM), above = cell < 0 ? -1 : p.grid.neighbor(cell, CubeSphere.TOP);
            if (cell < 0 || out.contains(cell) || out.contains(below) || out.contains(above)) continue;
            Vector3d look = CellSpace.direction(p.grid, cell, new Vector3d(fwd).negate());
            Direction toward = Direction.getApproximateNearest(look.x, 0, look.z);
            int rotation = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(look.x, -look.z)) / 22.5f), 16);
            String name = "minecraft:" + ROW[k].replace("{f}", toward.getSerializedName())
                    .replace("{b}", toward.getOpposite().getSerializedName()).replace("{r}", Integer.toString(rotation))
                    .replace("{s}", Integer.toString((rotation + 8) % 16)); // a sign's or banner's front faces the other way from a head's
            int id = blocks.parse(name);
            if (id == Blocks.AIR) continue;
            p.set(p.grid.neighbor(cell, CubeSphere.BOTTOM), stone);
            p.set(cell, id);
            if (name.contains("_bed[")) {
                int head = p.grid.neighbor(cell, CellSpace.SIDE_OF_DIRECTION[toward.getOpposite().ordinal()]);
                if (head >= 0) p.set(head, blocks.parse(name.replace("part=foot", "part=head")));
            }
            out.add(cell);
            k++;
        }
        return out;
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
