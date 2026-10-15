package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.LiveCodec;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.shadow.LiveBlocks;
import dev.moui.galaxycraft.shadow.ShadowMap;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The host shadow's live blocks (sign text, banners, heads, shelves, campfire items, pot sherds, open lids), for the
 * guests. The host (every few ticks) tells each guest on a planet only what changed since it last told it
 * ({@link LiveCodec}); the guest builds plain block entities from that (never added to a level: only for their
 * renderers) and EntityClient draws them where their cells are, as it does the host's own. Client thread only.
 */
public final class MpLive {
    /** The host looks every this many client ticks (the shadow makes a fresh list every 4). */
    private static final int EVERY = 4;

    /** A live block on the guest: what the host told, and the block entity built from it for the cell's block now. */
    private static final class Block {
        LiveCodec.Entry entry;
        BlockEntity be;
        int builtFor = -1; // the planet's block id the entity was built for
        boolean fresh; // the entry changed since the entity was built
    }

    private static final boolean TRACE = System.getenv("GXC_MP_TRACE") != null;

    // ---- host

    /** Per guest, per planet (catalog index): the cells it was told and a digest of what. */
    private static final Map<UUID, Map<Integer, Map<Integer, Integer>>> told = new HashMap<>();

    private static int digest(LiveCodec.Entry e) {
        return ((Arrays.hashCode(e.data()) * 31 + e.type()) * 31 + e.lid()) * 31 + e.open();
    }

    /** Host, every client tick: tells the guests that hold the planet what changed among the live blocks near them. */
    static void hostTick(int now, VoxelPlanet planet, int index, Collection<UUID> holders) {
        if (now % EVERY != 2) {
            forgetLeavers(index, holders);
            return;
        }
        for (var m : messages(planet, index, holders).entrySet())
            for (byte[] body : m.getValue()) MpServer.live(m.getKey(), body);
    }

    private static void forgetLeavers(int index, Collection<UUID> holders) {
        for (var g : told.entrySet()) // a guest that left the planet is told everything again when it comes back
            if (!holders.contains(g.getKey())) g.getValue().remove(index);
    }

    /** What each of the holders is told now (nothing: no entry), and remembered as told. Public for the probe. */
    public static Map<UUID, List<byte[]>> messages(VoxelPlanet planet, int index, Collection<UUID> holders) {
        Map<UUID, List<byte[]>> out = new HashMap<>();
        forgetLeavers(index, holders);
        if (holders.isEmpty()) return out;
        Map<Integer, LiveCodec.Entry> wire = new HashMap<>();
        for (LiveCodec.Entry e : ShadowWorld.liveWireOf(planet)) wire.put(e.cell(), e);
        for (UUID guest : holders) {
            Map<Integer, Integer> known = told.computeIfAbsent(guest, g -> new HashMap<>()).get(index);
            boolean full = known == null;
            if (full) {
                known = new HashMap<>();
                told.get(guest).put(index, known);
            }
            List<LiveCodec.Entry> ups = new ArrayList<>();
            for (LiveCodec.Entry e : wire.values()) {
                int d = digest(e);
                Integer was = known.put(e.cell(), d);
                if (was == null || was != d) ups.add(e);
            }
            List<Integer> gone = new ArrayList<>();
            for (var it = known.keySet().iterator(); it.hasNext(); ) {
                int cell = it.next();
                if (wire.containsKey(cell)) continue;
                gone.add(cell);
                it.remove();
            }
            if (!full && ups.isEmpty() && gone.isEmpty()) continue;
            if (TRACE) GalaxyCraft.LOG.info("[mp live] host: planet {} to {}: {} changed, {} gone{}", index, guest, ups.size(), gone.size(), full ? " (all)" : "");
            List<byte[]> bodies = new ArrayList<>();
            int u = 0, r = 0;
            boolean first = true;
            while (first || u < ups.size() || r < gone.size()) {
                int nu = Math.min(LiveCodec.MAX_ENTRIES, ups.size() - u), nr = Math.min(LiveCodec.MAX_REMOVED, gone.size() - r);
                int[] removed = new int[nr];
                for (int i = 0; i < nr; i++) removed[i] = gone.get(r + i);
                bodies.add(LiveCodec.encode(index, full && first, ups.subList(u, u + nu), removed));
                u += nu;
                r += nr;
                first = false;
            }
            out.put(guest, bodies);
        }
        return out;
    }

    // ---- guest

