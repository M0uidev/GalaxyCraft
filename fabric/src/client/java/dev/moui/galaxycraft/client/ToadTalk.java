package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.toad.ToadContext;
import dev.moui.galaxycraft.toad.ToadDialogue;
import dev.moui.galaxycraft.toad.ToadRecord;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * What happens when you talk to a Toad: its lines, in chat. The trade comes next (Task 5). Until
 * the 3D map's SystemInfo is in master the system's tier and star are the home system's (0, yellow)
 * and the ship's Warp Core and missing module are not asked.
 */
final class ToadTalk {
    private ToadTalk() {}

    static void talk(LocalPlayer player, ToadRecord toad) {
        ToadContext ctx = new ToadContext(0, "yellow", 0, null, true);
        for (ToadDialogue.Line l : ToadDialogue.lines(toad.type(), ctx))
            player.sendSystemMessage(Component.translatable(l.key(), l.args().toArray()));
    }
}
