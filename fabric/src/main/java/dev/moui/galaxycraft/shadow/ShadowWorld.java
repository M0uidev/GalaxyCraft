package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
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
 * which flow across the cube's edges): in the shadow they do not flow. Items dropped there, and
 * those the player throws while on a planet, become the planet's drops ({@link Drop}); experience
 * goes straight to the player.
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

    /**
     * An item dropped: with a planet, at pos and moving by vel (blocks per tick) in its space; with
     * none, in the player's Minecraft space (thrown by the player). Picked up after delay ticks.
     */
    public record Drop(VoxelPlanet planet, org.joml.Vector3d pos, org.joml.Vector3d vel, ItemStack stack, int delay) {}

    /** Ticks before a block's drops or a thrown item can be picked up (Minecraft's). */
    static final int BLOCK_DROP_DELAY = 10, THROWN_DELAY = 40;

    private static final ConcurrentLinkedQueue<Consumer<ServerLevel>> ops = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Change> changes = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> toClient = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Drop> drops = new ConcurrentLinkedQueue<>();
    private static volatile int applied;
    private static volatile boolean available, catchThrown;

    // Server thread only.
    private static MinecraftServer server;
    private static VoxelPlanet planet;
    private static ShadowMap map;
    private static final Set<Long> forced = new HashSet<>(), mirrored = new HashSet<>();
    private static final ArrayDeque<Long> toMirror = new ArrayDeque<>();
    private static final ArrayDeque<Integer> edgeChanged = new ArrayDeque<>();
    private static BlockPos writingPos;
    private static volatile BlockPos mario = BlockPos.ZERO;
    private static volatile Entities entities;
    private static volatile MarioAt marioAt;
    private static final ConcurrentLinkedQueue<Particle> particles = new ConcurrentLinkedQueue<>();
    /** Particles waiting for the client at most: more are dropped (a big explosion makes plenty). */
    static final int MAX_PARTICLES = 512;
    private static MarioProxy proxy;
    /** Shadow entities handed to the client each tick, at most. */
    static final int MAX_ENTITIES = 256;

    private ShadowWorld() {}

    // ---- client thread ----

    /**
     * The running planet's entities in the shadow (mobs, primed TNT, falling blocks; not players
     * nor items, which become planet drops), as of the last server tick, and where the shadow
     * lies on the planet. Read from the client thread: the entities keep moving meanwhile.
     */
    public record Entities(VoxelPlanet planet, ShadowMap map, List<Entity> list) {}

    public static Entities entities() {
        return entities;
    }

    /** Where Mario is on the running planet (blocks, its space), where he looks, and who plays him. */
    public record MarioAt(VoxelPlanet planet, org.joml.Vector3d feet, org.joml.Vector3d look, UUID player) {}

    /** Client tick: Mario's stand-in in the shadow goes where he is (null: none). */
    public static void mario(MarioAt at) {
        marioAt = at;
    }

    /** A particle Minecraft made in the shadow: at pos, moving by vel (blocks per tick), planet space. */
    public record Particle(VoxelPlanet planet, net.minecraft.core.particles.ParticleOptions options, org.joml.Vector3d pos,
            org.joml.Vector3d vel) {}

    public static Particle pollParticle() {
        return particles.poll();
    }

    /** Mario's stand-in in the shadow, for tests: where, game mode; "none" if there is none. */
    public static String proxyState() {
        MarioProxy p = proxy;
        return p == null ? "none" : p.blockPosition().toShortString() + " " + p.gameMode() + (p.isRemoved() ? " removed" : "");
    }

    /** The player hits a shadow entity (by its id) as Mario, from where he stands there. */
    public static void attack(int entityId, UUID player) {
        ops.add(level -> {
            Entity target = level.getEntity(entityId);
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
            if (target == null || p == null || proxy == null || proxy.isRemoved()) return;
            // The blow is the player's: what is in hand (the same stack, so it wears out) and how charged it is.
            proxy.setItemInHand(InteractionHand.MAIN_HAND, p.getMainHandItem());
            proxy.getAttributes().assignAllValues(p.getAttributes()); // the weapon's damage and speed
            ((dev.moui.galaxycraft.mixin.LivingEntityAccessor) (Object) proxy).galaxycraft$setAttackStrengthTicker(
                    ((dev.moui.galaxycraft.mixin.LivingEntityAccessor) p).galaxycraft$attackStrengthTicker());
            Vec3 to = target.position().subtract(proxy.position());
            proxy.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
            proxy.attack(target);
            p.resetAttackStrengthTicker();
            proxy.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        });
    }
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

    public static Drop pollDrop() {
        return drops.poll();
    }

    /** Whether items the player throws (Q, out of the inventory) land on the planet instead of in Minecraft. */
    public static void catchThrown(boolean on) {
        catchThrown = on;
    }

    /**
     * The player breaks cell, as ServerPlayerGameMode.destroyBlock does: the block's own breaking
     * (a door's other half, ice to water), the tool worn, and outside creative its drops.
     */
    public static void destroy(VoxelPlanet p, int cell, UUID player) {
        ops.add(level -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(player);
            if (p != planet || map == null || sp == null) return;
            BlockPos pos = new BlockPos(map.x(cell), map.y(cell), map.z(cell));
            mirrorNow(level, pos);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !sp.getMainHandItem().canDestroyBlock(state, level, pos, sp)) return;
            var blockEntity = level.getBlockEntity(pos);
            Block block = state.getBlock();
            BlockState adjusted = block.playerWillDestroy(level, pos, state, sp);
            boolean removed = level.removeBlock(pos, false);
            if (removed) block.destroy(level, pos, adjusted);
            if (sp.preventsBlockDrops()) return;
            ItemStack tool = sp.getMainHandItem(), with = tool.copy();
            boolean harvest = sp.hasCorrectToolForDrops(adjusted);
            tool.mineBlock(level, adjusted, pos, sp);
            if (removed && harvest) block.playerDestroy(level, sp, pos, adjusted, blockEntity, with);
        });
    }

    /** A drop picked up: into the player's inventory; what does not fit comes back through left. */
    public static void give(UUID player, ItemStack stack, Consumer<ItemStack> left) {
        ops.add(level -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(player);
            if (sp == null) {
                toClient.add(() -> left.accept(stack));
                return;
            }
            int before = stack.getCount();
            sp.getInventory().add(stack);
            if (stack.getCount() < before)
                sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.2f,
                        ((sp.getRandom().nextFloat() - sp.getRandom().nextFloat()) * 0.7f + 1) * 2);
            if (!stack.isEmpty()) toClient.add(() -> left.accept(stack));
        });
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
        moveProxy(level);
        entities = map == null ? null : new Entities(planet, map, collect(level));
    }

    /** Mario's stand-in: made when there is a planet and a player, kept where Mario is. */
    private static void moveProxy(ServerLevel level) {
        MarioAt at = marioAt;
        ServerPlayer p = at == null || map == null || at.planet() != planet ? null : level.getServer().getPlayerList().getPlayer(at.player());
        int cell = p == null ? -1 : map.grid.cellAt(at.feet());
        if (cell < 0) {
            if (proxy != null) proxy.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            return;
        }
        if (proxy == null || proxy.isRemoved() || proxy.level() != level) {
            proxy = new MarioProxy(level);
            level.addNewPlayer(proxy);
        }
        proxy.follow(at.player());
        net.minecraft.world.level.GameType mode = p.isAlive() ? p.gameMode() : net.minecraft.world.level.GameType.SPECTATOR;
        if (proxy.gameMode() != mode) proxy.setGameMode(mode);
        org.joml.Vector3d m = CellSpace.local(map.grid, cell, at.feet());
        org.joml.Vector3d d = CellSpace.direction(map.grid, cell, at.look());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(d.y, -1, 1)));
        proxy.snapTo(map.x(cell) + m.x, map.y(cell) + m.y, map.z(cell) + m.z, yaw, pitch);
        proxy.setYHeadRot(yaw);
        if (p.isAlive()) proxy.setHealth(p.getHealth());
    }

    // ---- particles (server thread, from the mixins) ----

    /** One particle made in the shadow at (x, y, z) moving by (vx, vy, vz): to the planet's space. */
    public static void particle(net.minecraft.core.particles.ParticleOptions options, double x, double y, double z, double vx,
            double vy, double vz) {
        if (map == null || particles.size() >= MAX_PARTICLES) return;
        double[] f = map.frame(x, y, z);
        if (f == null) return;
        // The frame is about the shadow position given: the point itself is its origin.
        org.joml.Vector3d pos = new org.joml.Vector3d(f[0], f[1], f[2]);
        org.joml.Vector3d vel = new org.joml.Vector3d(f[3] * vx + f[6] * vy + f[9] * vz, f[4] * vx + f[7] * vy + f[10] * vz,
                f[5] * vx + f[8] * vy + f[11] * vz);
        particles.add(new Particle(planet, options, pos, vel));
    }

    /** ServerLevel.sendParticles in the shadow: as a client spreads them (count 0: one, moving by dist times speed). */
    public static void sendParticles(net.minecraft.core.particles.ParticleOptions options, double x, double y, double z, int count,
            double dx, double dy, double dz, double sx, double sy, double sz) {
        java.util.Random r = new java.util.Random();
        if (count == 0) {
            particle(options, x, y, z, dx * sx, dy * sy, dz * sz);
            return;
        }
        for (int i = 0; i < Math.min(count, 64); i++)
            particle(options, x + r.nextGaussian() * dx, y + r.nextGaussian() * dy, z + r.nextGaussian() * dz,
                    r.nextGaussian() * sx, r.nextGaussian() * sy, r.nextGaussian() * sz);
    }

    /** An explosion in the shadow: its flash of particles, and its sound (sent only to players there). */
    public static void explosion(ServerLevel level, double x, double y, double z, float radius,
            net.minecraft.core.particles.ParticleOptions flash, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound) {
        particle(flash, x, y, z, 0, 0, 0);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 16; i++)
            particle(net.minecraft.core.particles.ParticleTypes.POOF, x + r.nextGaussian() * radius / 3, y + r.nextGaussian() * radius / 3,
                    z + r.nextGaussian() * radius / 3, r.nextGaussian() * 0.15, r.nextGaussian() * 0.15, r.nextGaussian() * 0.15);
        level.playSound(null, x, y, z, sound, net.minecraft.sounds.SoundSource.BLOCKS, 4.0F,
                (1.0F + (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.2F) * 0.7F);
    }

    /** A block broken in the shadow (level event 2001): its pieces fly, as a client shows them. */
    public static void blockBroken(BlockPos pos, int stateId) {
        BlockState s = Block.stateById(stateId);
        if (s == null || s.isAir()) return;
        var options = new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, s);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 12; i++) {
            double fx = r.nextDouble(), fy = r.nextDouble(), fz = r.nextDouble();
            particle(options, pos.getX() + fx, pos.getY() + fy, pos.getZ() + fz, (fx - 0.5) * 0.15, fy * 0.15 + 0.05,
                    (fz - 0.5) * 0.15);
        }
    }

    /** The planet's entities, those that walked off a face's edge put on the next face. */
    private static List<Entity> collect(ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            // Dying mobs stay until Minecraft removes them: they fall over first.
            if (e instanceof Player || e instanceof net.minecraft.world.entity.item.ItemEntity
                    || e.isRemoved() || !map.inStrip((int) Math.floor(e.getZ())))
                continue;
            if (!e.isPassenger() && e.isAlive()) wrap(level, e);
            if (out.size() < MAX_ENTITIES) out.add(e);
        }
        return List.copyOf(out);
    }

    private static void wrap(ServerLevel level, Entity e) {
        ShadowMap.Wrap w = map.wrap(e.getX(), e.getY(), e.getZ());
        if (w == null || !level.isLoaded(BlockPos.containing(w.x(), w.y(), w.z()))) return;
        float yaw = turn(w, e.getYRot());
        Vec3 v = e.getDeltaMovement();
        org.joml.Vector3d nv = w.turn().transform(new org.joml.Vector3d(v.x, v.y, v.z));
        e.snapTo(w.x(), w.y(), w.z(), yaw, e.getXRot());
        e.setOldPosAndRot();
        e.setDeltaMovement(nv.x, nv.y, nv.z);
        if (e instanceof LivingEntity le) {
            le.setYBodyRot(turn(w, le.yBodyRot));
            le.setYHeadRot(turn(w, le.getYHeadRot()));
            if (le instanceof Mob mob) mob.getNavigation().stop();
        }
    }

    /** A yaw (Minecraft's: looking along (-sin, 0, cos)) turned as the wrap turns directions. */
    private static float turn(ShadowMap.Wrap w, float yaw) {
        double r = Math.toRadians(yaw);
        org.joml.Vector3d f = w.turn().transform(new org.joml.Vector3d(-Math.sin(r), 0, Math.cos(r)));
        return (float) Math.toDegrees(Math.atan2(-f.x, f.z));
    }

    /** The server is stopping: nothing stays forced, nothing is left running. */
    public static void stop(MinecraftServer s) {
        ServerLevel level = s.getLevel(KEY);
        if (level != null) release(level);
        planet = null;
        map = null;
        entities = null;
        available = false;
        server = null;
        ops.clear();
    }

    private static void release(ServerLevel level) {
        if (proxy != null && !proxy.isRemoved()) level.removePlayerImmediately(proxy, Entity.RemovalReason.DISCARDED);
        proxy = null;
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

    /**
     * An item entity about to join a level: true if it becomes a planet drop instead (every one
     * dropped in the shadow; one thrown near a player while catchThrown).
     */
    public static boolean catchItem(ServerLevel level, net.minecraft.world.entity.item.ItemEntity e) {
        Vec3 v = e.getDeltaMovement();
        if (isShadow(level)) {
            if (map == null) return true;
            int bx = (int) Math.floor(e.getX()), by = (int) Math.floor(e.getY()), bz = (int) Math.floor(e.getZ());
            int cell = map.cell(bx, by, bz);
            if (cell >= 0) {
                double fx = e.getX() - bx, fy = e.getY() - by, fz = e.getZ() - bz;
                org.joml.Vector3d at = CellSpace.point(map.grid, cell, fx, fy, fz);
                org.joml.Vector3d vel = CellSpace.point(map.grid, cell, fx + v.x, fy + v.y, fz + v.z).sub(at);
                drops.add(new Drop(planet, at, vel, e.getItem().copy(), BLOCK_DROP_DELAY));
            }
            return true;
        }
        if (!catchThrown || level.getNearestPlayer(e, 8) == null) return false;
        drops.add(new Drop(null, new org.joml.Vector3d(e.getX(), e.getY(), e.getZ()), new org.joml.Vector3d(v.x, v.y, v.z),
                e.getItem().copy(), e.hasPickUpDelay() ? THROWN_DELAY : 0));
        return true;
    }

    /** Entity events that only start an animation (or make particles: 60, a mob's last poof) on the client, replayed on shadow entities. */
    private static final Set<Byte> ANIMATION_EVENTS = Set.of((byte) 1, (byte) 4, (byte) 10, (byte) 11, (byte) 34,
            (byte) 39, (byte) 45, (byte) 58, (byte) 59, (byte) 60, (byte) 61, (byte) 62, (byte) 66);
    /** By class: its client-only setupAnimationStates (bats, rabbits, camels...), or none. */
    private static final java.util.Map<Class<?>, java.util.Optional<java.lang.reflect.Method>> SETUP =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * An entity event of the shadow (an iron golem's swing, a sheep grazing): what a client would
     * do with it, done on the server's entity, which is the one the game draws. Only events that
     * start animations: others (a death) would act twice.
     */
    public static void entityEvent(Entity e, byte id) {
        if (!ANIMATION_EVENTS.contains(id)) return;
        try {
            e.handleEntityEvent(id);
        } catch (RuntimeException ex) {
            GalaxyCraft.LOG.debug("Entity event {} of {} in the shadow: {}", id, e.getType(), ex.toString());
        }
    }

    /** What the client calls each tick to start and stop a mob's animation states. */
    public static void setupAnimationStates(LivingEntity e) {
        SETUP.computeIfAbsent(e.getClass(), c -> {
            for (Class<?> k = c; k != null && k != LivingEntity.class; k = k.getSuperclass())
                try {
                    java.lang.reflect.Method m = k.getDeclaredMethod("setupAnimationStates");
                    m.setAccessible(true);
                    return java.util.Optional.of(m);
                } catch (NoSuchMethodException ignored) {
                    // up the hierarchy
                }
            return java.util.Optional.empty();
        }).ifPresent(m -> {
            try {
                m.invoke(e);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                SETUP.put(e.getClass(), java.util.Optional.empty());
            }
        });
    }

    /** Experience from the shadow (ores, furnaces): to the player. */
    public static void giveExperience(ServerLevel level, int value) {
        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            sp.giveExperiencePoints(value);
            return;
        }
    }

    /** Where Mario is in the shadow: sounds made there are heard from this far away. */
    public static double distanceToMario(double x, double y, double z) {
        BlockPos m = mario;
        return Math.sqrt(m.distToCenterSqr(x, y, z));
    }
}
