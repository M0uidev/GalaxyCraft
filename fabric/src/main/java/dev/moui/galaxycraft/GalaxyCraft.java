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
        LOG.info("GalaxyCraft loaded; waiting for a galaxy on {}", dev.moui.galaxycraft.proto.Layout.SHM_PATH);
        ServerTickEvents.END_SERVER_TICK.register(ShadowWorld::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(ShadowWorld::stop);
    }
}
