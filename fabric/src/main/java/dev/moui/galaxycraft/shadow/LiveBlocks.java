package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.net.LiveCodec;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
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

    /** What one pass found: the host's live blocks, and (as of the pass that made them) what the guests are told. */
    record Pass(List<Live> live, List<LiveCodec.Entry> wire) {}

    /** Openness of each lid last pass, to tell opening from closing (a lid moves a tenth a tick). */
    private static final Map<BlockEntity, Float> lastOpen = new WeakHashMap<>();

    /**
     * The live blocks of the mirrored columns within range of Mario's feet (planet space); those
     * already live (wasLive) stay so until LEAVE. Moves chest lids on the way: Minecraft does only
     * on clients, and the shadow is a server level. guests: where the guests' stand-ins are (shadow
     * space): the blocks near them are told to the guests (wire, null: not this pass), those already
     * told (wasWired, cells) stay so until LEAVE too.
     */
    static Pass collect(ServerLevel level, VoxelPlanet planet, ShadowMap map, Set<Long> columns, Vector3d feet,
            Set<Integer> wasLive, List<Vec3> guests, Set<Integer> wasWired, boolean wire) {
        List<Live> out = new ArrayList<>();
        List<LiveCodec.Entry> told = wire ? new ArrayList<>() : null;
        Set<Integer> wired = new HashSet<>();
        if (feet == null && guests.isEmpty()) return new Pass(out, told);
        for (long c : columns) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c));
            if (chunk == null) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockPos pos = be.getBlockPos();
                int cell = map.cell(pos.getX(), pos.getY(), pos.getZ());
                if (cell < 0) continue;
                boolean nearHost = feet != null
                        && CellSpace.point(map.grid, cell, 0.5, 0.5, 0.5).distance(feet) <= (wasLive.contains(cell) ? LEAVE : RANGE);
                boolean nearGuest = false;
                double reach = wasWired.contains(cell) ? LEAVE : RANGE;
                for (Vec3 g : guests)
                    if (g.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= reach * reach) {
                        nearGuest = true;
                        break;
                    }
                if (!nearHost && !nearGuest) continue;
                if (be instanceof ChestBlockEntity chest) ChestBlockEntity.lidAnimateTick(level, pos, be.getBlockState(), chest);
                else if (be instanceof EnderChestBlockEntity chest)
                    EnderChestBlockEntity.lidAnimateTick(level, pos, be.getBlockState(), chest);
                boolean wants = wants(be);
                if (nearHost && out.size() < MAX && wants) out.add(new Live(cell, be));
                if (nearGuest && wants) {
                    wired.add(cell);
                    if (told != null && told.size() < MAX) {
                        LiveCodec.Entry e = entry(level, be, cell);
                        if (e != null) told.add(e);
                    }
                }
                if (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity)
                    lastOpen.put(be, ((LidBlockEntity) be).getOpenNess(1f));
            }
        }
        wasWired.clear();
        wasWired.addAll(wired);
        return new Pass(out, told);
    }

    /** be as the guests are told of it; null if it holds too much to send (a head with a huge profile). */
    private static LiveCodec.Entry entry(ServerLevel level, BlockEntity be, int cell) {
        int type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(be.getType());
        if (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity) {
            float open = ((LidBlockEntity) be).getOpenNess(1f);
            Float before = lastOpen.get(be);
            boolean opening = open >= 1f || before != null && open > before;
            return new LiveCodec.Entry(cell, type, opening ? LiveCodec.LID_OPENING : LiveCodec.LID_CLOSING, Math.round(open * 255), new byte[0]);
        }
        if (be instanceof ShulkerBoxBlockEntity shulker) {
            var st = shulker.getAnimationStatus();
            boolean opening = st == ShulkerBoxBlockEntity.AnimationStatus.OPENING || st == ShulkerBoxBlockEntity.AnimationStatus.OPENED;
            return new LiveCodec.Entry(cell, type, opening ? LiveCodec.LID_OPENING : LiveCodec.LID_CLOSING,
                    Math.round(shulker.getProgress(1f) * 255), new byte[0]);
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (DataOutputStream o = new DataOutputStream(bo)) {
                NbtIo.write(be.saveWithFullMetadata(level.registryAccess()), o);
            }
            return bo.size() > LiveCodec.MAX_DATA ? null : new LiveCodec.Entry(cell, type, LiveCodec.LID_NONE, 0, bo.toByteArray());
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }
}
