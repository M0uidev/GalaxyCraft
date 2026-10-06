package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.ShadowLink;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * The blocks a player uses, on a planet, without the game (with BlockRulesProbe,
 * -PgalaxycraftBlocks): an ender chest opens the player's own ender inventory and stays open, a bed
 * and a straw bed let him sleep at night and set where he comes back, a sign opens its editor,
 * a shelf takes the item in hand, and a minecart put on rails rolls along them.
 */
public final class WorkingBlocksProbe implements FabricClientGameTest {
    private PlanetSession s;
    private McBlocks blocks;
    private ShadowLink link;
    private Vector3d mario;
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.blocks")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            int[] cells = ctx.computeOnClient(mc -> {
                blocks = McBlocks.create(mc);
                s = new PlanetSession(1);
                s.setBlocks(blocks);
                s.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
                link = new ShadowLink(() -> s);
                mario = new Vector3d(0, s.planet().surface() + 0.5, 0).add(s.center());
                return new int[] {place("ender_chest", 2), place("red_bed", 4), place("straw_bed", 7), place("oak_sign", 10),
                        place("oak_shelf", 12), place("rail", 14), place("rail", 15), place("rail", 16), place("rail", 17),
                        place("respawn_anchor", 19)};
            });
            run(ctx, 60); // the shadow takes the planet in
            enderChest(ctx, sp, cells[0]);
            bed(ctx, sp, cells[1], "red_bed");
            bed(ctx, sp, cells[2], "straw_bed");
            sign(ctx, sp, cells[3]);
            shelf(ctx, sp, cells[4]);
            minecart(ctx, sp, cells[5], cells[8]);
            anchor(ctx, sp, cells[9]);
            ctx.runOnClient(mc -> System.out.println("[GalaxyCraft working] lit TNT's renderer submits: " + tntSubmits(mc)));
            System.out.println("[GalaxyCraft working] " + (fails.isEmpty() ? "PASS" : "FAIL " + fails));
        }
    }

    /** Something in the player's ender inventory; the ender chest opens it and it stays open. */
    private void enderChest(ClientGameTestContext ctx, TestSingleplayerContext sp, int cell) {
        sp.getServer().runOnServer(server -> player(server).getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 7)));
        hold(sp, ItemStack.EMPTY);
        use(ctx, cell);
        run(ctx, 20);
        String menu = sp.getServer().computeOnServer(server -> {
            ServerPlayer p = player(server);
            return p.containerMenu == p.inventoryMenu ? "none"
                    : p.containerMenu.getClass().getSimpleName() + " " + p.containerMenu.getSlot(0).getItem();
        });
        report(menu.contains("diamond"), "the ender chest shows the player's ender inventory (" + menu + ")");
        sp.getServer().runOnServer(server -> player(server).closeContainer());
    }

    /** At night a bed is slept in: the night goes, and the bed is where the player comes back. */
    private void bed(ClientGameTestContext ctx, TestSingleplayerContext sp, int cell, String name) {
        sp.getServer().runCommand("time set midnight");
        run(ctx, 5);
        while (ShadowWorld.pollBed() != null) {}
        hold(sp, ItemStack.EMPTY);
        use(ctx, cell);
        run(ctx, 10);
        boolean day = sp.getServer().computeOnServer(server -> server.overworld().isBrightOutside());
        ShadowWorld.Bed bed = ShadowWorld.pollBed();
        report(day && bed != null, name + " slept in at night: morning " + day + ", the bed kept " + (bed != null));
    }

    /** Using a sign with an empty hand opens its editor; what is typed there stays on the sign. */
    private void sign(ClientGameTestContext ctx, TestSingleplayerContext sp, int cell) {
        hold(sp, ItemStack.EMPTY);
        use(ctx, cell);
        run(ctx, 10);
        String screen = ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
        report(screen.contains("Sign"), "the sign opens its editor (" + screen + ")");
        if (!screen.contains("Sign")) return;
        ctx.getInput().typeChars("Hola");
        ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
        ctx.getInput().typeChars("GalaxyCraft");
        System.out.println("[GalaxyCraft working] the editor holds " + ctx.computeOnClient(mc -> {
            try {
                var f = net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen.class.getDeclaredField("messages");
                f.setAccessible(true);
                return java.util.Arrays.toString((String[]) f.get(mc.gui.screen()));
            } catch (ReflectiveOperationException | ClassCastException e) {
                return e.toString();
            }
        }));
        ctx.clickScreenButton("gui.done");
        run(ctx, 10);
        var map = dev.moui.galaxycraft.shadow.ShadowMap.of(s.planet(), "probe");
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(map.x(cell), map.y(cell), map.z(cell));
        String text = sp.getServer().computeOnServer(server -> server.getLevel(ShadowWorld.KEY).getBlockEntity(pos)
                instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign
                ? String.join("/", sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).stream()
                        .map(net.minecraft.network.chat.Component::getString).toList()) + " | back "
                        + String.join("/", sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK).getMessages(false).stream()
                        .map(net.minecraft.network.chat.Component::getString).toList())
                : "no sign at " + pos);
        report(text.contains("Hola/GalaxyCraft"), "what is typed stays on the sign (" + text + ")");
    }

    /** A shelf takes the item in hand, clicked on its front. */
    private void shelf(ClientGameTestContext ctx, TestSingleplayerContext sp, int cell) {
        hold(sp, new ItemStack(Items.APPLE, 3));
        String name = ctx.computeOnClient(mc -> blocks.name(s.planet().get(cell)));
        var facing = net.minecraft.core.Direction.byName(name.replaceAll(".*facing=(\\w+).*", "$1"));
        int side = dev.moui.galaxycraft.voxel.CellSpace.SIDE_OF_DIRECTION[facing.ordinal()];
        int[] st = dev.moui.galaxycraft.voxel.CellSpace.STEP[side];
        ctx.runOnClient(mc -> ShadowWorld.use(s.planet(), cell, side, new Vec3(0.5 + 0.49 * st[0], 0.5 + 0.49 * st[1], 0.5 + 0.49 * st[2]),
                mc.player.getUUID(), InteractionHand.MAIN_HAND, () -> {}));
        run(ctx, 3);
        run(ctx, 10);
        String hand = sp.getServer().computeOnServer(server -> player(server).getMainHandItem().toString());
        report(!hand.contains("3 minecraft:apple"), "the shelf takes the apples in hand (hand now " + hand + ")");
    }

    /** A minecart used on the rails goes on them, and rolls along them when pushed. */
    private void minecart(ClientGameTestContext ctx, TestSingleplayerContext sp, int first, int last) {
        report(ctx.computeOnClient(mc -> blocks.name(s.planet().get(first)).contains("rail")), "rails on the planet ("
                + ctx.computeOnClient(mc -> blocks.name(s.planet().get(first))) + ")");
        hold(sp, new ItemStack(Items.MINECART));
        use(ctx, first);
        run(ctx, 10);
        String cart = sp.getServer().computeOnServer(server -> {
            for (var e : server.getLevel(ShadowWorld.KEY).getAllEntities())
                if (e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart m) {
                    m.setDeltaMovement(0.6, 0, 0);
                    return m.getId() + " at " + m.position() + " on " + m.level().getBlockState(m.blockPosition());
                }
            return "none";
        });
        report(cart.contains("rail"), "the minecart goes on the rails (" + cart + ")");
        run(ctx, 30);
        String moved = sp.getServer().computeOnServer(server -> {
            for (var e : server.getLevel(ShadowWorld.KEY).getAllEntities())
                if (e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart m)
                    return m.position() + " on " + m.level().getBlockState(m.blockPosition());
            return "none";
        });
        System.out.println("[GalaxyCraft working] minecart after a push: " + moved);
        report(!moved.equals("none") && !moved.startsWith(cart.replaceAll(".* at (\\S+, \\S+, \\S+) on .*", "$1")), "the minecart rolls");
    }

    /** A respawn anchor charged with glowstone, then used: it keeps where the player comes back, and is not blown up. */
    private void anchor(ClientGameTestContext ctx, TestSingleplayerContext sp, int cell) {
        while (ShadowWorld.pollBed() != null) {}
        hold(sp, new ItemStack(Items.GLOWSTONE, 2));
        use(ctx, cell);
        run(ctx, 5);
        hold(sp, ItemStack.EMPTY);
        use(ctx, cell);
        run(ctx, 10);
        String block = ctx.computeOnClient(mc -> blocks.name(s.planet().get(cell)));
        report(block.contains("charges=1") && ShadowWorld.pollBed() != null, "the respawn anchor is charged and keeps the spawn (" + block + ")");
    }

    /** What a lit TNT's own renderer submits (by kind of submit), as the game's entity capture sees it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String tntSubmits(net.minecraft.client.Minecraft mc) {
        var tnt = new net.minecraft.world.entity.item.PrimedTnt(mc.level, 0, 100, 0, null);
        tnt.setFuse(60);
        java.util.Map<String, Integer> seen = new java.util.TreeMap<>();
        var collector = (net.minecraft.client.renderer.SubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(
                WorkingBlocksProbe.class.getClassLoader(), new Class<?>[] {net.minecraft.client.renderer.SubmitNodeCollector.class},
                (proxy, m, args) -> {
                    String what = m.getName();
                    if (args != null)
                        for (Object a : args) {
                            if (a instanceof List<?> l) what += " list " + l.size();
                            if (a != null && a.getClass().isArray() && !a.getClass().getComponentType().isPrimitive())
                                what += " array " + ((Object[]) a).length;
                        }
                    what += " " + java.util.Arrays.toString(m.getParameterTypes()).replaceAll("class [a-z.]*\\.", "");
                    seen.merge(what, 1, Integer::sum);
                    return m.getName().equals("order") ? proxy : null;
                });
        net.minecraft.client.renderer.entity.EntityRenderer r = mc.getEntityRenderDispatcher().getRenderer(tnt);
        var st = r.createRenderState(tnt, 0f);
        r.submit(st, new com.mojang.blaze3d.vertex.PoseStack(), collector, new net.minecraft.client.renderer.state.level.CameraRenderState());
        return seen.toString();
    }

    private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static void hold(TestSingleplayerContext sp, ItemStack stack) {
        sp.getServer().runOnServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND, stack.copy()));
    }

    /** Places an item on the ground k blocks along a row (as BlockRulesProbe does); the cell it went in. */
    private int place(String name, int k) {
        var p = s.planet();
        var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(name));
        double a = (k - 8 + 0.5) / p.surface();
        Vector3d up = new Vector3d(Math.sin(a), Math.cos(a), 0.02).normalize();
        Vector3d ahead = new Vector3d(Math.cos(a), -Math.sin(a), 0);
        Vector3d at = new Vector3d(up).mul(p.surface());
        Vector3d eye = new Vector3d(up).mul(2.5).add(at).sub(new Vector3d(ahead).mul(0.6)).add(s.center());
        Vector3d look = new Vector3d(up).negate().add(new Vector3d(ahead).mul(0.25)).normalize();
        if (!s.placeBlock(eye, look, blocks.placer(new ItemStack(item)), new Vector3d(0, -100, 0).add(s.center())))
            fails.add(name + " not placed");
        return p.grid.cellAt(new Vector3d(up).mul(p.surface() + 0.5));
    }

    /** A right click on the cell's top, as the player's own would go (the block's use, then the item's). */
    private void use(ClientGameTestContext ctx, int cell) {
        AtomicBoolean passed = new AtomicBoolean();
        ctx.runOnClient(mc -> ShadowWorld.use(s.planet(), cell, CubeSphere.TOP, new Vec3(0.5, 0.5, 0.5), mc.player.getUUID(),
                InteractionHand.MAIN_HAND, () -> passed.set(true)));
        run(ctx, 3);
    }

    private void run(ClientGameTestContext ctx, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.runOnClient(mc -> link.tick("probe", mario));
            ctx.waitTick();
        }
    }

    private void report(boolean ok, String what) {
        System.out.println("[GalaxyCraft working] " + (ok ? "ok " : "FAILED ") + what);
        if (!ok) fails.add(what);
    }
}
