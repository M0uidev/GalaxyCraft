package dev.moui.galaxycraft.station;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The Station Core in hand: used in open space, a station goes up around it. Not a block item:
 * it never goes down as a block on a planet; the core block is only the station's own.
 */
public final class StationCoreItem extends Item {
    public StationCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) return InteractionResult.PASS; // the client decides and asks the server to use it up
        return StationHooks.get().placeCore(player, hand) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
}
