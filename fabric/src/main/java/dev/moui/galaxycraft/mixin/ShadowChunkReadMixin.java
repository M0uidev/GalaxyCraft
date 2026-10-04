package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game's copy of the shadow's entities is made on the client thread, where renderers read
 * the shadow's blocks (a minecart its rails). Off the server thread Minecraft hands every chunk
 * lookup to the server and waits, which locks both threads up while the server waits on the
 * client: a chunk already loaded is handed over at once instead, and one that is not is empty.
 */
@Mixin(ServerChunkCache.class)
abstract class ShadowChunkReadMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    public abstract LevelChunk getChunkNow(int x, int z);

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void galaxycraft$loadedAtOnce(int x, int z, ChunkStatus status, boolean required, CallbackInfoReturnable<ChunkAccess> cir) {
        if (Thread.currentThread() == level.getServer().getRunningThread() || !ShadowWorld.isShadow(level)) return;
        LevelChunk chunk = getChunkNow(x, z);
        cir.setReturnValue(chunk != null ? chunk
                : new EmptyLevelChunk(level, new ChunkPos(x, z),
                        level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)));
    }
}
