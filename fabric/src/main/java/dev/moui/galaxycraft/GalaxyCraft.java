package dev.moui.galaxycraft;

import dev.moui.galaxycraft.collision.CollisionField;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entrypoint. The collision field is shared by the client and the integrated server. */
public final class GalaxyCraft implements ModInitializer {
    public static final String MOD_ID = "galaxycraft";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    public static final CollisionField FIELD = new CollisionField();

    @Override
    public void onInitialize() {
        dev.moui.galaxycraft.station.StationBlocks.register();
        dev.moui.galaxycraft.ship.ShipBlocks.register();
        dev.moui.galaxycraft.launchstar.LaunchStarBlocks.register();
        LOG.info("GalaxyCraft loaded; waiting for a galaxy on {}", dev.moui.galaxycraft.proto.Layout.SHM_PATH);
        dev.moui.galaxycraft.net.MpPayloads.register();
        dev.moui.galaxycraft.net.MpServer.init();
        ServerTickEvents.END_SERVER_TICK.register(ShadowWorld::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(ShadowWorld::stop);
        // Dev only: a LAN world checks each guest's session with Mojang; two accounts-less dev clients can't
        // (GXC_OFFLINE_LAN=1 turns that check off so a second client can join).
        // Dev only: GXC_LAN_PORT=<port> opens a world to LAN as soon as it starts (a two-game test without menus).
        String lanPort = System.getenv("GXC_LAN_PORT");
        if (lanPort != null) {
            // A little after the host is in: publishing needs the client's connection to exist.
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                if (server.isSingleplayer() && !server.isPublished() && server.getTickCount() > 200 && server.getPlayerList().getPlayerCount() > 0)
                    server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, Integer.parseInt(lanPort));
            });
        }
        if (System.getenv("GXC_OFFLINE_LAN") != null) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> server.setUsesAuthentication(false));
        }
    }
}
