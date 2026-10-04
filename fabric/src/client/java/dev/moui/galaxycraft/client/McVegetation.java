package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.gen.BiomeSurface;
import dev.moui.galaxycraft.voxel.gen.Vegetation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * What grows on each biome, grown by Minecraft itself: on the integrated server, in a corner of the
 * shadow dimension far from what it mirrors, a flat floor of the biome's top block gets the biome's
 * vegetal decoration (its trees, flowers, grass; the biome check left out, the floor's biome being
 * the void), and what grew is kept as {@link Vegetation.Thing}s and cleared away. A few chunks per
 * biome, once per game: the planet's seed picks among them.
 */
final class McVegetation implements Vegetation.Library {
    /** Chunks grown per biome. */
    static final int SAMPLES = 4;
    private static final int FLOOR_Y = 40, HEIGHT = 48, MARGIN = 8;
    /** Far from the shadow's mirror (which is at z ≥ ShadowMap.Z_BASE): its scratch ground. */
    private static final int X0 = 0, Z0 = -20_000_000;
    private final MinecraftServer server;
    private final Map<String, List<Vegetation.Patch>> grown = new ConcurrentHashMap<>();

    McVegetation(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public List<Vegetation.Patch> patches(String biome) {
        List<Vegetation.Patch> p = grown.get(biome);
        if (p != null) return p;
        p = server.isSameThread() ? grow(biome) : server.submit(() -> grow(biome)).join();
        grown.put(biome, p);
        return p;
    }

    private List<Vegetation.Patch> grow(String biome) {
        ServerLevel level = server.getLevel(ShadowWorld.KEY);
        Holder<Biome> holder = server.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, Identifier.parse(biome))).orElse(null);
        if (level == null || holder == null) return List.of();
        List<HolderSet<PlacedFeature>> steps = holder.value().getGenerationSettings().features();
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (steps.size() <= step) return List.of();
        List<PlacedFeature> features = new ArrayList<>();
        for (Holder<PlacedFeature> f : steps.get(step))
            features.add(new PlacedFeature(f.value().feature(),
                    f.value().placement().stream().filter(m -> !(m instanceof BiomeFilter)).toList()));
        BiomeSurface.Palette palette = BiomeSurface.of(biome);
        BlockState top = state(palette.top()), filler = state(palette.filler());
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        List<Vegetation.Patch> out = new ArrayList<>();
        for (int s = 0; s < SAMPLES; s++) {
            int x0 = X0 + s * 64, z0 = Z0;
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (int x = -MARGIN; x < 16 + MARGIN; x++)
                for (int z = -MARGIN; z < 16 + MARGIN; z++) {
                    level.setBlock(at.set(x0 + x, FLOOR_Y, z0 + z), top, Block.UPDATE_CLIENTS);
                    for (int y = FLOOR_Y - 3; y < FLOOR_Y; y++) level.setBlock(at.set(x0 + x, y, z0 + z), filler, Block.UPDATE_CLIENTS);
                }
            RandomSource random = RandomSource.create(biome.hashCode() * 31L + s);
            for (PlacedFeature f : features) {
                try {
                    f.place(level, generator, random, new BlockPos(x0, 0, z0));
                } catch (RuntimeException e) {
                    GalaxyCraft.LOG.debug("{} on {}: {}", f, biome, e.toString());
                }
            }
            out.add(new Vegetation.Patch(things(level, x0, z0)));
            for (int x = -MARGIN; x < 16 + MARGIN; x++)
                for (int z = -MARGIN; z < 16 + MARGIN; z++)
                    for (int y = FLOOR_Y - 3; y <= FLOOR_Y + HEIGHT; y++)
                        level.setBlock(at.set(x0 + x, y, z0 + z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        int count = out.stream().mapToInt(p -> p.things().size()).sum();
        GalaxyCraft.LOG.info("Grew {} things for {} in {} chunks", count, biome, SAMPLES);
        return List.copyOf(out);
    }

    /**
     * What grew above the floor, as things: each block on the floor (a trunk, a flower, a tuft) is
     * one, and every other block goes with the nearest of them it touches (sides or corners), so
     * trees whose crowns meet stay apart. Things standing outside the chunk belong to its neighbors.
     */
    private static List<Vegetation.Thing> things(ServerLevel level, int x0, int z0) {
        Map<Long, BlockState> grown = new HashMap<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = -MARGIN; x < 16 + MARGIN; x++)
            for (int z = -MARGIN; z < 16 + MARGIN; z++)
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + HEIGHT; y++) {
                    BlockState s = level.getBlockState(at.set(x0 + x, y, z0 + z));
                    if (!s.isAir()) grown.put(BlockPos.asLong(x, y, z), s);
                }
        // Grown out from every base at once: each block is its nearest base's.
        Map<Long, Long> baseOf = new HashMap<>();
        ArrayDeque<Long> todo = new ArrayDeque<>();
        for (long q : grown.keySet())
            if (BlockPos.getY(q) == FLOOR_Y + 1) {
                baseOf.put(q, q);
                todo.add(q);
            }
        while (!todo.isEmpty()) {
            long q = todo.poll();
            int x = BlockPos.getX(q), y = BlockPos.getY(q), z = BlockPos.getZ(q);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        long r = BlockPos.asLong(x + dx, y + dy, z + dz);
                        if (!grown.containsKey(r) || baseOf.containsKey(r)) continue;
                        baseOf.put(r, baseOf.get(q));
                        todo.add(r);
                    }
        }
        Map<Long, List<Long>> parts = new HashMap<>();
        for (Map.Entry<Long, Long> e : baseOf.entrySet()) parts.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        List<Vegetation.Thing> things = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : parts.entrySet()) {
            int bx = BlockPos.getX(e.getKey()), bz = BlockPos.getZ(e.getKey());
            if (bx < 0 || bx >= 16 || bz < 0 || bz >= 16) continue;
            List<Long> part = e.getValue();
            int[] dx = new int[part.size()], dy = new int[part.size()], dz = new int[part.size()];
            String[] blocks = new String[part.size()];
            for (int i = 0; i < part.size(); i++) {
                long q = part.get(i);
                dx[i] = BlockPos.getX(q) - bx;
                dy[i] = BlockPos.getY(q) - FLOOR_Y;
                dz[i] = BlockPos.getZ(q) - bz;
                BlockState s = grown.get(q);
                // Leaves stay: on the planet only the blocks near Mario are mirrored, and leaves
                // whose log is past that edge would decay.
                if (s.hasProperty(BlockStateProperties.PERSISTENT)) s = s.setValue(BlockStateProperties.PERSISTENT, true);
                blocks[i] = BlockStateParser.serialize(s);
            }
            things.add(new Vegetation.Thing(bx, bz, dx, dy, dz, blocks));
        }
        // In a fixed order: the same seed plants the same planet.
        things.sort(java.util.Comparator.comparingInt((Vegetation.Thing t) -> t.ax()).thenComparingInt(Vegetation.Thing::az));
        return things;
    }

    private static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception e) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
    }
}
