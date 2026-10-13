package dev.moui.galaxycraft.launchstar;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** A Launch Star: put down on the ground ahead; two of them on two planets link. The client decides (LaunchStarHooks). */
public final class LaunchStarItem extends Item {
    public LaunchStarItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) return InteractionResult.PASS;
        return LaunchStarHooks.get().place(player, hand) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
}
