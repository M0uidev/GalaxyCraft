package dev.moui.galaxycraft.ship;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** A landing pad: put down on the ground ahead, a flat disc a ship docks on. The client decides (ShipHooks). */
public final class LandingPadItem extends Item {
    public LandingPadItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) return InteractionResult.PASS;
        return ShipHooks.get().placePad(player, hand) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
}
