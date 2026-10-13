package dev.moui.galaxycraft.ship;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** The Ship Table, the module items and the assembled ship. */
public final class ShipBlocks {
    public static Block TABLE;
    public static Item TABLE_ITEM, HULL, ENGINE, RUDDER, CABIN, TOAD_CORE, TOAD_SHIP, LANDING_PAD;

    private ShipBlocks() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("galaxycraft", path);
    }

    private static Item item(String path, int stack) {
        ResourceKey<Item> k = ResourceKey.create(Registries.ITEM, id(path));
        return Registry.register(BuiltInRegistries.ITEM, k, new Item(new Item.Properties().setId(k).stacksTo(stack)));
    }

    public static void register() {
        if (TABLE != null) return;
        ShipMenu.register();
        ResourceKey<Block> tk = ResourceKey.create(Registries.BLOCK, id("ship_table"));
        TABLE = Registry.register(BuiltInRegistries.BLOCK, tk,
                new ShipTableBlock(BlockBehaviour.Properties.of().setId(tk).strength(3, 6).sound(SoundType.METAL)));
        ResourceKey<Item> tik = ResourceKey.create(Registries.ITEM, id("ship_table"));
        TABLE_ITEM = Registry.register(BuiltInRegistries.ITEM, tik,
                new BlockItem(TABLE, new Item.Properties().setId(tik).useBlockDescriptionPrefix()));
        HULL = item("ship_hull", 16);
        ENGINE = item("ship_engine", 16);
        RUDDER = item("ship_rudder", 16);
        CABIN = item("ship_cabin", 16);
        TOAD_CORE = item("toad_core", 16);
        ResourceKey<Item> sk = ResourceKey.create(Registries.ITEM, id("toad_ship"));
        TOAD_SHIP = Registry.register(BuiltInRegistries.ITEM, sk, new ToadShipItem(new Item.Properties().setId(sk).stacksTo(1)));
        ResourceKey<Item> pk = ResourceKey.create(Registries.ITEM, id("landing_pad"));
        LANDING_PAD = Registry.register(BuiltInRegistries.ITEM, pk, new LandingPadItem(new Item.Properties().setId(pk).stacksTo(16)));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(o -> {
            o.accept(TABLE_ITEM);
            o.accept(HULL);
            o.accept(ENGINE);
            o.accept(RUDDER);
            o.accept(CABIN);
            o.accept(TOAD_CORE);
            o.accept(LANDING_PAD);
        });
    }
}
