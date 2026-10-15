package dev.moui.galaxycraft.mixin;

import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Netty channel behind a player's connection: how backed up it is says how much to send (multiplayer pacing). */
@Mixin(Connection.class)
public interface ConnectionAccess {
    @Accessor("channel")
    io.netty.channel.Channel galaxycraft$channel();
}
