package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.toad.ToadContext;
import dev.moui.galaxycraft.toad.ToadMerchant;
import dev.moui.galaxycraft.toad.ToadRecord;
import dev.moui.galaxycraft.toad.ToadScript;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What happens when you talk to a Toad: its dialogue box opens (ToadClient runs it, the player keeps
 * moving) and, if Trade is chosen, Minecraft's merchant screen (opened on the integrated server's
 * player: the world is always singleplayer). The system's tier and star are those of the system
 * the player is in (home: tier 0, yellow); the ship's Warp Core and missing module are not asked yet.
 */
final class ToadTalk {
    private ToadTalk() {}

    /** The system the player is in, as a Toad knows it (home's when the universe or the system is not known). */
    static ToadContext context() {
        var u = UniverseClient.universe();
        if (u == null) return ToadContext.home();
        return Warp.here().map(star -> ToadContext.forSystem(u.tier(star), u.starType(star))).orElse(ToadContext.home());
    }

    static void talk(LocalPlayer player, ToadRecord toad) {
        ToadContext ctx = context();
        boolean canTrade = ToadMerchant.of(toad.type(), ctx.systemTier()) != null;
        ToadClient.begin(player, toad, ToadScript.open(toad.type(), ctx, canTrade), ctx);
    }

    /** Trade chosen: the merchant screen. */
    static void trade(LocalPlayer player, ToadRecord toad, ToadContext ctx) {
        ToadMerchant merchant = ToadMerchant.of(toad.type(), ctx.systemTier());
        if (merchant == null) return; // it sells nothing here
        if (MpUse.guest()) { // the host opens the screen on this player
            MpToads.trade(toad, ctx.systemTier());
            return;
        }
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        java.util.UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            merchant.setTradingPlayer(sp);
            merchant.openTradingScreen(sp, ToadMerchant.title(toad.type()), 1);
        });
    }
}
