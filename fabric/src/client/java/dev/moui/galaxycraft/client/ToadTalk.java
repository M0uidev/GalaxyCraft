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
 * opened on the integrated server's player: the world is always singleplayer). Until
 * the 3D map's SystemInfo is in master the system's tier and star are the home system's (0, yellow)
 * and the ship's Warp Core and missing module are not asked.
 */
final class ToadTalk {
    private ToadTalk() {}

    static void talk(LocalPlayer player, ToadRecord toad) {
        ToadContext ctx = new ToadContext(0, "yellow", 0, null, true);
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
