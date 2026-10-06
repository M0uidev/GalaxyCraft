package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.view.HeldItem;
import dev.moui.galaxycraft.view.View;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * End to end against the real game, only with -Dgalaxycraft.held=true (tools/gxvoxel.sh held):
 * Mario lands on a voxel planet and, seen from behind (F5), Steve holds what the hotbar holds,
 * one slot after the other: a tool, blocks by their model's faces, a flower (drawn flat, as
 * Minecraft holds it) and flat items. The module's debug block says what it has in hand; a screenshot of each shows it.
 */
public final class HeldItemTest implements FabricClientGameTest {
    private static final Pattern MBX = Pattern.compile("^at=([0-9a-f]+)", Pattern.MULTILINE);
    /** Debug.held_kind: right after the mailbox (4048 bytes), 102 words into the debug block. */
    private static final int DBG_HELD_KIND = 4048 + 4 * 102;
    private static final String[] ITEMS = {"iron_pickaxe", "grass_block", "stone", "oak_planks", "poppy",
            "water_bucket", "apple", "diamond_sword"};
    private static final int[] KINDS = {HeldItem.TOOL, HeldItem.BLOCK, HeldItem.BLOCK, HeldItem.BLOCK, HeldItem.ITEM,
            HeldItem.ITEM, HeldItem.ITEM, HeldItem.TOOL};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.held")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("gamemode adventure @a");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("gamerule fall_damage false");
            sp.getServer().runCommand("tp @a 0 100 0 0 0");
            for (int i = 0; i < ITEMS.length; i++)
                sp.getServer().runCommand("item replace entity @a hotbar." + (i + 1) + " with " + ITEMS[i]);
            sp.getServer().runCommand("item replace entity @a hotbar.0 with air");
            ctx.waitFor(mc -> GalaxyCraftClient.galaxyPos().isPresent(), 1200);
            ctx.waitTicks(40);
            PlanetSession s = PlanetClient.session();
            ctx.runOnClient(mc -> PlanetClient.remove());
            ctx.runOnClient(mc -> PlanetClient.requestSpawn(PlanetClient.DEFAULT_RADIUS));
            ctx.waitFor(mc -> s.active() && s.queued() == 0, 400);
            ctx.waitTicks(60);
            ctx.runOnClient(mc -> PlanetClient.teleport());
            ctx.waitTicks(120);

            slot(ctx, 0);
            check(heldKind() == HeldItem.NONE, "empty hand: nothing held");
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.waitTicks(20);
            check(ctx.computeOnClient(mc -> GalaxyCraftClient.view()) == View.BACK, "F5: third person behind");
            ctx.runOnClient(mc -> mc.player.setXRot(15));
            for (int i = 0; i < ITEMS.length; i++) {
                slot(ctx, i + 1);
                int kind = heldKind();
                check(kind == KINDS[i], ITEMS[i] + " in Steve's hand as kind " + KINDS[i] + " (" + kind + ")");
                gxdev("ctl", "shot held-" + (i + 1) + "-" + ITEMS[i]);
            }
            // From the side, where a flat item shows its edge and a tool its blade.
            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 90));
            for (int i : new int[] {1, 2, 5}) {
                slot(ctx, i);
                gxdev("ctl", "shot held-side-" + i + "-" + ITEMS[i - 1]);
            }
            // A torch in the off hand, the sword in the main one: one in each of Steve's hands.
            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() - 90));
            slot(ctx, 8);
            sp.getServer().runCommand("item replace entity @a weapon.offhand with torch");
            ctx.waitTicks(20);
            int both = heldKind();
            check((both & 0xFF) == HeldItem.TOOL && (both >> 8) == HeldItem.ITEM,
                    "the sword in the right hand and the torch in the left (" + Integer.toHexString(both) + ")");
            gxdev("ctl", "shot held-offhand");
            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 180));
            ctx.waitTicks(20);
            gxdev("ctl", "shot held-offhand-left");
            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() - 180));
            sp.getServer().runCommand("item replace entity @a weapon.offhand with air");
            ctx.waitTicks(20);
            check(heldKind() >> 8 == HeldItem.NONE, "the left hand empty again");
            // The Galaxy view (the game's camera) shows him too.
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.waitTicks(20);
            check(ctx.computeOnClient(mc -> GalaxyCraftClient.view()) == View.GALAXY, "F5 twice more: the Galaxy view");
            gxdev("ctl", "shot held-galaxy-view");
            ctx.getInput().pressKey(o -> o.keyTogglePerspective);
            ctx.waitTicks(20);
            gxdev("ctl", "shot held-first-person");
            ctx.runOnClient(mc -> PlanetClient.remove());
            log("PASS");
        }
    }

    /** Selects a hotbar slot (0 to 8) and waits for the game to have it in hand. */
    private static void slot(ClientGameTestContext ctx, int i) {
        ctx.getInput().pressKey(o -> o.keyHotbarSlots[i]);
        ctx.waitTicks(20);
    }

    private static int heldKind() {
        Matcher m = MBX.matcher(gxdev("ctl", "mbx"));
        check(m.find(), "the mailbox is found");
        long at = Long.parseLong(m.group(1), 16) + DBG_HELD_KIND;
        String[] parts = gxdev("ctl", "peek 0x" + Long.toHexString(at) + " 4").strip().split("\\s+");
        int v = 0;
        for (int i = parts.length - 4; i < parts.length; i++) v = (v << 8) | Integer.parseInt(parts[i], 16);
        return v;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            log("FAIL " + what);
            throw new AssertionError(what);
        }
        log("ok " + what);
    }

    private static void log(String msg) {
        System.out.println("[GalaxyCraft held] " + msg);
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
