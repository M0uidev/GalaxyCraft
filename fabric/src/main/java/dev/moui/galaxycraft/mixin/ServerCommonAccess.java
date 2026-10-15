package dev.moui.galaxycraft.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A player's connection (the listener keeps it protected). */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonAccess {
    @Accessor("connection")
    Connection galaxycraft$connection();
}
