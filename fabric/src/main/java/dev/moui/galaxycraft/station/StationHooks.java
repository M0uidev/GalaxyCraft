package dev.moui.galaxycraft.station;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/**
 * What the station items do when used, set by the client (StationClient): the stations live on
 * the client, next to the planets, and the common code never sees client classes.
 */
public interface StationHooks {
    /** A Station Core used in the air: true if a station went up. */
    boolean placeCore(Player player, InteractionHand hand);

    /** A Packed Station used in the air: true if it unfolded. */
    boolean unfold(Player player, InteractionHand hand, String id);

    StationHooks NONE = new StationHooks() {
        @Override public boolean placeCore(Player player, InteractionHand hand) {
            return false;
        }

        @Override public boolean unfold(Player player, InteractionHand hand, String id) {
            return false;
        }
    };

    static StationHooks get() {
        return Holder.hooks;
    }

    static void set(StationHooks hooks) {
        Holder.hooks = hooks == null ? NONE : hooks;
    }

    final class Holder {
        private static volatile StationHooks hooks = NONE;

        private Holder() {}
    }
}
