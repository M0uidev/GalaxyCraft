package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The planet's blocks as Minecraft runs them: the cells near Mario are copied into a dimension of
 * the integrated server (galaxycraft:shadow, see {@link ShadowMap}) whose chunks are kept loaded
 * and ticking, so doors, redstone, pistons, crops, fire and every other block behave as
 * Minecraft's own code says. What changes there comes back to the planet; the planet's changes
 * (the player's, the fluids') go there. Fluids stay the planet's ({@link dev.moui.galaxycraft.voxel.Fluids},
 * which flow across the cube's edges): in the shadow they do not flow, and items and experience
 * dropped there vanish.
 *
 * The planet lives on the client thread and the shadow on the server's: they talk through queues.
 * Each change the client sends carries a number; a change coming back is dropped if the client
 * changed that cell since (newer than what the server had applied when it made it).
 */
public final class ShadowWorld {
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(GalaxyCraft.MOD_ID, "shadow"));
    /** Columns of 16×16 blocks copied in per server tick. */
    static final int MIRROR_PER_TICK = 4;
    /** Copying in: no neighbor updates, no onPlace (the planet's states already fit together). */
    static final int MIRROR_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    /** A change Minecraft made: cell to id, made when the server had applied the client's change seq. */
    public record Change(VoxelPlanet planet, int cell, int id, int seq) {}

    private static final ConcurrentLinkedQueue<Consumer<ServerLevel>> ops = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Change> changes = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> toClient = new ConcurrentLinkedQueue<>();
    private static volatile int applied;
    private static volatile boolean available;

    // Server thread only.
    private static MinecraftServer server;
    private static VoxelPlanet planet;
    private static ShadowMap map;
    private static final Set<Long> forced = new HashSet<>(), mirrored = new HashSet<>();
    private static final ArrayDeque<Long> toMirror = new ArrayDeque<>();
    private static final ArrayDeque<Integer> edgeChanged = new ArrayDeque<>();
    private static BlockPos writingPos;
    private static volatile BlockPos mario = BlockPos.ZERO;

    private ShadowWorld() {}

    // ---- client thread ----

    /** Whether an integrated server with the shadow dimension is running. */
    public static boolean available() {
        return available;
    }

    /** The client's last change the server has applied. */
    public static int applied() {
        return applied;
    }

    public static Change pollChange() {
        return changes.poll();
    }

    /** What the server hands back to the client thread (a click that used nothing places a block). */
    public static Runnable pollClient() {
        return toClient.poll();
    }

    /** This planet, of this stage, is the one to run (null: none). */
    public static void attach(VoxelPlanet p, String stage) {
        ops.add(level -> {
            release(level);
            planet = p;
            map = p == null ? null : new ShadowMap(p.grid, stage);
        });
    }

    /** The columns to keep running (ChunkPos longs) and the cell Mario is in. */
    public static void follow(VoxelPlanet p, Set<Long> columns, int marioCell) {
        ops.add(level -> {
            if (p != planet || map == null) return;
            if (marioCell >= 0) mario = new BlockPos(map.x(marioCell), map.y(marioCell), map.z(marioCell));
            for (Long c : new HashSet<>(forced))
                if (!columns.contains(c)) {
                    level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
                    forced.remove(c);
                    mirrored.remove(c);
                }
            for (Long c : columns)
                if (forced.add(c)) {
                    level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), true);
                    toMirror.add(c);
                }
        });
    }

    /** The client changed a cell (its change number seq). */
    public static void set(VoxelPlanet p, int cell, int id, int seq) {
        ops.add(level -> {
            applied = seq;
            if (p != planet || map == null) return;
            BlockState s = state(id);
            write(level, new BlockPos(map.x(cell), map.y(cell), map.z(cell)), s, Block.UPDATE_ALL);
            for (int[] h : map.halos(cell)) write(level, new BlockPos(h[0], h[1], h[2]), s, Block.UPDATE_ALL);
        });
    }

    /**
     * A right click on cell's side face at hit (its model space), as Minecraft's
     * ServerPlayerGameMode.useItemOn does it, but leaving block items and buckets to the planet:
     * the block's use (a door opens, a lever flips, a chest opens its menu), else the held item's
     * use on it (flint and steel, bone meal, a hoe). If neither does anything, onPass runs on the
     * client thread.
     */
    public static void use(VoxelPlanet p, int cell, int face, Vec3 hit, UUID player, Runnable onPass) {
        ops.add(level -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(player);
            if (p != planet || map == null || sp == null) {
                toClient.add(onPass);
                return;
            }
            BlockPos pos = new BlockPos(map.x(cell), map.y(cell), map.z(cell));
            mirrorNow(level, pos);
            BlockHitResult h = new BlockHitResult(Vec3.atLowerCornerOf(pos).add(hit),
                    Direction.values()[CellSpace.DIRECTION_OF_SIDE[face]], pos, false);
            if (!use(level, sp, h).consumesAction()) toClient.add(onPass);
        });
    }

    private static InteractionResult use(ServerLevel level, ServerPlayer sp, BlockHitResult h) {
        BlockState state = level.getBlockState(h.getBlockPos());
        ItemStack stack = sp.getMainHandItem();
        InteractionResult r = InteractionResult.PASS;
        boolean handsFull = !stack.isEmpty() || !sp.getOffhandItem().isEmpty();
        if (!(sp.isSecondaryUseActive() && handsFull)) {
            r = state.useItemOn(stack, level, sp, InteractionHand.MAIN_HAND, h);
            if (r instanceof InteractionResult.TryEmptyHandInteraction) r = state.useWithoutItem(level, sp, h);
        }
        if (r.consumesAction() || stack.isEmpty() || stack.getItem() instanceof BlockItem
                || stack.getItem() instanceof BucketItem || sp.getCooldowns().isOnCooldown(stack))
            return r;
        int count = stack.getCount();
        r = stack.useOn(new UseOnContext(level, sp, InteractionHand.MAIN_HAND, stack, h));
        if (sp.hasInfiniteMaterials()) stack.setCount(count);
        else if (r instanceof InteractionResult.Success s && s.heldItemTransformedTo() != null
                && s.heldItemTransformedTo() != stack)
            sp.setItemInHand(InteractionHand.MAIN_HAND, s.heldItemTransformedTo());
        return r;
    }

    // ---- server thread ----

    /** End of each server tick: the client's requests, then copying in, then the edges' copies. */
    public static void tick(MinecraftServer s) {
        server = s;
        ServerLevel level = s.getLevel(KEY);
        available = level != null;
        if (level == null) {
            ops.clear();
            return;
        }
        for (Consumer<ServerLevel> op; (op = ops.poll()) != null; ) op.accept(level);
        for (int n = 0; n < MIRROR_PER_TICK && !toMirror.isEmpty(); n++) mirror(level, toMirror.poll());
        flushEdges(level);
    }

    /** The server is stopping: nothing stays forced, nothing is left running. */
    public static void stop(MinecraftServer s) {
        ServerLevel level = s.getLevel(KEY);
        if (level != null) release(level);
        planet = null;
        map = null;
        available = false;
        server = null;
        ops.clear();
    }

    private static void release(ServerLevel level) {
        for (Long c : forced) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
        forced.clear();
        mirrored.clear();
        toMirror.clear();
        edgeChanged.clear();
    }

    private static void mirrorNow(ServerLevel level, BlockPos pos) {
        long c = ChunkPos.pack(pos);
        if (mirrored.contains(c)) return;
        if (forced.add(c)) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), true);
        toMirror.remove(c);
        mirror(level, c);
    }

    /** Copies the planet's cells (and halo copies) of a column in where they differ. */
    private static void mirror(ServerLevel level, long column) {
        if (map == null || !forced.contains(column)) return;
        int x0 = ChunkPos.getX(column) << 4, z0 = ChunkPos.getZ(column) << 4;
        mirrored.add(column);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x < x0 + 16; x++)
            for (int z = z0; z < z0 + 16; z++)
                for (int y = 0; y < map.grid.layers; y++) {
                    int cell = map.cell(x, y, z);
                    if (cell < 0) cell = map.haloSource(x, y, z);
                    if (cell < 0) {
                        if (y == 0 && map.haloSource(x, 0, z) < 0) break; // not this planet's column
                        continue;
                    }
                    BlockState s = state(planet.get(cell));
                    if (level.getBlockState(pos.set(x, y, z)) != s) write(level, pos.immutable(), s, MIRROR_FLAGS);
                }
    }

    private static void write(ServerLevel level, BlockPos pos, BlockState s, int flags) {
        if (!mirrored.contains(ChunkPos.pack(pos))) return;
        BlockPos was = writingPos;
        writingPos = pos;
        try {
            level.setBlock(pos, s, flags);
        } finally {
            writingPos = was;
        }
    }

    /** Cells on a face's edge that changed here: their halo copies take their new state. */
    private static void flushEdges(ServerLevel level) {
        for (int n = 0; n < 4096 && !edgeChanged.isEmpty(); n++) {
            int cell = edgeChanged.poll();
            BlockState s = level.getBlockState(new BlockPos(map.x(cell), map.y(cell), map.z(cell)));
            for (int[] h : map.halos(cell)) write(level, new BlockPos(h[0], h[1], h[2]), s, Block.UPDATE_ALL);
        }
    }

    private static BlockState state(int id) {
        BlockState s = Block.stateById(id);
        return s == null ? Blocks.AIR.defaultBlockState() : s;
    }

    // ---- hooks (server thread, from the mixins) ----

    public static boolean isShadow(Level level) {
        return level.dimension() == KEY;
    }

    /** Whether Minecraft may change this position: a cell of the running planet, not halo, gaps or other planets. */
    public static boolean writable(BlockPos pos) {
        if (pos.equals(writingPos)) return true;
        return map != null && map.cell(pos.getX(), pos.getY(), pos.getZ()) >= 0;
    }

    /** A block of the shadow changed: back to the planet, unless GalaxyCraft itself wrote it there. */
    public static void changed(BlockPos pos, BlockState now) {
        if (map == null || pos.equals(writingPos)) return;
        int cell = map.cell(pos.getX(), pos.getY(), pos.getZ());
        if (cell < 0) return;
        changes.add(new Change(planet, cell, Block.getId(now), applied));
        if (map.onEdge(cell)) edgeChanged.add(cell);
    }

    /** Where Mario is in the shadow: sounds made there are heard from this far away. */
    public static double distanceToMario(double x, double y, double z) {
        BlockPos m = mario;
        return Math.sqrt(m.distToCenterSqr(x, y, z));
    }
}
