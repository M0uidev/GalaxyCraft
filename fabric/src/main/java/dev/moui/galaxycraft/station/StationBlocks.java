package dev.moui.galaxycraft.station;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * The stations' block and items: the core block (a station's middle, never mined, never dropped),
 * the Station Core item that makes a station, and the Packed Station item that carries one.
 */
public final class StationBlocks {
    public static final ResourceKey<Block> CORE_KEY = ResourceKey.create(Registries.BLOCK, id("station_core"));
    public static final ResourceKey<Item> CORE_ITEM_KEY = ResourceKey.create(Registries.ITEM, id("station_core"));
    public static final ResourceKey<Item> PACKED_KEY = ResourceKey.create(Registries.ITEM, id("packed_station"));
    /** The core's block state as planets name blocks. */
    public static final String CORE_NAME = "galaxycraft:station_core";

    public static Block CORE;
    public static Item CORE_ITEM, PACKED;

    private StationBlocks() {}

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("galaxycraft", path);
    }

    public static void register() {
        if (CORE != null) return;
        // Unbreakable as bedrock: a station loses its core only by being packed up.
        CORE = Registry.register(BuiltInRegistries.BLOCK, CORE_KEY, new Block(BlockBehaviour.Properties.of().setId(CORE_KEY)
                .strength(-1, 3_600_000).noLootTable().sound(SoundType.METAL).lightLevel(s -> 7)
                .pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE)));
        CORE_ITEM = Registry.register(BuiltInRegistries.ITEM, CORE_ITEM_KEY, new StationCoreItem(new Item.Properties().setId(CORE_ITEM_KEY).stacksTo(16)));
        PACKED = Registry.register(BuiltInRegistries.ITEM, PACKED_KEY, new PackedStationItem(new Item.Properties().setId(PACKED_KEY).stacksTo(1)));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> output.accept(CORE_ITEM));
    }
}