    private static final Map<Integer, Map<Integer, Block>> planets = new HashMap<>();
    private static final Map<VoxelPlanet, ShadowMap> maps = new java.util.WeakHashMap<>();

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Live.TYPE,
                (m, ctx) -> ctx.client().execute(() -> receive(m.data())));
    }

    public static void reset() {
        planets.clear();
        maps.clear();
        told.clear();
    }

    private static void receive(byte[] bytes) {
        receive(bytes, false);
    }

    /** A body from the host (force: the probe plays the guest without a connection). */
    public static void receive(byte[] bytes, boolean force) {
        LiveCodec.Body b = LiveCodec.decode(bytes);
        if (b == null || !force && !MpUse.guest()) return;
        Map<Integer, Block> on = planets.computeIfAbsent(b.planet(), k -> new HashMap<>());
        if (b.full()) on.clear();
        for (int cell : b.removed()) on.remove(cell);
        for (LiveCodec.Entry e : b.upserts()) {
            Block old = on.get(e.cell());
            if (old != null && old.entry.type() == e.type() && Arrays.equals(old.entry.data(), e.data())) { // a lid moved
                old.entry = e;
                applyLid(old);
                continue;
            }
            Block nb = new Block();
            nb.entry = e;
            on.put(e.cell(), nb);
        }
        if (TRACE) GalaxyCraft.LOG.info("[mp live] guest: planet {}: {} changed, {} gone{}, {} live", b.planet(), b.upserts().size(), b.removed().length,
                b.full() ? " (all)" : "", on.size());
    }

    /** Every client tick: the lids glide (as Minecraft's do on a client). */
    public static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Map<Integer, Block> on : planets.values())
            for (Block b : on.values()) {
                if (b.be instanceof ChestBlockEntity c) ChestBlockEntity.lidAnimateTick(mc.level, c.getBlockPos(), c.getBlockState(), c);
                else if (b.be instanceof EnderChestBlockEntity c)
                    EnderChestBlockEntity.lidAnimateTick(mc.level, c.getBlockPos(), c.getBlockState(), c);
            }
    }

    /** Guest: the live blocks of planet p (catalog index), their entities built for the blocks that stand there now. */
    public static LiveBlocks.Snapshot snapshot(int index, VoxelPlanet p, McBlocks blocks) {
        Map<Integer, Block> on = planets.get(index);
        var mc = Minecraft.getInstance();
        if (on == null || on.isEmpty() || blocks == null || mc.level == null) return null;
        ShadowMap map = maps.computeIfAbsent(p, q -> ShadowMap.of(q, "live"));
        List<LiveBlocks.Live> out = new ArrayList<>(on.size());
        for (var en : on.entrySet()) {
            Block b = en.getValue();
            int cell = en.getKey(), id = p.get(cell);
            if (b.be == null || b.builtFor != id) build(b, cell, id, map, blocks, mc);
            if (b.be != null) out.add(new LiveBlocks.Live(cell, b.be));
        }
        return new LiveBlocks.Snapshot(p, map, out);
    }

    private static void build(Block b, int cell, int id, ShadowMap map, McBlocks blocks, Minecraft mc) {
        b.builtFor = id;
        b.be = null;
        b.fresh = false;
        try {
            BlockState state = blocks.state(id);
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.byId(b.entry.type());
            if (type == null || !type.isValid(state)) return; // the cell holds another block now (or the host's edit has not come yet)
            BlockPos pos = new BlockPos(map.x(cell), map.y(cell), map.z(cell));
            BlockEntity be;
            if (b.entry.data().length > 0) {
                CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(b.entry.data())));
                be = BlockEntity.loadStatic(pos, state, tag, mc.level.registryAccess());
            } else {
                be = type.create(pos, state);
            }
            if (be == null || be.getType() != type) return;
            be.setLevel(mc.level);
            b.be = be;
            applyLid(b);
        } catch (java.io.IOException | RuntimeException ex) {
            GalaxyCraft.LOG.debug("[GalaxyCraft mp] cannot build a live block: {}", ex.toString());
        }
    }

    // ---- lids

    private static Field lidOf(Class<?> c) {
        try {
            Field f = c.getDeclaredField("chestLidController");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private static final Field CHEST_LID = lidOf(ChestBlockEntity.class), ENDER_LID = lidOf(EnderChestBlockEntity.class);

    private static void put(Object o, String field, Object value) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    /** The lid goes where the host's is: a new block starts there, a moving one is told which way to go. */
    private static void applyLid(Block b) {
        if (b.be == null || b.entry.lid() == LiveCodec.LID_NONE) return;
        boolean opening = b.entry.lid() == LiveCodec.LID_OPENING;
        float open = b.entry.open() / 255f;
        try {
            if (b.be instanceof ChestBlockEntity || b.be instanceof EnderChestBlockEntity) {
                b.be.triggerEvent(1, opening ? 1 : 0);
                if (b.fresh) return;
                b.fresh = true; // first time: it starts where the host's lid is, not shut
                Object lid = (b.be instanceof ChestBlockEntity ? CHEST_LID : ENDER_LID).get(b.be);
                put(lid, "openness", open);
                put(lid, "oOpenness", open);
            } else if (b.be instanceof ShulkerBoxBlockEntity s) {
                put(s, "progressOld", s.getProgress(1f));
                put(s, "progress", open);
                put(s, "animationStatus", opening ? (open >= 1f ? ShulkerBoxBlockEntity.AnimationStatus.OPENED : ShulkerBoxBlockEntity.AnimationStatus.OPENING)
                        : ShulkerBoxBlockEntity.AnimationStatus.CLOSING);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            GalaxyCraft.LOG.debug("[GalaxyCraft mp] cannot move a lid: {}", ex.toString());
        }
    }
}
