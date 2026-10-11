package dev.moui.galaxycraft.ship;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/** What the ship and landing pad items do when used, set by the client (ShipClient): ships live on the client, like stations. */
public interface ShipHooks {
    /** A Toad Ship used: true if it was put down. */
    boolean place(Player player, InteractionHand hand, ShipModules modules);

    /** A Landing Pad used: true if it was put down. */
    default boolean placePad(Player player, InteractionHand hand) {
        return false;
    }

    ShipHooks NONE = (player, hand, modules) -> false;

    static ShipHooks get() {
        return Holder.hooks;
    }

    static void set(ShipHooks hooks) {
        Holder.hooks = hooks == null ? NONE : hooks;
    }

    final class Holder {
        private static volatile ShipHooks hooks = NONE;

        private Holder() {}
    }
}
