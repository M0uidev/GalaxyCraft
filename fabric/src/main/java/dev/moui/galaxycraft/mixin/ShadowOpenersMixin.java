package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A shadow chest's openers: Minecraft counts the players near it with it open, but the player who
 * opened it from a planet is in another level, so the recheck would shut its lid after 5 ticks.
 */
@Mixin(ContainerOpenersCounter.class)
abstract class ShadowOpenersMixin {
    @Inject(method = "getEntitiesWithContainerOpen", at = @At("RETURN"), cancellable = true)
    private void galaxycraft$planetPlayers(Level level, BlockPos pos, CallbackInfoReturnable<List<ContainerUser>> cir) {
        if (level.dimension() != ShadowWorld.KEY || level.getServer() == null) return;
        ContainerOpenersCounter self = (ContainerOpenersCounter) (Object) this;
        List<ContainerUser> out = null;
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers())
            if (p.level() != level && self.isOwnContainer(p)) {
                if (out == null) out = new ArrayList<>(cir.getReturnValue());
                out.add(p);
            }
        if (out != null) cir.setReturnValue(out);
    }
}
