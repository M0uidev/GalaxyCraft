package dev.moui.galaxycraft.client.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What a block item makes where it is placed: wall torches, heads and signs are the item's choice, not the block's. */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState galaxycraft$placementState(BlockPlaceContext ctx);
}
