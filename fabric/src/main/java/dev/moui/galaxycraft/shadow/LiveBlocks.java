package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;

/**
 * The shadow's block entities that the game draws with their own renderer instead of the baked
 * shape in the chunk mesh: those whose look depends on what they hold (a sign's text, a banner's
 * patterns and its waving, a player head's skin, a shelf's or a campfire's items, a pot's sherds,
 * an open lid), near Mario. Server thread; the client reads the list as of the last tick.
 */
public final class LiveBlocks {
    /** Live within this many blocks of Mario (Minecraft's block entity view distance), dropped past LEAVE. */
    public static final double RANGE = 64, LEAVE = 68;
    /** At most this many a tick (the nearest first are not sorted: a crowd past it loses some). */
    public static final int MAX = 256;

    /** One live block: its planet cell and the shadow's block entity there. */
    public record Live(int cell, BlockEntity entity) {}

    /** The running planet's live blocks, as of the last server tick. */
    public record Snapshot(VoxelPlanet planet, ShadowMap map, List<Live> list) {}

    private LiveBlocks() {}

    /** Whether be's look depends on what it holds now (else the baked shape is it). */
    public static boolean wants(BlockEntity be) {
        return switch (be) {
            case SignBlockEntity s -> s.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).hasMessage(false)
                    || s.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK).hasMessage(false);
            case BannerBlockEntity b -> true;
            case SkullBlockEntity s -> s.getOwnerProfile() != null;
            case ShelfBlockEntity s -> any(s.getItems());
            case CampfireBlockEntity c -> any(c.getItems());
            case DecoratedPotBlockEntity p -> !p.getDecorations().equals(PotDecorations.EMPTY);
            case ChestBlockEntity c -> c.getOpenNess(1f) > 0;
            case EnderChestBlockEntity c -> c.getOpenNess(1f) > 0;
            case ShulkerBoxBlockEntity s -> s.getAnimationStatus() != ShulkerBoxBlockEntity.AnimationStatus.CLOSED;
            default -> false;
        };
    }

    private static boolean any(List<ItemStack> items) {
        for (ItemStack s : items)
            if (!s.isEmpty()) return true;
        return false;
    }

    /**
     * The live blocks of the mirrored columns within range of Mario's feet (planet space); those
     * already live (wasLive) stay so until LEAVE. Moves chest lids on the way: Minecraft does only
     * on clients, and the shadow is a server level.
     */
    static List<Live> collect(ServerLevel level, VoxelPlanet planet, ShadowMap map, Set<Long> columns, Vector3d feet,
            Set<Integer> wasLive) {
        List<Live> out = new ArrayList<>();
        if (feet == null) return out;
        for (long c : columns) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c));
            if (chunk == null) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockPos pos = be.getBlockPos();
                int cell = map.cell(pos.getX(), pos.getY(), pos.getZ());
                if (cell < 0) continue;
                double d = CellSpace.point(map.grid, cell, 0.5, 0.5, 0.5).distance(feet);
                if (d > (wasLive.contains(cell) ? LEAVE : RANGE)) continue;
                if (be instanceof ChestBlockEntity chest) ChestBlockEntity.lidAnimateTick(level, pos, be.getBlockState(), chest);
                else if (be instanceof EnderChestBlockEntity chest)
                    EnderChestBlockEntity.lidAnimateTick(level, pos, be.getBlockState(), chest);
                if (out.size() < MAX && wants(be)) out.add(new Live(cell, be));
            }
        }
        return out;
    }
}
