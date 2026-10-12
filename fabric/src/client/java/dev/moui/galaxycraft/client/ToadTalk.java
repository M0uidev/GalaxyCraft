package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.toad.ToadContext;
import dev.moui.galaxycraft.toad.ToadDialogue;
import dev.moui.galaxycraft.toad.ToadRecord;
import dev.moui.galaxycraft.toad.ToadMerchant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

/**
 * What happens when you talk to a Toad: its lines, in chat, then its trade (Minecraft's own screen,
 * opened on the integrated server's player: the world is always singleplayer). The system's tier and
 * star are those of the system the player is in (home: tier 0, yellow); the ship's Warp Core and
 * missing module are not asked yet.
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
        for (ToadDialogue.Line l : ToadDialogue.lines(toad.type(), ctx))
            player.sendSystemMessage(Component.translatable(l.key(), l.args().toArray()));
        ToadMerchant merchant = ToadMerchant.of(toad.type(), ctx.systemTier());
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (merchant == null || server == null) return; // it sells nothing here: it only talks
        java.util.UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            merchant.setTradingPlayer(sp);
            merchant.openTradingScreen(sp, ToadMerchant.title(toad.type()), 1);
        });
    }
}
