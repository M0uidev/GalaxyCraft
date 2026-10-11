package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.ship.ShipBlocks;
import dev.moui.galaxycraft.ship.ShipFlightState;
import dev.moui.galaxycraft.ship.ShipModules;
import dev.moui.galaxycraft.ship.ShipStats;
import dev.moui.galaxycraft.ship.ShipTableBlock;
import dev.moui.galaxycraft.ship.ToadShipItem;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;

/**
 * The ship without the game (-PgalaxycraftShip): its recipes exist, the Ship Table assembles the
 * best modules of an inventory (and refuses without an engine and a cabin), an assembled ship
 * item keeps its modules, a boarded player glides without elytra, and Minecraft's cushion has a
 * renderer the game can draw the cabin's seats with. Prints "[GalaxyCraft ship] PASS" or FAIL.
 */
public final class ShipProbe implements FabricClientGameTest {
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.ship")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            // 1. Recipes.
            for (String r : new String[] {"ship_table", "ship_hull", "ship_engine", "ship_rudder", "ship_cabin", "toad_core"}) {
                boolean has = ctx.computeOnClient(mc -> mc.getSingleplayerServer().getRecipeManager()
                        .byKey(ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("galaxycraft", r))).isPresent());
                if (!has) fails.add("no recipe " + r);
            }
            // 2. The table: refuses without an engine and a cabin, then assembles the best tiers and uses them up.
            ShipModules[] made = new ShipModules[2];
            boolean[] refused = new boolean[1];
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().get(0);
                p.setGameMode(GameType.SURVIVAL);
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(ShipBlocks.HULL));
                refused[0] = ShipTableBlock.assemble(p.getInventory()).isEmpty();
                p.getInventory().setItem(1, tier(ShipBlocks.ENGINE, 1));
                p.getInventory().setItem(2, tier(ShipBlocks.ENGINE, 3));
                p.getInventory().setItem(3, new ItemStack(ShipBlocks.CABIN));
                made[0] = ShipTableBlock.assemble(p.getInventory()).orElse(null);
                made[1] = new ShipModules(p.getInventory().countItem(ShipBlocks.HULL), p.getInventory().countItem(ShipBlocks.ENGINE),
                        p.getInventory().countItem(ShipBlocks.RUDDER), p.getInventory().countItem(ShipBlocks.CABIN));
            });
            if (!refused[0]) fails.add("the table assembled without an engine and a cabin");
            if (!new ShipModules(1, 3, 0, 1).equals(made[0])) fails.add("assembled " + made[0]);
            if (!new ShipModules(0, 1, 0, 0).equals(made[1])) fails.add("left in the inventory (hull, engine, rudder, cabin): " + made[1]);
            log("assembled " + made[0] + " stats " + (made[0] == null ? null : ShipStats.of(made[0])));

            // 3. The item keeps its modules.
            ShipModules back = ToadShipItem.modules(ToadShipItem.stack(new ShipModules(2, 3, 1, 3))).orElse(null);
            if (!new ShipModules(2, 3, 1, 3).equals(back)) fails.add("ship item modules " + back);

            // 4. Aboard a ship the player glides in the air with no elytra, and not otherwise.
            boolean[] glides = new boolean[2];
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().get(0);
                p.getInventory().clearContent();
                p.setPos(0, 300, 0);
                p.setOnGround(false);
                glides[0] = p.tryToStartFallFlying();
                if (p.isFallFlying()) p.stopFallFlying();
                ShipFlightState.set(ShipStats.of(new ShipModules(1, 1, 1, 1)));
                glides[1] = p.tryToStartFallFlying();
                ShipFlightState.set(null);
                if (p.isFallFlying()) p.stopFallFlying();
            });
            if (glides[0]) fails.add("glided without a ship or elytra");
            if (!glides[1]) fails.add("did not glide aboard a ship");

            // 5. Minecraft's cushion: made in the client's level, with a renderer and a render state.
            String cushion = ctx.computeOnClient(mc -> {
                try {
                    Cushion c = new Cushion(EntityTypes.CUSHION, mc.level);
                    c.setColor(DyeColor.RED);
                    var r = mc.getEntityRenderDispatcher().getRenderer(c);
                    return r == null || r.createRenderState(c, 0f) == null ? "no renderer" : null;
                } catch (RuntimeException e) {
                    return e.toString();
                }
            });
            if (cushion != null) fails.add("cushion: " + cushion);
        }
        log(fails.isEmpty() ? "PASS" : "FAIL " + fails);
    }

    private static ItemStack tier(net.minecraft.world.item.Item item, int t) {
        ItemStack s = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putInt("tier", t);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static void log(String s) {
        System.out.println("[GalaxyCraft ship] " + s);
    }
}
