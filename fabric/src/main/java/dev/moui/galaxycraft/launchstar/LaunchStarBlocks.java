package dev.moui.galaxycraft.launchstar;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/** The Launch Star item (crafted two at a time). */
public final class LaunchStarBlocks {
    public static Item LAUNCH_STAR;

    private LaunchStarBlocks() {}

    public static void register() {
        if (LAUNCH_STAR != null) return;
        ResourceKey<Item> k = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("galaxycraft", "launch_star"));
        LAUNCH_STAR = Registry.register(BuiltInRegistries.ITEM, k, new LaunchStarItem(new Item.Properties().setId(k).stacksTo(16)));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(o -> o.accept(LAUNCH_STAR));
    }
}
