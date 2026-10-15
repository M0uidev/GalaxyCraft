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
    /**
     * Time each server tick may spend copying the planet in, ns: a row of 16 cells by the planet's
     * layers at a time. Copying in whole columns, a few a tick, a new planet's dozens of them
     * (thousands of blocks each, lit as they go) made the server's ticks last hundreds of ms.
     */
    static final long MIRROR_NANOS = 4_000_000;
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
    private static final ConcurrentLinkedQueue<Bed> beds = new ConcurrentLinkedQueue<>();
    private static volatile int applied;
    private static volatile boolean available, catchThrown;

    // Server thread only.
    private static MinecraftServer server;
    /**
     * What one running planet holds. The server thread works on {@link #cur}, the run of the planet it is dealing
     * with (set around each request, hook and tick); the fields read from the client thread are volatile.
     * {@link #primary} is the host's own planet.
     */
    private static final class Run {
        final VoxelPlanet planet;
        ShadowMap map;
        final Set<Long> forced = new HashSet<>(), mirrored = new HashSet<>();
        final ArrayDeque<Long> toMirror = new ArrayDeque<>();
        /** The column being copied in (in mirrored already, so edits reach it), and its next row of x; null: none. */
        Long mirroring;
        int mirrorX;
        final ArrayDeque<Integer> edgeChanged = new ArrayDeque<>();
        volatile BlockPos mario = BlockPos.ZERO;
        volatile Entities entities;
        MarioProxy proxy;
        Entity riding;
        volatile Seat seat;
        volatile LiveBlocks.Snapshot live;
        /** The cells live last tick (they leave a little farther than they come). */
        final Set<Integer> liveCells = new HashSet<>();
        /** Mobs that may despawn, by category, as of the last tick. */
        final int[] mobCounts = new int[net.minecraft.world.entity.MobCategory.values().length];

        Run(VoxelPlanet planet) {
            this.planet = planet;
        }
    }

    /** The runs by planet (server thread), the host's own planet's, and the one being worked on (server thread). */
    private static final java.util.Map<VoxelPlanet, Run> runs = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Run primary = new Run(null);
    private static Run cur = primary;

    private static BlockPos writingPos;
    private static volatile MarioAt marioAt;
    private static final ConcurrentLinkedQueue<Particle> particles = new ConcurrentLinkedQueue<>();
    /** Particles waiting for the client at most: more are dropped (a big explosion makes plenty). */
    static final int MAX_PARTICLES = 512;
    /** What Mario rides (server thread), and where he sits on it (planet space; null: nothing). */
    private static volatile Steer steer = new Steer(false, false, false, false, false);
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
        return primary.entities;
    }

    /** Whether a mob (or any creature of the shadow) stands in this cell of p: no block goes there. */
    public static boolean occupied(VoxelPlanet p, int cell) {
        Run r = p == null ? null : runs.get(p);
        Entities es = r == null ? null : r.entities;
        if (es == null || es.planet() != p) return false;
        ShadowMap m = es.map();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(m.x(cell), m.y(cell), m.z(cell), m.x(cell) + 1, m.y(cell) + 1, m.z(cell) + 1)
                .deflate(0.02);
        for (Entity e : es.list())
            if (!e.isRemoved() && e.getBoundingBox().intersects(box)) return true;
        return false;
    }

    /** Server thread: the cells live last tick (they leave a little farther than they come). */

    /** Where a cell of the running planet is in the shadow; null if p is not running. */
    public static BlockPos shadowPos(VoxelPlanet p, int cell) {
        Run r = p == null ? null : runs.get(p);
        ShadowMap m = r == null ? null : r.map;
        return m == null ? null : new BlockPos(m.x(cell), m.y(cell), m.z(cell));
    }

    /** The running planet's live blocks as of the last server tick (see {@link LiveBlocks}); null: none. */
    public static LiveBlocks.Snapshot live() {
        return primary.live;
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

    /** The player was hurt through Mario's stand-in: from where (planet space) and how (Layout.HURT_*). */
    public record Hurt(VoxelPlanet planet, org.joml.Vector3d from, int kind) {}

    private static final ConcurrentLinkedQueue<Hurt> hurts = new ConcurrentLinkedQueue<>();

    public static Hurt pollHurt() {
        return hurts.poll();
    }

    /** Server thread: Mario's stand-in passed a blow on to the player; the game makes Mario feel it. */
    static void hurt(net.minecraft.world.damagesource.DamageSource source, Entity proxy) {
        if (cur.map == null) return;
        Vec3 from = source.getSourcePosition();
        if (from == null) from = cur.proxy.position().add(cur.proxy.getLookAngle()); // no attacker: from in front
        double[] f = cur.map.frame(from.x, from.y, from.z);
        if (f == null) return;
        int kind = source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) ? dev.moui.galaxycraft.proto.Layout.HURT_EXPLOSION
                : source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) ? dev.moui.galaxycraft.proto.Layout.HURT_FIRE
                : dev.moui.galaxycraft.proto.Layout.HURT_HIT;
        hurts.add(new Hurt(cur.planet, new org.joml.Vector3d(f[0], f[1], f[2]), kind));
    }

    /** Mario's stand-in in the shadow, for tests: where, game mode; "none" if there is none. */
    public static String proxyState() {
        MarioProxy p = primary.proxy;
        return p == null ? "none" : p.blockPosition().toShortString() + " " + p.gameMode() + (p.isRemoved() ? " removed" : "");
    }

    /** Mario's seat on what he rides: planet space; null when he rides nothing. */
    public record Seat(VoxelPlanet planet, org.joml.Vector3d pos) {}

    public static Seat seat() {
        return primary.seat;
    }

    /** The movement keys while Mario rides (client tick): forward pushes, left and right steer a boat, sneak gets off. */
    public record Steer(boolean forward, boolean back, boolean left, boolean right, boolean sneak) {}

    public static void steer(Steer s) {
        steer = s;
    }

    /** Mario gets off what he rides. */
    public static void dismount() {
        ops.add(level -> cur.riding = null);
    }

    /**
     * The player uses a shadow entity (right click, by its id): a minecart or boat is ridden;
     * anything else gets the held item as from the player (shears, a bucket, wheat, a name tag,
     * trading).
     */
    public static void interact(int entityId, UUID player) {
        ops.add(level -> {
            Entity target = level.getEntity(entityId);
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
            if (target == null || p == null || !target.isAlive()) return;
            if (target instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart
                    || target instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat) {
                cur.riding = target;
                return;
            }
            p.interactOn(target, InteractionHand.MAIN_HAND, target.position());
        });
    }

    /** A projectile the player launched (an arrow, a snowball, a trident) while Mario is on the planet: it flies from him. */
    public static boolean catchProjectile(ServerLevel level, net.minecraft.world.entity.projectile.Projectile p) {
        if (isShadow(level) || !catchThrown || !(p.getOwner() instanceof ServerPlayer owner)) return false;
        // A rocket boosting the player's elytra pushes the player, where it is: not a shot from Mario.
        if (p instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity && owner.isFallFlying()) return false;
        ServerLevel shadow = level.getServer().getLevel(KEY);
        MarioProxy at = cur.proxy;
        if (shadow == null || at == null || at.isRemoved()) return false;
        Entity copy = p.getType().create(shadow, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        if (!(copy instanceof net.minecraft.world.entity.projectile.Projectile moved)) return false;
        moved.restoreFrom(p);
        moved.setOwner(at); // its hits are Mario's, from where he stands
        Vec3 look = at.getLookAngle();
        moved.snapTo(at.getX() + look.x * 0.5, at.getEyeY() - 0.1 + look.y * 0.5, at.getZ() + look.z * 0.5, at.getYRot(), at.getXRot());
        moved.setDeltaMovement(look.scale(p.getDeltaMovement().length()));
        shadow.addFreshEntity(moved);
        return true;
    }

    /** The player hits a shadow entity (by its id) as Mario, from where he stands there. */
    public static void attack(int entityId, UUID player) {
        attack(entityId, player, null);
    }

    /**
     * A guest's blow: the same, but the blow comes from the way the guest looks (planet space; null: from Mario's own
     * place), so the mob is pushed away from the guest, not from the host's Mario.
     */
    public static void attack(int entityId, UUID player, org.joml.Vector3d look) {
        ops.add(level -> {
            Entity target = level.getEntity(entityId);
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
            if (target == null || p == null || cur.proxy == null || cur.proxy.isRemoved()) return;
            // The blow is the player's: what is in hand (the same stack, so it wears out) and how charged it is.
            cur.proxy.setItemInHand(InteractionHand.MAIN_HAND, p.getMainHandItem());
            cur.proxy.getAttributes().assignAllValues(p.getAttributes()); // the weapon's damage and speed
            ((dev.moui.galaxycraft.mixin.LivingEntityAccessor) (Object) cur.proxy).galaxycraft$setAttackStrengthTicker(
                    ((dev.moui.galaxycraft.mixin.LivingEntityAccessor) p).galaxycraft$attackStrengthTicker());
            Vec3 to = target.position().subtract(cur.proxy.position());
            double[] f = look == null || cur.map == null ? null : cur.map.frame(target.getX(), target.getY(), target.getZ());
            if (f != null) { // the look in the shadow's directions there: cur.planet = x ax + y ay + z az
                org.joml.Vector3d d = new org.joml.Matrix3d(f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10], f[11]).invert()
                        .transform(new org.joml.Vector3d(look));
                if (Double.isFinite(d.x) && Double.isFinite(d.z) && d.x * d.x + d.z * d.z > 1e-9) to = new Vec3(d.x, 0, d.z);
            }
            cur.proxy.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
            cur.proxy.attack(target);
            p.resetAttackStrengthTicker();
            cur.proxy.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        });
    }
    /** Whether the shadow runs this planet (a guest's block use there can be done; elsewhere it passes). Any thread. */
    public static boolean runs(VoxelPlanet p) {
        return available && p != null && runs.containsKey(p);
    }

    /** Whether sp is the player Mario's stand-in follows (the host's own); a guest's has none yet. */
    private static boolean isHostPlayer(ServerPlayer sp) {
        MarioAt at = marioAt;
        return at != null && at.player().equals(sp.getUUID());
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
    /** A sign to edit, as the client's editor takes it: the shadow's sign, its texts, the side the player faces. */
    public record SignEdit(BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.SignText front,
            net.minecraft.world.level.block.entity.SignText back, net.minecraft.world.level.block.entity.SignTextSlot slot) {}

    /** Set by the client: opens Minecraft's sign editor on a copy of the shadow's sign (on the client thread). */
    public static volatile Consumer<SignEdit> signEditor;
    /** By player: the shadow's sign their editor is open for (server thread). */
    private static final java.util.Map<UUID, BlockPos> editing = new java.util.HashMap<>();

    /** A bed slept in (any bed, straw too): the player comes back to its cell's top after dying. */
    public record Bed(VoxelPlanet planet, int cell, UUID player) {}

    public static Bed pollBed() {
        return beds.poll();
    }

    public static Runnable pollClient() {
        return toClient.poll();
    }

    /** A guest's thrown item: lies on planet p from pos (planet space), moving at vel. */
    public static void throwOnto(VoxelPlanet p, org.joml.Vector3d pos, org.joml.Vector3d vel, ItemStack stack) {
        drops.add(new Drop(p, pos, vel, stack, THROWN_DELAY));
    }

    public static Drop pollDrop() {
        return drops.poll();
    }

    /** Whether items the player throws (Q, out of the inventory) land on the planet instead of in Minecraft. */
    public static void catchThrown(boolean on) {
        catchThrown = on;
    }

    /** A leaf the planet says no log holds: out, with the drops Minecraft gives a decayed leaf (saplings, sticks, apples). */
    public static void decay(VoxelPlanet p, int cell) {
        ops.add(level -> {
            if (p != cur.planet || cur.map == null) return;
            BlockPos pos = new BlockPos(cur.map.x(cell), cur.map.y(cell), cur.map.z(cell));
            mirrorNow(level, pos);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) return;
            Block.dropResources(state, level, pos);
            level.removeBlock(pos, false);
        });
    }

    /**
     * The player breaks cell, as ServerPlayerGameMode.destroyBlock does: the block's own breaking
     * (a door's other half, ice to water), the tool worn, and outside creative its drops.
     */
    public static void destroy(VoxelPlanet p, int cell, UUID player) {
        ops.add(level -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(player);
            if (p != cur.planet || cur.map == null || sp == null) return;
            BlockPos pos = new BlockPos(cur.map.x(cell), cur.map.y(cell), cur.map.z(cell));
            mirrorNow(level, pos);
            BlockState state = level.getBlockState(pos);
            // A station's core stays, whoever asks (it holds the station together).
            if (state.isAir() || state.is(dev.moui.galaxycraft.station.StationBlocks.CORE)
                    || !sp.getMainHandItem().canDestroyBlock(state, level, pos, sp)) return;
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

    /** The Toads of p to run in the shadow (replaces the list; any thread). */
    public static void wantToads(VoxelPlanet p, List<ShadowToads.Wanted> toads) {
        ops.add(level -> {
            if (p == cur.planet) ShadowToads.want(toads);
        });
    }

    /** A Toad being talked to stands still and looks at Mario (on), or goes on with its day (off). */
    public static void standToad(String id, boolean on) {
        ops.add(level -> ShadowToads.stand(id, on));
    }

    /** This planet, of this stage, is the one to run (null: none). */
    public static void attach(VoxelPlanet p, String stage) {
        ops.add(level -> {
            Run old = primary;
            if (old.planet != null && old.planet != p) {
                cur = old;
                release(level);
                runs.remove(old.planet);
            }
            ShadowToads.release(level);
            Run run = p == null ? new Run(null) : runs.get(p);
            if (run == null) {
                run = new Run(p);
                run.map = ShadowMap.of(p, stage);
                runs.put(p, run);
            }
            primary = run;
            cur = run;
        });
    }

    /** The columns to keep running (ChunkPos longs) and the cell Mario is in. */
    public static void follow(VoxelPlanet p, Set<Long> columns, int marioCell) {
        ops.add(level -> {
            if (p != cur.planet || cur.map == null) return;
            if (marioCell >= 0) cur.mario = new BlockPos(cur.map.x(marioCell), cur.map.y(marioCell), cur.map.z(marioCell));
            for (Long c : new HashSet<>(cur.forced))
                if (!columns.contains(c)) {
                    level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
                    cur.forced.remove(c);
                    cur.mirrored.remove(c);
                    if (c.equals(cur.mirroring)) cur.mirroring = null;
                }
            for (Long c : columns)
                if (cur.forced.add(c)) {
                    level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), true);
                    cur.toMirror.add(c);
                }
        });
    }

    /** The client changed a cell (its change number seq). */
    public static void set(VoxelPlanet p, int cell, int id, int seq) {
        ops.add(level -> {
            applied = seq;
            if (p != cur.planet || cur.map == null) return;
            BlockState s = state(id);
            write(level, new BlockPos(cur.map.x(cell), cur.map.y(cell), cur.map.z(cell)), s, Block.UPDATE_ALL);
            for (int[] h : cur.map.halos(cell)) write(level, new BlockPos(h[0], h[1], h[2]), s, Block.UPDATE_ALL);
        });
    }

    /**
     * A right click on cell's side face at hit (its model space), as Minecraft's
     * ServerPlayerGameMode.useItemOn does it, but leaving block items and buckets to the planet:
     * the block's use (a door opens, a lever flips, a chest opens its menu), else the held item's
     * use on it (flint and steel, bone meal, a hoe), with what is in hand. If neither does anything,
     * onPass runs on the client thread.
     */
    public static void use(VoxelPlanet p, int cell, int face, Vec3 hit, UUID player, InteractionHand hand, Runnable onPass) {
        use(p, cell, face, hit, player, hand, onPass, () -> {});
    }

    /** As above; onUsed runs on the client thread when the block or the item took the click (a guest is told so). */
    public static void use(VoxelPlanet p, int cell, int face, Vec3 hit, UUID player, InteractionHand hand, Runnable onPass,
            Runnable onUsed) {
        ops.add(level -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(player);
            if (p != cur.planet || cur.map == null || sp == null) {
                toClient.add(onPass);
                return;
            }
            BlockPos pos = new BlockPos(cur.map.x(cell), cur.map.y(cell), cur.map.z(cell));
            mirrorNow(level, pos);
            BlockHitResult h = new BlockHitResult(Vec3.atLowerCornerOf(pos).add(hit),
                    Direction.values()[CellSpace.DIRECTION_OF_SIDE[face]], pos, false);
            toClient.add(use(level, sp, hand, h).consumesAction() ? onUsed : onPass);
        });
    }

    private static InteractionResult use(ServerLevel level, ServerPlayer sp, InteractionHand hand, BlockHitResult h) {
        BlockState state = level.getBlockState(h.getBlockPos());
        if (state.getBlock() instanceof net.minecraft.world.level.block.AbstractBedBlock && !sp.isSecondaryUseActive())
            return sleep(level, sp, h.getBlockPos(), state);
        if (state.getBlock() instanceof net.minecraft.world.level.block.RespawnAnchorBlock && !sp.isSecondaryUseActive()
                && state.getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE) > 0
                && !(sp.getItemInHand(hand).is(net.minecraft.world.item.Items.GLOWSTONE)
                        && state.getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE) < 4))
            return anchor(sp, h.getBlockPos());
        ItemStack stack = sp.getItemInHand(hand);
        InteractionResult r = InteractionResult.PASS;
        boolean handsFull = !stack.isEmpty() || !sp.getOffhandItem().isEmpty();
        if (!(sp.isSecondaryUseActive() && handsFull)) {
            r = state.useItemOn(stack, level, sp, hand, h);
            if (r instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND
                    && state.getBlock() instanceof net.minecraft.world.level.block.SignBlock)
                return editSign(level, sp, h.getBlockPos(), state);
            if (r instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND)
                r = state.useWithoutItem(level, sp, h);
        }
        if (r.consumesAction() || stack.isEmpty() || stack.getItem() instanceof BlockItem
                || stack.getItem() instanceof BucketItem || sp.getCooldowns().isOnCooldown(stack))
            return r;
        if (stack.getItem() instanceof net.minecraft.world.item.BoatItem && isHostPlayer(sp) && cur.proxy != null && !cur.proxy.isRemoved()) {
            // A boat goes where Mario looks: placed by his stand-in, turned to the spot clicked.
            Vec3 to = h.getLocation().subtract(cur.proxy.getEyePosition());
            cur.proxy.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
            cur.proxy.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
            cur.proxy.setItemInHand(InteractionHand.MAIN_HAND, stack);
            r = stack.use(level, cur.proxy, InteractionHand.MAIN_HAND);
            cur.proxy.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return r;
        }
        int count = stack.getCount();
        r = stack.useOn(new UseOnContext(level, sp, hand, stack, h));
        if (sp.hasInfiniteMaterials()) stack.setCount(count);
        else if (r instanceof InteractionResult.Success s && s.heldItemTransformedTo() != null
                && s.heldItemTransformedTo() != stack)
            sp.setItemInHand(hand, s.heldItemTransformedTo());
        return r;
    }

    /**
     * A sign used with nothing to put on it (dye, glow ink and honeycomb go Minecraft's own way): its
     * editor opens on the player's screen, for the side Mario faces. Minecraft's would look for the
     * sign where the player is, not in the shadow; what is written comes back through
     * {@link #signEdited}.
     */
    private static InteractionResult editSign(ServerLevel level, ServerPlayer sp, BlockPos pos, BlockState state) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) || signEditor == null)
            return InteractionResult.PASS;
        if (!isHostPlayer(sp)) return InteractionResult.SUCCESS; // the editor opens on the host's screen: a guest's sign stays as it is for now
        if (sign.isWaxed()) {
            level.playSound(null, pos, sign.getSignInteractionFailedSoundEvent(), net.minecraft.sounds.SoundSource.BLOCKS);
            return InteractionResult.SUCCESS;
        }
        var slot = cur.proxy != null && !cur.proxy.isRemoved() ? sign.getSlotPlayerIsFacing(cur.proxy)
                : net.minecraft.world.level.block.entity.SignTextSlot.FRONT;
        openEditor(sp, pos, state, sign, slot);
        return InteractionResult.SUCCESS;
    }

    /** A sign the player just put down: its editor opens on the front, as Minecraft's sign item opens it. */
    public static void placedSign(VoxelPlanet p, int cell, UUID player) {
        ops.add(level -> {
            if (p != cur.planet || cur.map == null) return;
            BlockPos pos = new BlockPos(cur.map.x(cell), cur.map.y(cell), cur.map.z(cell));
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(player);
            if (sp != null && level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign && !sign.isWaxed())
                openEditor(sp, pos, level.getBlockState(pos), sign, net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
        });
    }

    private static void openEditor(ServerPlayer sp, BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.SignBlockEntity sign,
            net.minecraft.world.level.block.entity.SignTextSlot slot) {
        if (signEditor == null) return;
        editing.put(sp.getUUID(), pos);
        SignEdit edit = new SignEdit(pos, state, sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT),
                sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK), slot);
        Consumer<SignEdit> open = signEditor;
        toClient.add(() -> open.accept(edit));
    }

    /**
     * What the player wrote on a sign (the server's sign update, on the server thread): true if the
     * sign is the shadow's (written there, as Minecraft writes it: only by whom it was opened for,
     * never on a waxed one), false for one of the player's own level.
     */
    public static boolean signEdited(ServerPlayer sp, BlockPos pos, net.minecraft.world.level.block.entity.SignTextSlot slot,
            List<net.minecraft.server.network.FilteredText> lines) {
        if (server == null) return false;
        ServerLevel level = server.getLevel(KEY);
        if (level == null || level == sp.level() || !pos.equals(editing.get(sp.getUUID()))) return false;
        editing.remove(sp.getUUID());
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign))
            return true;
        // Minecraft lets only a player within reach in the sign's level keep editing it, and the
        // player is not in the shadow: the editor opened for them here is their leave.
        sign.setAllowedPlayerEditor(sp.getUUID());
        sign.updateSignText(sp, slot, lines);
        return true;
    }

    /**
     * A charged respawn anchor used: where the player comes back after dying, as a bed (the shadow
     * is no Nether, and Minecraft would blow it up). Charging it with glowstone stays Minecraft's.
     */
    private static InteractionResult anchor(ServerPlayer sp, BlockPos pos) {
        int cell = cur.map == null ? -1 : cur.map.cell(pos.getX(), pos.getY(), pos.getZ());
        if (cell >= 0) beds.add(new Bed(cur.planet, cell, sp.getUUID()));
        sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable("block.minecraft.set_spawn"), true);
        return InteractionResult.SUCCESS;
    }

    /**
     * A bed used, as Minecraft's own: where the player comes back after dying from now on, and at
     * night, with no monster about, the night is slept through. The player is not in the shadow
     * (Minecraft's BedBlock would find no bed where he is), so it is done here; the straw bed does
     * what every bed does.
     */
    private static InteractionResult sleep(ServerLevel level, ServerPlayer sp, BlockPos pos, BlockState state) {
        BlockPos head = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                == net.minecraft.world.level.block.state.properties.BedPart.HEAD ? pos
                : pos.relative(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING));
        int cell = cur.map == null ? -1 : cur.map.cell(head.getX(), head.getY(), head.getZ());
        if (cell >= 0) beds.add(new Bed(cur.planet, cell, sp.getUUID()));
        sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable("block.minecraft.set_spawn"), true);
        ServerLevel overworld = server.overworld();
        if (!overworld.isDarkOutside() && !overworld.isThundering()) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable("block.minecraft.bed.no_sleep"), true);
            return InteractionResult.SUCCESS;
        }
        var monsters = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                new net.minecraft.world.phys.AABB(head).inflate(8, 5, 8), m -> m.isPreventingPlayerRest(level, sp));
        if (!monsters.isEmpty()) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable("block.minecraft.bed.not_safe"), true);
            return InteractionResult.SUCCESS;
        }
        // As ServerLevel.tick once everyone sleeps: the clock to the morning, the rain gone.
        var rules = overworld.getGameRules();
        if ((Boolean) rules.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME))
            overworld.dimensionType().defaultClock().ifPresent(clock -> server.clockManager().moveToTimeMarker(clock,
                    net.minecraft.world.clock.ClockTimeMarkers.WAKE_UP_FROM_SLEEP));
        if ((Boolean) rules.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER) && overworld.isRaining())
            overworld.resetWeatherCycle();
        return InteractionResult.SUCCESS;
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
        cur = primary;
        for (Consumer<ServerLevel> op; (op = ops.poll()) != null; ) {
            cur = primary;
            op.accept(level);
        }
        // Monsters by the gamerules as they are now: Minecraft re-reads them only when difficulty
        // or spawn_monsters change, not spawn_mobs.
        level.setSpawnSettings(level.isSpawningMonsters());
        for (Run run : new ArrayList<>(runs.values())) {
            cur = run;
            boolean mine = run == primary;
            mirrorSome(level);
            flushEdges(level);
            moveProxy(level);
            if (mine) animate(level);
            if (mine)
                ShadowToads.tick(level, cur.planet, cur.map, cur.mirrored, cur.proxy == null || cur.proxy.isRemoved() ? null : cur.proxy.getEyePosition());
            cur.entities = cur.map == null ? null : new Entities(cur.planet, cur.map, collect(level));
            MarioAt at = marioAt;
            List<LiveBlocks.Live> blocks = cur.map == null ? List.of()
                    : LiveBlocks.collect(level, cur.planet, cur.map, cur.mirrored, !mine || at == null || at.planet() != cur.planet ? null : at.feet(), cur.liveCells);
            cur.liveCells.clear();
            for (LiveBlocks.Live l : blocks) cur.liveCells.add(l.cell());
            cur.live = cur.map == null ? null : new LiveBlocks.Snapshot(cur.planet, cur.map, List.copyOf(blocks));
        }
        cur = primary;
    }

    private static final net.minecraft.util.RandomSource ANIMATE = net.minecraft.util.RandomSource.create();

    /**
     * What a Minecraft client does around its player every tick (ClientLevel.animateTick): blocks
     * at random spots within 16 and 32 blocks of Mario make their ambient particles (a torch's
     * flame and smoke, a campfire's, falling leaves, portal swirls), here caught for the planet.
     * Fluids are the planet's (not in the shadow): theirs come from its cells (lava pops, drips).
     */
    private static void animate(ServerLevel level) {
        if (cur.map == null || cur.proxy == null || cur.proxy.isRemoved() || cur.proxy.isSpectator()) return;
        BlockPos m = cur.mario;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 667; i++)
            for (int r : new int[] {16, 32}) {
                pos.set(m.getX() + ANIMATE.nextInt(r) - ANIMATE.nextInt(r), m.getY() + ANIMATE.nextInt(r) - ANIMATE.nextInt(r),
                        m.getZ() + ANIMATE.nextInt(r) - ANIMATE.nextInt(r));
                if (!cur.mirrored.contains(ChunkPos.pack(pos)) || !level.isLoaded(pos)) continue;
                BlockState s = level.getBlockState(pos);
                try {
                    if (!s.isAir()) s.getBlock().animateTick(s, level, pos, ANIMATE);
                    int cell = cur.map.cell(pos.getX(), pos.getY(), pos.getZ());
                    if (cell >= 0 && cur.planet.fluid(cell) != dev.moui.galaxycraft.voxel.Blocks.NO_FLUID)
                        state(cur.planet.get(cell)).getFluidState().animateTick(level, pos.immutable(), ANIMATE);
                } catch (RuntimeException e) {
                    // Some block that wants a client level: no particles from it.
                }
            }
    }

    /** Mario's stand-in: made when there is a planet and a player, kept where Mario is. */
    private static void moveProxy(ServerLevel level) {
        MarioAt at = marioAt;
        ServerPlayer p = at == null || cur.map == null || at.planet() != cur.planet ? null : level.getServer().getPlayerList().getPlayer(at.player());
        int cell = p == null ? -1 : cur.map.grid.cellAt(at.feet());
        if (cell < 0) {
            if (cur.proxy != null && cur.proxy.gameMode() != net.minecraft.world.level.GameType.SPECTATOR) {
                cur.proxy.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                level.getChunkSource().move(cur.proxy); // no mobs spawn around a Mario who left the cur.planet
            }
            return;
        }
        if (cur.proxy == null || cur.proxy.isRemoved() || cur.proxy.level() != level) {
            cur.proxy = new MarioProxy(level);
            level.addNewPlayer(cur.proxy);
        }
        cur.proxy.follow(at.player());
        net.minecraft.world.level.GameType mode = p.isAlive() ? p.gameMode() : net.minecraft.world.level.GameType.SPECTATOR;
        if (cur.proxy.gameMode() != mode) cur.proxy.setGameMode(mode);
        org.joml.Vector3d m = CellSpace.local(cur.map.grid, cell, at.feet());
        org.joml.Vector3d d = CellSpace.direction(cur.map.grid, cell, at.look());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(d.y, -1, 1)));
        cur.proxy.snapTo(cur.map.x(cell) + m.x, cur.map.y(cell) + m.y, cur.map.z(cell) + m.z, yaw, pitch);
        cur.proxy.setYHeadRot(yaw);
        // Where the chunks around players count him (natural spawning: monsters near him, not too near).
        level.getChunkSource().move(cur.proxy);
        if (p.isAlive()) cur.proxy.setHealth(p.getHealth());
        // Mario's body: what he walks into is pushed, arrows lying there are picked up.
        for (Entity e : level.getEntities(cur.proxy, cur.proxy.getBoundingBox().inflate(0.2))) {
            if (e == cur.riding) continue;
            if (e.isPushable()) e.push(cur.proxy);
            if (e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) e.playerTouch(p);
        }
        ride(level, p);
    }

    /** Mario on a minecart or boat: pushed or paddled by the movement keys, off with sneak. */
    private static void ride(ServerLevel level, ServerPlayer p) {
        Steer s = steer;
        if (cur.riding != null && (cur.riding.isRemoved() || cur.riding.level() != level || s.sneak() || !p.isAlive())) cur.riding = null;
        if (cur.riding == null) {
            cur.seat = null;
            return;
        }
        Vec3 look = cur.proxy.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
        if (cur.riding instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat) {
            float yaw = cur.riding.getYRot() + (s.left() ? -3 : 0) + (s.right() ? 3 : 0);
            cur.riding.setYRot(yaw);
            double r = Math.toRadians(yaw);
            Vec3 front = new Vec3(-Math.sin(r), 0, Math.cos(r));
            if (s.forward()) cur.riding.push(front.scale(0.04));
            if (s.back()) cur.riding.push(front.scale(-0.005));
        } else {
            if (s.forward()) cur.riding.push(flat.scale(0.02));
            if (s.back()) cur.riding.push(flat.scale(-0.02));
        }
        Vec3 at = cur.riding.getPassengerRidingPosition(cur.proxy);
        double[] f = cur.map.frame(at.x, at.y, at.z);
        cur.seat = f == null ? null : new Seat(cur.planet, new org.joml.Vector3d(f[0], f[1], f[2]));
    }

    // ---- particles (server thread, from the mixins) ----

    /** One particle made in the shadow at (x, y, z) moving by (vx, vy, vz): to the planet's space. */
    public static void particle(net.minecraft.core.particles.ParticleOptions options, double x, double y, double z, double vx,
            double vy, double vz) {
        if (cur.map == null || particles.size() >= MAX_PARTICLES) return;
        double[] f = cur.map.frame(x, y, z);
        if (f == null) return;
        // The frame is about the shadow position given: the point itself is its origin.
        org.joml.Vector3d pos = new org.joml.Vector3d(f[0], f[1], f[2]);
        org.joml.Vector3d vel = new org.joml.Vector3d(f[3] * vx + f[6] * vy + f[9] * vz, f[4] * vx + f[7] * vy + f[10] * vz,
                f[5] * vx + f[8] * vy + f[11] * vz);
        particles.add(new Particle(cur.planet, options, pos, vel));
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

    /** Mobs that may despawn, by category, as of the last tick (server thread). */

    /**
     * The categories still under their cap on this planet: Minecraft's caps (per 17 × 17 chunks)
     * times the planet's columns near Mario over 289.
     */
    public static List<net.minecraft.world.entity.MobCategory> underCap(List<net.minecraft.world.entity.MobCategory> categories) {
        int columns = Math.max(1, cur.mirrored.size());
        List<net.minecraft.world.entity.MobCategory> out = new ArrayList<>(categories.size());
        for (var c : categories)
            if (cur.mobCounts[c.ordinal()] < Math.max(1, c.getMaxInstancesPerChunk() * columns / 289)) out.add(c);
        return out;
    }

    /** The planet's entities, those that walked off a face's edge put on the next face. */
    private static List<Entity> collect(ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        java.util.Arrays.fill(cur.mobCounts, 0);
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob mob && !mob.isPersistenceRequired() && !mob.requiresCustomPersistence() && !e.isRemoved())
                cur.mobCounts[e.getType().getCategory().ordinal()]++;
            // Dying mobs stay until Minecraft removes them: they fall over first.
            if (e instanceof Player || e instanceof net.minecraft.world.entity.item.ItemEntity
                    || e.isRemoved() || !cur.map.inStrip((int) Math.floor(e.getZ())))
                continue;
            if (!e.isPassenger() && e.isAlive()) wrap(level, e);
            if (out.size() < MAX_ENTITIES) out.add(e);
        }
        return List.copyOf(out);
    }

    private static void wrap(ServerLevel level, Entity e) {
        ShadowMap.Wrap w = cur.map.wrap(e.getX(), e.getY(), e.getZ());
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
        if (level != null)
            for (Run r : new ArrayList<>(runs.values())) {
                cur = r;
                release(level);
            }
        runs.clear();
        primary = new Run(null);
        cur = primary;
        available = false;
        server = null;
        ops.clear();
    }

    private static void release(ServerLevel level) {
        if (cur.proxy != null && !cur.proxy.isRemoved()) level.removePlayerImmediately(cur.proxy, Entity.RemovalReason.DISCARDED);
        cur.proxy = null;
        cur.riding = null;
        cur.seat = null;
        for (Long c : cur.forced) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
        cur.forced.clear();
        cur.mirrored.clear();
        cur.toMirror.clear();
        cur.mirroring = null;
        cur.edgeChanged.clear();
    }

    private static void mirrorNow(ServerLevel level, BlockPos pos) {
        long c = ChunkPos.pack(pos);
        if (cur.mirrored.contains(c) && !Long.valueOf(c).equals(cur.mirroring)) return; // copied in already (not half)
        if (cur.forced.add(c)) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), true);
        cur.toMirror.remove(c);
        mirror(level, c);
    }

    /** Copies columns in, a row at a time, for MIRROR_NANOS at most (a row at least). */
    private static void mirrorSome(ServerLevel level) {
        long until = System.nanoTime() + MIRROR_NANOS;
        do {
            if (cur.mirroring == null) {
                Long next = cur.toMirror.poll();
                if (next == null) return;
                if (cur.map == null || !cur.forced.contains(next)) continue;
                cur.mirroring = next;
                cur.mirrorX = 0;
                paintBiomes(level, next);
                cur.mirrored.add(next); // edits reach it from now on; the rows still to copy take what the cells are then
            }
            mirrorRow(level, cur.mirroring, cur.mirrorX++);
            if (cur.mirrorX == 16) {
                populate(level, cur.mirroring);
                cur.mirroring = null;
            }
        } while (System.nanoTime() - until < 0);
    }

    /** Copies the planet's cells (and halo copies) of a column in where they differ, all at once. */
    private static void mirror(ServerLevel level, long column) {
        if (cur.map == null || !cur.forced.contains(column)) return;
        cur.mirrored.add(column);
        paintBiomes(level, column);
        for (int x = 0; x < 16; x++) mirrorRow(level, column, x);
        populate(level, column);
        if (Long.valueOf(column).equals(cur.mirroring)) cur.mirroring = null;
    }

    /**
     * The column's biomes are the planet's there: Minecraft spawns each biome's mobs, makes snow
     * fall where it is cold, and so on. Halo positions take the biome of the cell they copy;
     * what is no planet's stays the void.
     */
    private static void paintBiomes(ServerLevel level, long column) {
        if (cur.map == null) return;
        var chunk = level.getChunk(ChunkPos.getX(column), ChunkPos.getZ(column));
        var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        java.util.Map<String, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> holders = new java.util.HashMap<>();
        boolean[] any = {false};
        chunk.fillBiomesFromNoise((qx, qy, qz) -> {
            int x = (qx << 2) + 2, z = (qz << 2) + 2;
            int cell = cur.map.cell(x, cur.map.y0, z);
            if (cell < 0) cell = cur.map.haloSource(x, cur.map.y0, z);
            var was = chunk.getNoiseBiome(qx, qy, qz);
            if (cell < 0) return was;
            String name = cur.planet.biome(cell);
            var h = holders.computeIfAbsent(name, n -> {
                Identifier id = Identifier.tryParse(n);
                return id == null ? null : registry.get(ResourceKey.create(Registries.BIOME, id)).map(r -> (net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>) r).orElse(null);
            });
            if (h == null) return was;
            any[0] |= !h.equals(was);
            return h;
        });
        if (any[0]) chunk.markUnsaved();
    }

    /**
     * A column copied in for the first time: the animals Minecraft puts in a new chunk, by its
     * biome (NaturalSpawner.spawnMobsForChunkGeneration). Its inhabited time marks it done.
     */
    private static void populate(ServerLevel level, long column) {
        if (cur.map == null || !SPAWN_ANIMALS) return;
        var chunk = level.getChunk(ChunkPos.getX(column), ChunkPos.getZ(column));
        if (chunk.getInhabitedTime() > 0) return;
        chunk.setInhabitedTime(1);
        ChunkPos pos = new ChunkPos(ChunkPos.getX(column), ChunkPos.getZ(column));
        BlockPos middle = new BlockPos(pos.getMiddleBlockX(), cur.map.y0 + cur.map.grid.layers - 1, pos.getMiddleBlockZ());
        if (cur.map.cell(middle.getX(), cur.map.y0, middle.getZ()) < 0) return;
        try {
            net.minecraft.world.level.NaturalSpawner.spawnMobsForChunkGeneration(level, middle, pos, level.getRandom());
        } catch (RuntimeException e) {
            GalaxyCraft.LOG.warn("Could not put animals in {}: {}", pos, e.toString());
        }
    }

    /** -Dgalaxycraft.animals=false: no animals in new columns (natural spawning stays Minecraft's gamerules). */
    static final boolean SPAWN_ANIMALS = !"false".equals(System.getProperty("galaxycraft.animals"));

    /** One row of a column: x = its 16 blocks along z, by the planet's layers. */
    private static void mirrorRow(ServerLevel level, long column, int row) {
        int x = (ChunkPos.getX(column) << 4) + row, z0 = ChunkPos.getZ(column) << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = z0; z < z0 + 16; z++)
            for (int y = cur.map.y0; y < cur.map.y0 + cur.map.grid.layers; y++) {
                int cell = cur.map.cell(x, y, z);
                if (cell < 0) cell = cur.map.haloSource(x, y, z);
                if (cell < 0) {
                    if (y == cur.map.y0 && cur.map.haloSource(x, y, z) < 0) break; // not this cur.planet's column
                    continue;
                }
                BlockState s = state(cur.planet.get(cell));
                if (level.getBlockState(pos.set(x, y, z)) != s) write(level, pos.immutable(), s, MIRROR_FLAGS);
            }
    }

    private static void write(ServerLevel level, BlockPos pos, BlockState s, int flags) {
        if (!cur.mirrored.contains(ChunkPos.pack(pos))) return;
        BlockPos was = writingPos;
        writingPos = pos;
        try {
            BlockState old = level.getBlockState(pos);
            level.setBlock(pos, s, flags);
            if (old != s) pois(level, pos, old, s);
        } finally {
            writingPos = was;
        }
    }

    /**
     * Beds and doors known to the villagers' AI: copying in skips Minecraft's own block-change
     * path, which is where a block's point of interest is registered.
     */
    private static void pois(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        var manager = level.getPoiManager();
        if (net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(old).isPresent()) manager.remove(pos);
        net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(now).ifPresent(type -> manager.add(pos, type));
    }

    /** Cells on a face's edge that changed here: their halo copies take their new state. */
    private static void flushEdges(ServerLevel level) {
        for (int n = 0; n < 4096 && !cur.edgeChanged.isEmpty(); n++) {
            int cell = cur.edgeChanged.poll();
            BlockState s = level.getBlockState(new BlockPos(cur.map.x(cell), cur.map.y(cell), cur.map.z(cell)));
            for (int[] h : cur.map.halos(cell)) write(level, new BlockPos(h[0], h[1], h[2]), s, Block.UPDATE_ALL);
        }
    }

    private static BlockState state(int id) {
        BlockState s = Block.stateById(id);
        if (s == null) return Blocks.AIR.defaultBlockState();
        // The planet decides when a leaf falls (voxel/LeafDecay): here none does by itself.
        if (s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT))
            return s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
        return s;
    }

    /** The shadow holds every leaf persistent: one the planet holds natural (not persistent) goes back natural. */
    private static BlockState naturalLeaf(int planetId, BlockState now) {
        var persistent = net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT;
        if (!(now.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) || !now.hasProperty(persistent)) return now;
        BlockState was = Block.stateById(planetId);
        if (was != null && was.getBlock() == now.getBlock() && was.hasProperty(persistent) && !was.getValue(persistent))
            return now.setValue(persistent, false);
        return now;
    }

    // ---- hooks (server thread, from the mixins) ----

    /**
     * The player stands hidden in a world of their own while Mario plays (tools/gxplay.sh's demo,
     * or -Dgalaxycraft.hiddenPlayer=true): monsters spawn on planets only.
     */
    public static boolean hidesPlayer() {
        return Boolean.getBoolean("galaxycraft.demo") || Boolean.getBoolean("galaxycraft.hiddenPlayer");
    }

    public static boolean isShadow(Level level) {
        return level.dimension() == KEY;
    }

    /** Whether Minecraft may change this position: a cell of the running planet, not halo, gaps or other planets. */
    public static boolean writable(BlockPos pos) {
        if (pos.equals(writingPos) || pos.getZ() <= SCRATCH_Z) return true;
        return cur.map != null && cur.map.cell(pos.getX(), pos.getY(), pos.getZ()) >= 0;
    }

    /**
     * Past this z the shadow is scratch ground, not a mirror: anything may be written there and
     * nothing goes back to a planet (generated planets grow their trees there, client/McVegetation).
     */
    public static final int SCRATCH_Z = -10_000_000;

    /** A block of the shadow changed: back to the planet, unless GalaxyCraft itself wrote it there. */
    public static void changed(BlockPos pos, BlockState now) {
        if (cur.map == null || pos.equals(writingPos)) return;
        int cell = cur.map.cell(pos.getX(), pos.getY(), pos.getZ());
        if (cell < 0) return;
        changes.add(new Change(cur.planet, cell, Block.getId(naturalLeaf(cur.planet.get(cell), now)), applied));
        if (cur.map.onEdge(cell)) cur.edgeChanged.add(cell);
    }

    /**
     * An item entity about to join a level: true if it becomes a planet drop instead (every one
     * dropped in the shadow; one thrown near a player while catchThrown).
     */
    public static boolean catchItem(ServerLevel level, net.minecraft.world.entity.item.ItemEntity e) {
        Vec3 v = e.getDeltaMovement();
        if (isShadow(level)) {
            if (cur.map == null) return true;
            int bx = (int) Math.floor(e.getX()), by = (int) Math.floor(e.getY()), bz = (int) Math.floor(e.getZ());
            int cell = cur.map.cell(bx, by, bz);
            if (cell >= 0) {
                double fx = e.getX() - bx, fy = e.getY() - by, fz = e.getZ() - bz;
                org.joml.Vector3d at = CellSpace.point(cur.map.grid, cell, fx, fy, fz);
                org.joml.Vector3d vel = CellSpace.point(cur.map.grid, cell, fx + v.x, fy + v.y, fz + v.z).sub(at);
                drops.add(new Drop(cur.planet, at, vel, e.getItem().copy(), BLOCK_DROP_DELAY));
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
        BlockPos m = primary.mario;
        return Math.sqrt(m.distToCenterSqr(x, y, z));
    }
}
