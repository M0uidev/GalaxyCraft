package dev.moui.galaxycraft.launchstar;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/** What the Launch Star item does when used, set by the client (LaunchStarClient): stars live on the client, like ships. */
public interface LaunchStarHooks {
    /** A Launch Star used: true if it was put down. */
    boolean place(Player player, InteractionHand hand);

    LaunchStarHooks NONE = (player, hand) -> false;

    static LaunchStarHooks get() {
        return Holder.hooks;
    }

    static void set(LaunchStarHooks hooks) {
        Holder.hooks = hooks == null ? NONE : hooks;
    }

    final class Holder {
        private static volatile LaunchStarHooks hooks = NONE;

        private Holder() {}
    }
}
