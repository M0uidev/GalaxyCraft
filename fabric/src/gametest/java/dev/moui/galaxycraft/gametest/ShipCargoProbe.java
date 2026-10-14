package dev.moui.galaxycraft.gametest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.moui.galaxycraft.ship.CargoCodec;
import dev.moui.galaxycraft.ship.ShipCargo;
import dev.moui.galaxycraft.ship.ShipMenu;
import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.ShipBlocks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * The ship's hold through Minecraft's own menu (-PgalaxycraftShipCargo), no game: shift-click and drag install
 * chests, Join makes a large one, items go in and out, a chest with items stays, Split and removal work once it
 * is empty, and a hold with items survives its JSON. Prints "[GalaxyCraft cargo] PASS" or FAIL.
 */
public final class ShipCargoProbe implements FabricClientGameTest {
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.shipcargo")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            // The chests drawn in the ship come from Minecraft's chest renderer: single and both halves of a double one.
            String render = ctx.computeOnClient(mc -> {
                try {
                    for (var type : net.minecraft.world.level.block.state.properties.ChestType.values()) {
                        var state = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.SOUTH)
                                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, type);
                        var be = new net.minecraft.world.level.block.entity.ChestBlockEntity(net.minecraft.core.BlockPos.ZERO, state);
                        be.setLevel(mc.level);
                        var r = mc.getBlockEntityRenderDispatcher().getRenderer(be);
                        if (r == null) return "no renderer for " + type;
                        var st = r.createRenderState();
                        r.extractRenderState(be, st, 0f, net.minecraft.world.phys.Vec3.ZERO, null);
                    }
                    return null;
                } catch (RuntimeException e) {
                    return e.toString();
                }
            });
            if (render != null) fails.add("chest renderer: " + render);
            sp.getServer().runOnServer(server -> {
                try {
                    run(server.getPlayerList().getPlayers().get(0), server);
                } catch (RuntimeException e) {
                    fails.add("threw " + e);
                }
            });
            // Pictures: the hold's screen with a few chests and things in it, and the flight panel.
            ShipCargo shown = new ShipCargo(4);
            shown.install(0);
            shown.install(1);
            shown.join(0);
            shown.install(2);
            sp.getServer().runOnServer(server -> {
                ServerPlayer pl = server.getPlayerList().getPlayers().get(0);
                shown.chest(0).items().set(0, new ItemStack(Items.DIAMOND, 12));
                shown.chest(0).items().set(1, new ItemStack(Items.OAK_LOG, 64));
                shown.chest(0).items().set(30, new ItemStack(Items.IRON_INGOT, 5));
                pl.getInventory().setItem(2, new ItemStack(Items.CHEST, 2));
                ShipMenu.open(pl, "probe", shown, new ShipModules(2, 3, 1, 3), mods -> {}, () -> {});
            });
            ctx.waitTicks(20);
            ctx.takeScreenshot("ship-cargo-screen");
            ctx.runOnClient(mc -> mc.gameMode.handleInventoryButtonClick(mc.player.containerMenu.containerId, ShipMenu.B_TAB + ShipMenu.TAB_PARTS));
            ctx.waitTicks(10);
            ctx.takeScreenshot("ship-parts-screen");
            ctx.runOnClient(mc -> mc.player.closeContainer());
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> dev.moui.galaxycraft.client.ShipHud.show(dev.moui.galaxycraft.ship.ShipHudModel.of(
                    new org.joml.Vector3d(0, 0, 1.1), true, new dev.moui.galaxycraft.ship.ShipModules(2, 3, 1, 3), 2, 4), null));
            ctx.waitTicks(5);
            ctx.takeScreenshot("ship-hud");
            ctx.runOnClient(mc -> dev.moui.galaxycraft.client.ShipHud.fullForTest = true);
            ctx.waitTicks(5);
            ctx.takeScreenshot("ship-hud-full");
            ctx.runOnClient(mc -> dev.moui.galaxycraft.client.ShipHud.fullForTest = false);
            ctx.runOnClient(mc -> dev.moui.galaxycraft.client.ShipHud.show(null, null));
        }
        System.out.println("[GalaxyCraft cargo] " + (fails.isEmpty() ? "PASS" : "FAIL " + fails));
    }

    private void expect(boolean ok, String what) {
        if (!ok) fails.add(what);
    }

    private void run(ServerPlayer p, net.minecraft.server.MinecraftServer server) {
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        p.getInventory().setItem(0, new ItemStack(Items.CHEST, 3));
        p.getInventory().setItem(1, new ItemStack(Items.DIRT, 32));
        int[] saved = {0};
        ShipCargo cargo = new ShipCargo(4);
        ShipMenu m = new ShipMenu(7, p.getInventory(), cargo, new ShipModules(1, 1, 1, 3), mods -> {}, () -> saved[0]++);
        int hotbar0 = ShipMenu.PLAYER_START + 27, hotbar1 = hotbar0 + 1;
        ShipModules[] lastModules = {null};

        // Shift-click puts a chest in the first free bay; dragging it onto bay 1 installs another.
        m.clicked(hotbar0, 0, ContainerInput.QUICK_MOVE, p);
        expect(cargo.bay(0) == ShipCargo.Bay.SMALL, "shift-click did not install bay 0: " + cargo.bay(0));
        m.clicked(hotbar0, 0, ContainerInput.PICKUP, p);
        m.clicked(ShipMenu.BAY_START + 1, 0, ContainerInput.PICKUP, p);
        expect(cargo.bay(1) == ShipCargo.Bay.SMALL, "dragging did not install bay 1: " + cargo.bay(1));
        expect(p.getInventory().countItem(Items.CHEST) == 0 && m.getCarried().getCount() == 1, "chests left: " + p.getInventory().countItem(Items.CHEST) + " carried " + m.getCarried());
        m.clicked(hotbar0, 0, ContainerInput.PICKUP, p); // the one still on the cursor goes back
        expect(saved[0] > 0, "installing did not ask for a save");

        // Join: one large chest of 54 slots, shown.
        expect(m.clickMenuButton(p, ShipMenu.B_JOIN), "join refused");
        m.broadcastChanges();
        expect(m.shownSize() == 54, "shown size " + m.shownSize());
        expect(cargo.bay(0) == ShipCargo.Bay.LARGE_HEAD && cargo.bay(1) == ShipCargo.Bay.LARGE_TAIL, "bays " + cargo.bay(0) + " " + cargo.bay(1));

        // Items in by shift-click, into the shown chest; slot 53 (second row page) takes items too.
        m.clicked(hotbar1, 0, ContainerInput.QUICK_MOVE, p);
        expect(p.getInventory().countItem(Items.DIRT) == 0, "dirt stayed in the inventory");
        int dirt = 0;
        for (ItemStack s : cargo.chest(0).items()) if (s.is(Items.DIRT)) dirt += s.getCount();
        expect(dirt == 32, "dirt in the chest " + dirt);

        // A chest that holds something stays; split is refused.
        m.clicked(ShipMenu.BAY_START, 0, ContainerInput.PICKUP, p);
        expect(m.getCarried().isEmpty() && cargo.bay(0) == ShipCargo.Bay.LARGE_HEAD, "a large chest was taken out");
        expect(!m.clickMenuButton(p, ShipMenu.B_SPLIT), "split with items");

        // The hold survives its JSON, items included.
        CargoCodec.setItemCodec(new CargoCodec.ItemCodec() {
            @Override
            public JsonElement encode(ItemStack s) {
                return ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), s).getOrThrow();
            }

            @Override
            public ItemStack decode(JsonElement e) {
                return ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), e).result().orElse(ItemStack.EMPTY);
            }
        });
        ShipCargo back = CargoCodec.fromJson(CargoCodec.toJson(cargo), 4);
        int again = 0;
        for (ItemStack s : back.chest(0).items()) if (s.is(Items.DIRT)) again += s.getCount();
        expect(back.bay(0) == ShipCargo.Bay.LARGE_HEAD && again == 32, "reloaded " + back.bay(0) + " dirt " + again);

        // Out again: shift-click the content slots, then split, then take the chests out.
        for (int i = 0; i < ShipMenu.PAGE; i++) m.clicked(ShipMenu.CONTENT_START + i, 0, ContainerInput.QUICK_MOVE, p);
        expect(p.getInventory().countItem(Items.DIRT) == 32, "dirt back " + p.getInventory().countItem(Items.DIRT));
        expect(m.clickMenuButton(p, ShipMenu.B_SPLIT), "split refused when empty");
        m.clicked(ShipMenu.BAY_START, 0, ContainerInput.QUICK_MOVE, p);
        m.clicked(ShipMenu.BAY_START + 1, 0, ContainerInput.QUICK_MOVE, p);
        expect(cargo.bay(0) == ShipCargo.Bay.EMPTY && cargo.bay(1) == ShipCargo.Bay.EMPTY, "bays not emptied");
        expect(p.getInventory().countItem(Items.CHEST) == 3, "chests back " + p.getInventory().countItem(Items.CHEST));

        // The parts: a better engine swaps in (the old one comes back), a hull comes out, an engine never does, and
        // the cabin cannot drop below what the chests need.
        p.getInventory().clearContent();
        ShipCargo hold = new ShipCargo(4);
        hold.install(2); // a chest in bay 2 needs three bays: a cabin of tier 3
        ShipModules[] now = {new ShipModules(1, 1, 1, 3)};
        ShipMenu pm = new ShipMenu(9, p.getInventory(), hold, now[0], mods -> now[0] = mods, () -> {});
        pm.clickMenuButton(p, ShipMenu.B_TAB + ShipMenu.TAB_PARTS);
        expect(pm.tab() == ShipMenu.TAB_PARTS, "tab " + pm.tab());
        p.getInventory().setItem(0, ShipMenu.moduleStack(1, 3));
        pm.clicked(ShipMenu.PLAYER_START + 27, 0, ContainerInput.QUICK_MOVE, p);
        expect(now[0].engine() == 3, "engine after the swap " + now[0]);
        expect(p.getInventory().countItem(ShipBlocks.ENGINE) == 1, "the old engine did not come back: " + p.getInventory().countItem(ShipBlocks.ENGINE));
        p.getInventory().clearContent();
        pm.clicked(ShipMenu.PART_START + 1, 0, ContainerInput.QUICK_MOVE, p); // the engine cannot be taken out
        expect(now[0].engine() == 3 && p.getInventory().countItem(ShipBlocks.ENGINE) == 0, "an engine came out " + now[0]);
        pm.clicked(ShipMenu.PART_START, 0, ContainerInput.PICKUP, p); // the hull can
        expect(now[0].hull() == 0 && pm.getCarried().is(ShipBlocks.HULL), "hull " + now[0] + " carried " + pm.getCarried());
        pm.clicked(ShipMenu.PART_START, 0, ContainerInput.PICKUP, p); // and goes back
        expect(now[0].hull() == 1, "hull put back " + now[0]);
        pm.setCarried(ShipMenu.moduleStack(3, 2)); // a cabin of tier 2 opens two bays: the chest in bay 2 forbids it
        pm.clicked(ShipMenu.PART_START + 3, 0, ContainerInput.PICKUP, p);
        expect(now[0].cabin() == 3, "the cabin dropped under its chests " + now[0]);
        pm.setCarried(ItemStack.EMPTY);
        hold.remove(2);
        pm.setCarried(ShipMenu.moduleStack(3, 2));
        pm.clicked(ShipMenu.PART_START + 3, 0, ContainerInput.PICKUP, p);
        expect(now[0].cabin() == 2 && hold.capacity() == 2, "cabin " + now[0] + " bays " + hold.capacity());
        pm.setCarried(ItemStack.EMPTY);

        // A locked bay takes no chest.
        ShipCargo one = new ShipCargo(1);
        ShipMenu m2 = new ShipMenu(8, p.getInventory(), one, new ShipModules(1, 1, 1, 1), mods -> {}, () -> {});
        m2.setCarried(new ItemStack(Items.CHEST));
        m2.clicked(ShipMenu.BAY_START + 2, 0, ContainerInput.PICKUP, p);
        expect(one.bay(2) == ShipCargo.Bay.EMPTY, "a locked bay took a chest");
        m2.setCarried(ItemStack.EMPTY);
    }
}
