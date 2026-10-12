package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.client.ToadClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.toad.ToadType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The Toads in the real game, only with -Dgalaxycraft.toadGame=true (tools/gxvoxel.sh toad): in empty
 * space a Toad goes down ahead of the player and is drawn by the game from the disc's Kinopio
 * (screenshots toad-front, toad-side); a right click on it talks (ToadClient.talks goes up) and a
 * right click on nothing does not. -Dgalaxycraft.shipDir has Kinopio.arc.
 */
public final class ToadDolphinProbe implements FabricClientGameTest {
    private static final double SPACE_UP = Integer.getInteger("galaxycraft.shipUp", 3000);
    private boolean failed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.toadGame")) return;
        try { // what an earlier run marked (the home Guide is put once) is not this world's
            java.nio.file.Files.deleteIfExists(Path.of(System.getProperty("galaxycraft.planetDir", "."), "toads.json"));
        } catch (IOException e) {
            log("could not clear toads.json: " + e);
        }
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("effect give @a resistance infinite 255 true");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);

            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MINECRAFT));
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.waitTicks(10);
            Vector3d home = ctx.computeOnClient(mc -> new Vector3d(GalaxyCraftClient.galaxyPos().orElseThrow()));
            Vector3d space = new Vector3d(0, 1, 0).mul(units(SPACE_UP)).add(home);
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(space));
            ctx.waitTicks(60);
            check(ctx.computeOnClient(mc -> GalaxyCraftClient.inVoid()), "far above the stage is space");

            ctx.runOnClient(mc -> ToadClient.clearAll());
            // 1. The five types go down in a row 4 blocks ahead, the Guide in the middle, facing the player.
            ToadType[] row = {ToadType.MINER, ToadType.EXPLORER, ToadType.GUIDE, ToadType.TECHNICIAN, ToadType.MAILMAN};
            Vector3d feet = ctx.computeOnClient(mc -> {
                GravityFrame f = GalaxyCraftClient.frame();
                Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
                Vector3d look = f.dirToGal(LookMath.direction(mc.player.getYRot(), mc.player.getXRot()));
                Vector3d up = f.upGal().normalize();
                Vector3d flat = new Vector3d(look).fma(-look.dot(up), up).normalize();
                Vector3d at = new Vector3d(eye).fma(units(4), flat).fma(-units(1.6), up);
                Vector3d back = new Vector3d(flat).negate();
                Vector3d right = new Vector3d(up).cross(back);
                Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(right, up, back));
                for (int i = 0; i < row.length; i++)
                    ToadClient.put(row[i], new Vector3d(at).fma(units((i - 2) * 1.5), right), q);
                return at;
            });
            check(ctx.computeOnClient(mc -> ToadClient.toads().size()) == 5, "five Toads are in the world");
            ctx.waitTicks(40);
            Vector3d centre = ctx.computeOnClient(mc -> new Vector3d(feet).fma(units(0.75), GalaxyCraftClient.frame().upGal().normalize()));
            aimAt(ctx, centre);
            ctx.waitTicks(60);
            aimAt(ctx, centre);
            shot(ctx, "toad-front");
            waitReal(ctx, 1200);
            shot(ctx, "toad-front-later"); // animated: not the same frame

            // 1b. Each type close up, from the front, at its head's height.
            for (int i = 0; i < row.length; i++) {
                final int k = i;
                Vector3d spot = ctx.computeOnClient(mc -> {
                    GravityFrame f = GalaxyCraftClient.frame();
                    Vector3d up = f.upGal().normalize();
                    Vector3d toPlayer = new Vector3d(centre).sub(feet);
                    Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
                    Vector3d flat = new Vector3d(eye).sub(centre);
                    flat.fma(-flat.dot(up), up).normalize();
                    Vector3d right = new Vector3d(up).cross(flat).negate();
                    Vector3d toadFeet = new Vector3d(feet).fma(units((k - 2) * 1.5), right);
                    return new Vector3d(toadFeet).fma(units(2.2), flat);
                });
                Vector3d head = ctx.computeOnClient(mc -> {
                    GravityFrame f = GalaxyCraftClient.frame();
                    Vector3d up = f.upGal().normalize();
                    Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
                    Vector3d flat = new Vector3d(eye).sub(centre);
                    flat.fma(-flat.dot(up), up).normalize();
                    Vector3d right = new Vector3d(up).cross(flat).negate();
                    return new Vector3d(feet).fma(units((k - 2) * 1.5), right).fma(units(1.2), up);
                });
                ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(spot));
                ctx.waitTicks(30);
                aimAt(ctx, head);
                ctx.waitTicks(40);
                aimAt(ctx, head);
                shot(ctx, "toad-" + row[i].id());
                if (row[i] == ToadType.MINER) {
                    // From behind and farther than it turns to the player (8 blocks): the pickel on its back.
                    Vector3d far = ctx.computeOnClient(mc -> {
                        GravityFrame f = GalaxyCraftClient.frame();
                        Vector3d up = f.upGal().normalize();
                        Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
                        Vector3d flat = new Vector3d(eye).sub(centre);
                        flat.fma(-flat.dot(up), up).normalize();
                        Vector3d right = new Vector3d(up).cross(flat).negate();
                        return new Vector3d(feet).fma(units((k - 2) * 1.5), right).fma(units(-10), flat);
                    });
                    ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(far));
                    ctx.waitTicks(40);
                    aimAt(ctx, head);
                    ctx.waitTicks(40);
                    aimAt(ctx, head);
                    shot(ctx, "toad-miner-back");
                }
            }
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(space));
            ctx.waitTicks(30);

            // 2. From its side, 3 blocks off.
            Vector3d side = ctx.computeOnClient(mc -> {
                GravityFrame f = GalaxyCraftClient.frame();
                Vector3d up = f.upGal().normalize();
                Vector3d any = new Vector3d(up.y, up.z, up.x).cross(up).normalize();
                return new Vector3d(centre).fma(units(3), any);
            });
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(side));
            ctx.waitTicks(40);
            aimAt(ctx, centre);
            ctx.waitTicks(60);
            aimAt(ctx, centre);
            shot(ctx, "toad-side");

            // 3. Right click on nothing talks to nobody; on the Toad it talks and opens its trade.
            int before = ctx.computeOnClient(mc -> ToadClient.talks());
            Vector3d away = ctx.computeOnClient(mc -> new Vector3d(centre).fma(units(30), GalaxyCraftClient.frame().upGal().normalize()));
            aimAt(ctx, away);
            ctx.waitTicks(5);
            gxdev("ctl", "keys rmb");
            waitReal(ctx, 300);
            gxdev("ctl", "keys");
            waitReal(ctx, 500);
            check(ctx.computeOnClient(mc -> ToadClient.talks()) == before, "a right click on nothing talks to nobody");
            aimAt(ctx, centre);
            ctx.waitTicks(5);
            gxdev("ctl", "keys rmb");
            waitReal(ctx, 300);
            gxdev("ctl", "keys");
            waitReal(ctx, 1500);
            check(ctx.computeOnClient(mc -> ToadClient.talks()) > before, "a right click on the Toad talks to it");
            // The box: open, letters typed, the player free to move; clicks go through the pages to the choices.
            check(ctx.computeOnClient(mc -> ToadClient.conversation() != null), "the Toad's dialogue box opens");
            check(ctx.computeOnClient(mc -> !(mc.player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu)),
                    "no merchant screen yet");
            check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "the box is not a screen: the player is free");
            waitReal(ctx, 700);
            ctx.takeScreenshot("toad-dialogue");
            for (int i = 0; i < 8 && !ctx.computeOnClient(mc -> ToadClient.conversation() != null && ToadClient.conversation().choosing()); i++) {
                gxdev("ctl", "keys rmb");
                waitReal(ctx, 250);
                gxdev("ctl", "keys");
                waitReal(ctx, 350);
            }
            check(ctx.computeOnClient(mc -> ToadClient.conversation() != null && ToadClient.conversation().choosing()),
                    "clicks go through the pages to the choices");
            ctx.takeScreenshot("toad-dialogue-choices");
            // 2: Tell me about this place: more pages, then the choices again.
            ctx.runOnClient(mc -> ToadClient.choose(mc.player, 1));
            ctx.waitTicks(10);
            check(ctx.computeOnClient(mc -> ToadClient.conversation() != null && !ToadClient.conversation().choosing()),
                    "asking about the place says more");
            for (int i = 0; i < 8 && !ctx.computeOnClient(mc -> ToadClient.conversation() != null && ToadClient.conversation().choosing()); i++) {
                gxdev("ctl", "keys rmb");
                waitReal(ctx, 250);
                gxdev("ctl", "keys");
                waitReal(ctx, 350);
            }
            check(ctx.computeOnClient(mc -> ToadClient.conversation() != null && ToadClient.conversation().choosing()),
                    "and the choices come back");
            // 1: Trade closes the box and opens the merchant screen.
            ctx.runOnClient(mc -> ToadClient.choose(mc.player, 0));
            waitReal(ctx, 1500);
            int offers = ctx.computeOnClient(mc -> mc.player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu m
                    ? m.getOffers().size() : -1);
            log("the Toad's trade has " + offers + " offers");
            check(offers == 4, "Trade opens the Guide's merchant screen (2 things, 2 payments each)");
            check(ctx.computeOnClient(mc -> ToadClient.conversation() == null), "and the box is gone");
            shot(ctx, "toad-trade");
            ctx.takeScreenshot("toad-trade-gui"); // Minecraft's window: the merchant screen is not in Dolphin's picture
            ctx.runOnClient(mc -> mc.player.closeContainer());
            ctx.waitTicks(10);
            // Talk again and walk away: the box closes by itself.
            aimAt(ctx, centre);
            ctx.waitTicks(5);
            gxdev("ctl", "keys rmb");
            waitReal(ctx, 300);
            gxdev("ctl", "keys");
            waitReal(ctx, 500);
            check(ctx.computeOnClient(mc -> ToadClient.conversation() != null), "a second talk opens the box again");
            Vector3d farAway = ctx.computeOnClient(mc -> new Vector3d(centre).fma(units(20), GalaxyCraftClient.frame().upGal().normalize()));
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(farAway));
            ctx.waitTicks(40);
            check(ctx.computeOnClient(mc -> ToadClient.conversation() == null), "walking away closes the box");

            // Still in space (stations go only there): the station first, then the planet.
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(space));
            ctx.waitTicks(40);
            station(ctx, sp);
            hut(ctx);

            ctx.runOnClient(mc -> ToadClient.clearAll());
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(home));
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> GalaxyOptions.MOVEMENT.set(Movement.MARIO));
            ctx.waitTicks(60);
        }
        log(failed ? "FAIL" : "PASS");
    }

    /**
     * 5. A station placed in space gets a Technician on its deck, and packed it goes (shots
     * station-technician).
     */
    private void station(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(mc -> ToadClient.clearAll());
        sp.getServer().runCommand("item replace entity @a hotbar.0 with galaxycraft:station_core");
        ctx.waitTicks(5);
        dev.moui.galaxycraft.voxel.PlanetSession s = ctx.computeOnClient(mc -> {
            var at = dev.moui.galaxycraft.client.StationClient.spot(mc.player);
            return at == null ? null : dev.moui.galaxycraft.client.StationClient.place(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, at);
        });
        check(s != null, "a station goes up in empty space");
        if (s == null) return;
        ctx.waitTicks(60);
        var tech = ctx.computeOnClient(mc -> ToadClient.toads().stream()
                .filter(t -> t.id().startsWith("toad-station-") && t.type() == ToadType.TECHNICIAN).findFirst().orElse(null));
        check(tech != null, "the station has its Technician");
        if (tech != null) {
            Vector3d at = new Vector3d(tech.pos()[0], tech.pos()[1], tech.pos()[2]);
            Vector3d up = new Vector3d(0, 1, 0).rotate(new Quaterniond(tech.rot()[0], tech.rot()[1], tech.rot()[2], tech.rot()[3]));
            Vector3d eye = new Vector3d(at).fma(units(4), up).fma(units(3), new Vector3d(up.y, up.z, up.x).cross(up).normalize());
            ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(eye));
            ctx.waitTicks(60);
            aimAt(ctx, new Vector3d(at).fma(units(1.2), up));
            ctx.waitTicks(20);
            shot(ctx, "station-technician");
        }
        ctx.runOnClient(mc -> dev.moui.galaxycraft.client.StationClient.pack(s));
        ctx.waitTicks(40);
        check(ctx.computeOnClient(mc -> ToadClient.toads().stream().noneMatch(t -> t.id().startsWith("toad-station-"))),
                "packed: the Technician goes with the station");
    }

    /**
     * 4. An outpost: a generated planet made with a hut asked for, its Miner put at the door. Shots
     * from outside (front, side from above), from inside beside the Toad, and the Toad from behind.
     */
    private void hut(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ToadClient.clearAll());
        var bp = dev.moui.galaxycraft.voxel.PlanetBlueprint.standard("hut", 64)
                .withMode(dev.moui.galaxycraft.voxel.PlanetBlueprint.Mode.GENERATED).withPlants(100).withWater(false)
                .withBiome(21, "minecraft:plains", 0);
        ctx.runOnClient(mc -> PlanetClient.remove());
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> PlanetClient.askHuts(java.util.List.of(
                new dev.moui.galaxycraft.voxel.gen.Huts.Request("toad-hut-test", new double[] {0, 1, 0}))));
        dev.moui.galaxycraft.voxel.PlanetSession s = PlanetClient.session();
        ctx.runOnClient(mc -> PlanetClient.requestSpawn(bp));
        ctx.waitFor(mc -> s.active() && s.queued() == 0, 6000);
        ctx.runOnClient(mc -> PlanetClient.teleport());
        ctx.waitTicks(100);
        var made = PlanetClient.madeHuts();
        check(made.size() == 1, "the planet's hut found ground (" + made.size() + ")");
        if (made.isEmpty()) return;
        var rec = dev.moui.galaxycraft.toad.ToadPlacing.record("", ToadType.MINER, made.get(0), ctx.computeOnClient(mc -> s.center()), units(1));
        Vector3d feet = new Vector3d(rec.pos()[0], rec.pos()[1], rec.pos()[2]);
        Quaterniond q = new Quaterniond(rec.rot()[0], rec.rot()[1], rec.rot()[2], rec.rot()[3]);
        // The way the world's outposts arrive: offered from a worker thread, put by the client.
        ctx.runOnClient(mc -> ToadClient.offerOutposts("probe-planet", java.util.List.of(rec)));
        check(ctx.computeOnClient(mc -> ToadClient.toads().stream().anyMatch(t -> t.id().equals("toad-hut-test"))),
                "the offered outpost is put");

        Vector3d up = new Vector3d(0, 1, 0).rotate(q), fwd = new Vector3d(0, 0, 1).rotate(q), right = new Vector3d(1, 0, 0).rotate(q);
        Vector3d head = new Vector3d(feet).fma(units(1.2), up);
        // Outside, in front of the door.
        Vector3d out = new Vector3d(feet).fma(units(7), fwd).fma(units(0.3), up);
        ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(out));
        ctx.waitTicks(120);
        aimAt(ctx, head);
        ctx.waitTicks(20);
        shot(ctx, "hut-front");
        // From the side and above, the whole roof.
        Vector3d roof = new Vector3d(feet).fma(units(9), right).fma(units(6), up);
        ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(roof));
        ctx.waitTicks(60);
        aimAt(ctx, head);
        ctx.waitTicks(20);
        shot(ctx, "hut-side");
        // Inside: back in the hut, looking at the Toad, then it from behind (the miner's pickel).
        Vector3d inside = new Vector3d(feet).fma(units(-1.6), fwd).fma(units(0.3), up);
        ctx.runOnClient(mc -> GalaxyCraftClient.moveTo(inside));
        ctx.waitTicks(60);
        aimAt(ctx, head);
        ctx.waitTicks(20);
        shot(ctx, "hut-inside");
        ctx.runOnClient(mc -> PlanetClient.askHuts(java.util.List.of()));
        check(ctx.computeOnClient(mc -> ToadClient.toads().stream().anyMatch(t -> t.type() == ToadType.GUIDE)),
                "the home Guide stands near where Mario lands");
    }

    private static void aimAt(ClientGameTestContext ctx, Vector3d targetGal) {
        ctx.runOnClient(mc -> {
            GravityFrame f = GalaxyCraftClient.frame();
            Vector3d eye = f.toGal(new Vector3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
            Vector3d d = f.dirToMc(new Vector3d(targetGal).sub(eye).normalize());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d.y)))));
        });
    }

    private static void waitReal(ClientGameTestContext ctx, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) ctx.waitTicks(1);
    }

    /** A Dolphin screenshot of the game a moment from now (test ticks outrun the game). */
    private static void shot(ClientGameTestContext ctx, String name) {
        waitReal(ctx, 500);
        log(gxdev("ctl", "shot " + name).strip());
    }

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private static double units(double blocks) {
        return blocks / GravityFrame.SCALE;
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft toad] " + msg);
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
