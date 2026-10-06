package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.client.mixin.BlockItemInvoker;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.voxel.Atlas;
import dev.moui.galaxycraft.voxel.BlockInfo;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.BoxModel;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetBiomes;
import dev.moui.galaxycraft.voxel.ModelQuad;
import dev.moui.galaxycraft.voxel.Placer;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Minecraft's blocks for the planets: every block state, by its id in Block.BLOCK_STATE_REGISTRY.
 * Models are Minecraft's baked ones (the first variant of each), tinted with the default colors
 * (no biome), textured from an atlas of every sprite the block models use (built here once, from
 * the resources, first animation frame). Blocks Minecraft draws apart (chests, signs, beds) are
 * boxes of their shape with their particle texture.
 *
 * Rules that look at neighbors (placement, updateShape, canSurvive) run in Minecraft's own code on
 * a copy of the cell's 3×3×3 neighborhood written for a moment into the client level, high above
 * the player (see {@link #withNeighborhood}): only the client sees it, and it is put back at once.
 */
public final class McBlocks implements Blocks {
    private static final int WHITE = 0xFFFFFF, WATER_TINT = 0x3F76E4;
    private static final Identifier WATER_STILL = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier LAVA_STILL = Identifier.withDefaultNamespace("block/lava_still");
    private static final Identifier WATER_FLOW = Identifier.withDefaultNamespace("block/water_flow");
    private static final Identifier LAVA_FLOW = Identifier.withDefaultNamespace("block/lava_flow");
    /** Minecraft's cracks on a block being broken, stage 0 to 9 (textures/block/destroy_stage_N.png). */
    private static final Identifier[] DESTROY_STAGES = new Identifier[Layout.CRACK_STAGES];

    static {
        for (int i = 0; i < DESTROY_STAGES.length; i++) DESTROY_STAGES[i] = Identifier.withDefaultNamespace("block/destroy_stage_" + i);
    }
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Where in the client level a neighborhood goes: this far under the top, above the player. */
    private static final int SCRATCH_BELOW_TOP = 4;
    /** Written there without updates, drops, sounds or block entity side effects. */
    private static final int SCRATCH_FLAGS = Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_ON_PLACE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private final Minecraft mc;
    private final BlockStateModelSet models;
    private final BlockColors colors;
    private final int count;
    private final BlockInfo[] infos;
    private final Map<Identifier, Integer> tileOf;
    private final List<int[]> tiles;
    private final int[] materialIds = new int[Material.values().length];
    private final Map<Block, Boolean> usable = new HashMap<>();
    final Atlas atlas;

    private McBlocks(Minecraft mc) {
        this.mc = mc;
        this.models = mc.getModelManager().getBlockStateModelSet();
        this.colors = mc.getBlockColors();
        this.count = Block.BLOCK_STATE_REGISTRY.size();
        if (count > 0xFFFF) throw new IllegalStateException(count + " block states do not fit a 16-bit cell");
        this.infos = new BlockInfo[count];
        // Every sprite any block's model uses, and the fluids' (drawn by GalaxyCraft, not by a model).
        LinkedHashMap<Identifier, Integer> sprites = new LinkedHashMap<>();
        sprites.put(WATER_STILL, 0);
        sprites.put(LAVA_STILL, 1);
        sprites.put(WATER_FLOW, 2);
        sprites.put(LAVA_FLOW, 3);
        for (int id = 0; id < count; id++) {
            BlockState state = Block.stateById(id);
            if (state == null) continue;
            BlockStateModel model = models.get(state);
            sprites.putIfAbsent(model.particleMaterial().sprite().contents().name(), sprites.size());
            for (BlockStateModelPart part : parts(model))
                for (int d = -1; d < DIRECTIONS.length; d++)
                    for (BakedQuad q : part.getQuads(d < 0 ? null : DIRECTIONS[d]))
                        sprites.putIfAbsent(q.materialInfo().sprite().contents().name(), sprites.size());
        }
        for (Identifier stage : DESTROY_STAGES) sprites.putIfAbsent(stage, sprites.size()); // the game draws them over a block
        this.tileOf = sprites;
        this.tiles = new ArrayList<>(sprites.size());
        for (Identifier name : sprites.keySet()) tiles.add(image(name));
        // The cracks' gaps are white at alpha 1/255, which Minecraft's 0.1 cutoff drops; RGB5A3 would
        // round it up to 1/7, past the game's, and the crack would whiten the block where it is not.
        for (Identifier stage : DESTROY_STAGES) {
            int[] argb = tiles.get(sprites.get(stage));
            for (int i = 0; i < argb.length; i++) if ((argb[i] >>> 24) < 26) argb[i] = 0;
        }
        List<Atlas.Anim> anims = animations(sprites.keySet());
        this.atlas = Atlas.of(tiles, anims);
        for (Material m : Material.values()) materialIds[m.ordinal()] = parse(m.state);
        GalaxyCraft.LOG.info("Planet blocks: {} states, {} sprites in a {}x{} atlas", count, tiles.size(), atlas.width(),
                atlas.height());
    }

    /** Where crack stage 0..9's tile is in the atlas: u0, v0, u1, v1 (0 to 1), as GxcCrack takes it. */
    float[] crackUv(int stage) {
        int t = tileOf.get(DESTROY_STAGES[Math.clamp(stage, 0, DESTROY_STAGES.length - 1)]);
        float cols = atlas.columns, rows = atlas.rows;
        int c = t % atlas.columns, r = t / atlas.columns;
        return new float[] {c / cols, r / rows, (c + 1) / cols, (r + 1) / rows};
    }

    /** Built once Minecraft's models are loaded (the first time a planet needs it). */
    public static McBlocks create(Minecraft mc) {
        return new McBlocks(mc);
    }

    private static List<BlockStateModelPart> parts(BlockStateModel model) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        return parts;
    }

    /** A sprite's first frame, 16×16 ARGB; a checkerboard if it has no file (Minecraft's missing texture). */
    private int[] image(Identifier sprite) {
        int n = Atlas.TILE;
        int[] argb = new int[n * n];
        Identifier file = sprite.withPath(p -> "textures/" + p + ".png");
        try (InputStream in = mc.getResourceManager().open(file); NativeImage img = NativeImage.read(in)) {
            int size = img.getWidth(); // frames stack downward: the first is the top square
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) argb[y * n + x] = img.getPixel(x * size / n, y * size / n);
        } catch (IOException e) {
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) argb[y * n + x] = (x < n / 2) == (y < n / 2) ? 0xFFF800F8 : 0xFF000000;
        }
        return argb;
    }

    /** Game frames (60 a second) per Minecraft tick (20 a second). */
    private static final int FRAMES_PER_TICK = 3;

    /**
     * The animated sprites (water, lava, fire, portals, sea lanterns...): every frame of each
     * becomes a tile after the others, and an animation shows them in its sprite's tile in the
     * order and at the pace its .mcmeta gives (no interpolation). As many as the atlas holds.
     */
    private List<Atlas.Anim> animations(java.util.Collection<Identifier> names) {
        List<Atlas.Anim> anims = new ArrayList<>();
        int tableBytes = 8, maxTiles = Atlas.MAX_COLUMNS * Atlas.MAX_COLUMNS;
        for (Identifier sprite : names) {
            if (anims.size() == Atlas.ANIM_MAX) break;
            Identifier file = sprite.withPath(p -> "textures/" + p + ".png");
            com.google.gson.JsonObject anim;
            try (var reader = mc.getResourceManager().openAsReader(file.withPath(p -> p + ".mcmeta"))) {
                var meta = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                if (!meta.has("animation")) continue;
                anim = meta.getAsJsonObject("animation");
            } catch (IOException | RuntimeException e) {
                continue; // no .mcmeta: not animated
            }
            try (InputStream in = mc.getResourceManager().open(file); NativeImage img = NativeImage.read(in)) {
                int fw = anim.has("width") ? anim.get("width").getAsInt() : img.getWidth();
                int fh = anim.has("height") ? anim.get("height").getAsInt() : fw;
                int count = img.getHeight() / fh, across = img.getWidth() / fw;
                count *= across;
                if (count < 2) continue;
                int frametime = anim.has("frametime") ? Math.max(1, anim.get("frametime").getAsInt()) : 1;
                // The order: its "frames" (an index, or an index and a time of its own), else 0..count-1.
                List<Integer> order = new ArrayList<>();
                if (anim.has("frames"))
                    for (var f : anim.getAsJsonArray("frames")) {
                        int index = f.isJsonObject() ? f.getAsJsonObject().get("index").getAsInt() : f.getAsInt();
                        int time = f.isJsonObject() && f.getAsJsonObject().has("time") ? f.getAsJsonObject().get("time").getAsInt() : frametime;
                        for (int r = 0; r < Math.max(1, Math.round((float) time / frametime)); r++) order.add(index);
                    }
                else for (int k = 0; k < count; k++) order.add(k);
                int bytes = 8 + 2 * order.size() + 2;
                if (tiles.size() + count > maxTiles || tableBytes + bytes > Atlas.ANIM_BYTES_MAX) break;
                int first = tiles.size();
                for (int k = 0; k < count; k++) tiles.add(frame(img, (k % across) * fw, (k / across) * fh, fw, fh));
                int[] frames = new int[order.size()];
                for (int i = 0; i < frames.length; i++) frames[i] = first + Math.clamp(order.get(i), 0, count - 1);
                anims.add(new Atlas.Anim(tileOf.get(sprite), frametime * FRAMES_PER_TICK, frames));
                tableBytes += bytes;
            } catch (IOException | RuntimeException e) {
                GalaxyCraft.LOG.warn("Animated sprite {} left still: {}", sprite, e.toString());
            }
        }
        GalaxyCraft.LOG.info("Planet blocks: {} animated sprites", anims.size());
        return anims;
    }

    /** One frame (x, y, w, h texels of img), 16×16 ARGB. */
    private static int[] frame(NativeImage img, int x0, int y0, int w, int h) {
        int n = Atlas.TILE;
        int[] argb = new int[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) argb[y * n + x] = img.getPixel(x0 + x * w / n, y0 + y * h / n);
        return argb;
    }

    /** A tile's 16×16 ARGB image (for what Steve holds). */
    int[] tileImage(int tile) {
        return tiles.get(tile);
    }

    public BlockState state(int id) {
        BlockState s = id >= 0 && id < count ? Block.stateById(id) : null;
        return s == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : s;
    }

    static int id(BlockState state) {
        return Block.getId(state);
    }

    @Override public BlockInfo info(int id) {
        if (id < 0 || id >= count) id = AIR;
        BlockInfo b = infos[id];
        if (b == null) infos[id] = b = compute(state(id));
        return b;
    }

    private BlockInfo compute(BlockState state) {
        EmptyBlockGetter none = EmptyBlockGetter.INSTANCE;
        if (state.getBlock() instanceof LiquidBlock) {
            boolean water = state.getFluidState().is(FluidTags.WATER);
            return new BlockInfo(water ? WATER : LAVA, state.getValue(LiquidBlock.LEVEL), false, false, false, false, false,
                    true, List.of(), List.of(), BlockInfo.FULL, tileOf.get(water ? WATER_STILL : LAVA_STILL),
                    water ? PlanetBiomes.tint(PlanetBiomes.WATER, WATER_TINT) : WHITE);
        }
        VoxelShape collision = shape(() -> state.getCollisionShape(none, BlockPos.ZERO));
        VoxelShape outline = shape(() -> state.getShape(none, BlockPos.ZERO));
        List<double[]> boxes = boxes(collision);
        double[] bounds = outline.isEmpty() ? BlockInfo.FULL : bounds(outline.bounds());
        BlockStateModel model = models.get(state);
        int particle = tileOf.getOrDefault(model.particleMaterial().sprite().contents().name(), 0);
        List<ModelQuad> quads = new ArrayList<>();
        if (state.getRenderShape() == RenderShape.MODEL) {
            for (BlockStateModelPart part : parts(model))
                for (int d = -1; d < DIRECTIONS.length; d++)
                    for (BakedQuad q : part.getQuads(d < 0 ? null : DIRECTIONS[d]))
                        quads.add(quad(state, q, d < 0 ? -1 : CellSpace.SIDE_OF_DIRECTION[d]));
        } else if (!state.isAir() && !outline.isEmpty()) {
            // Drawn by a block entity renderer in Minecraft: its shape, in its particle texture.
            for (AABB box : outline.toAabbs()) quads.addAll(BoxModel.box(bounds(box), particle, particle, particle, WHITE));
        }
        float hardness = state.getDestroySpeed(none, BlockPos.ZERO);
        return new BlockInfo(NO_FLUID, 0, !collision.isEmpty(), Block.isShapeFullBlock(collision), state.isSolidRender(),
                !state.isAir() && !outline.isEmpty(), !state.isAir() && hardness >= 0, state.canBeReplaced(),
                List.copyOf(quads), boxes, bounds, outline.isEmpty() ? List.of(BlockInfo.FULL) : boxes(outline), particle, WHITE);
    }

    private static VoxelShape shape(java.util.function.Supplier<VoxelShape> s) {
        try {
            return s.get();
        } catch (RuntimeException e) { // a shape that wants a real level: none
            return net.minecraft.world.phys.shapes.Shapes.empty();
        }
    }

    private static List<double[]> boxes(VoxelShape shape) {
        List<double[]> out = new ArrayList<>();
        for (AABB b : shape.toAabbs()) out.add(bounds(b));
        return List.copyOf(out);
    }

    private static double[] bounds(AABB b) {
        return new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
    }

    private ModelQuad quad(BlockState state, BakedQuad q, int cull) {
        TextureAtlasSprite sprite = q.materialInfo().sprite();
        float[] pos = new float[12], uv = new float[8];
        float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
        for (int v = 0; v < 4; v++) {
            var p = q.position(v);
            pos[3 * v] = p.x();
            pos[3 * v + 1] = p.y();
            pos[3 * v + 2] = p.z();
            long packed = q.packedUV(v);
            uv[2 * v] = du == 0 ? 0 : (UVPair.unpackU(packed) - sprite.getU0()) / du;
            uv[2 * v + 1] = dv == 0 ? 0 : (UVPair.unpackV(packed) - sprite.getV0()) / dv;
        }
        int tint = WHITE;
        if (q.materialInfo().isTinted()) {
            BlockTintSource source = colors.getTintSource(state, q.materialInfo().tintIndex());
            if (source != null) tint = PlanetBiomes.tint(TintProbe.kind(source, state), source.color(state));
        }
        return new ModelQuad(pos, uv, tileOf.getOrDefault(sprite.contents().name(), 0), tint, cull);
    }

    @Override public int lightBlock(int id) {
        return state(id).getLightDampening();
    }

    @Override public int lightEmission(int id) {
        return state(id).getLightEmission();
    }

    @Override public int flowTile(int fluid) {
        return fluid == WATER ? tileOf.get(WATER_FLOW) : fluid == LAVA ? tileOf.get(LAVA_FLOW) : -1;
    }

    /** Biomes by name, for their colors (looked up once each). */
    private final Map<String, java.util.Optional<net.minecraft.world.level.biome.Biome>> biomes = new java.util.concurrent.ConcurrentHashMap<>();

    @Override public int biomeColor(String biome, int kind, double x, double z) {
        var b = biomes.computeIfAbsent(biome, name -> {
            var level = mc.level;
            Identifier id = Identifier.tryParse(name);
            if (level == null || id == null) return java.util.Optional.empty();
            return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getOptional(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id));
        });
        if (b.isEmpty()) return -1;
        return switch (kind) {
            case PlanetBiomes.GRASS -> b.get().getGrassColor(x, z);
            case PlanetBiomes.FOLIAGE -> b.get().getFoliageColor();
            case PlanetBiomes.DRY_FOLIAGE -> b.get().getDryFoliageColor();
            case PlanetBiomes.WATER -> b.get().getWaterColor();
            default -> -1;
        } & 0xFFFFFF;
    }

    /**
     * Which biome color a block's tint asks Minecraft for: its tint source is run against a level
     * that answers every biome color with a marker and notes which one was asked. A source that
     * answers anything but that marker (a constant, redstone's power) is no biome color.
     */
    static final class TintProbe implements net.minecraft.client.renderer.block.BlockAndTintGetter {
        private static final int MARKER = 0x123457;
        private net.minecraft.world.level.ColorResolver asked;

        static int kind(BlockTintSource source, BlockState state) {
            TintProbe probe = new TintProbe();
            int c;
            try {
                c = source.colorInWorld(state, probe, BlockPos.ZERO);
            } catch (RuntimeException e) {
                return PlanetBiomes.FIXED;
            }
            if ((c & 0xFFFFFF) != MARKER || probe.asked == null) return PlanetBiomes.FIXED;
            var r = probe.asked;
            if (r == net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER) return PlanetBiomes.GRASS;
            if (r == net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER) return PlanetBiomes.FOLIAGE;
            if (r == net.minecraft.client.renderer.BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) return PlanetBiomes.DRY_FOLIAGE;
            if (r == net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER) return PlanetBiomes.WATER;
            return PlanetBiomes.FIXED;
        }

        @Override public net.minecraft.world.level.CardinalLighting cardinalLighting() {
            return net.minecraft.world.level.CardinalLighting.DEFAULT;
        }

        @Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
            return net.minecraft.world.level.lighting.LevelLightEngine.EMPTY;
        }

        @Override public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver color) {
            asked = color;
            return MARKER;
        }

        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }

        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }

        @Override public int getHeight() {
            return 0;
        }

        @Override public int getMinY() {
            return 0;
        }
    }

    /** Whether a right click can do something to the block itself (its class has a use): doors, levers, chests. */
    public boolean usable(int id) {
        return usable.computeIfAbsent(state(id).getBlock(), McBlocks::hasUse);
    }

    private static boolean hasUse(Block b) {
        for (Class<?> c = b.getClass(); c != Block.class; c = c.getSuperclass())
            for (java.lang.reflect.Method m : c.getDeclaredMethods())
                if (m.getName().equals("useWithoutItem") || m.getName().equals("useItemOn")) return true;
        return false;
    }

    @Override public int id(Material m) {
        return materialIds[m.ordinal()];
    }

    @Override public Material material(int id) {
        BlockState s = state(id);
        if (s.getBlock() == net.minecraft.world.level.block.Blocks.WATER) return Material.WATER;
        if (s.getBlock() == net.minecraft.world.level.block.Blocks.LAVA) return Material.LAVA;
        for (Material m : Material.values())
            if (materialIds[m.ordinal()] == id) return m;
        return null;
    }

    @Override public int fluidState(int fluid, int level) {
        Block b = fluid == WATER ? net.minecraft.world.level.block.Blocks.WATER : net.minecraft.world.level.block.Blocks.LAVA;
        return id(b.defaultBlockState().setValue(LiquidBlock.LEVEL, Math.max(0, Math.min(15, level))));
    }

    @Override public boolean faceVisible(int id, int neighbor, int side) {
        return Block.shouldRenderFace(state(id), state(neighbor), DIRECTIONS[CellSpace.DIRECTION_OF_SIDE[side]]);
    }

    @Override public boolean leaves(int id) {
        return state(id).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
    }

    @Override public String name(int id) {
        return BlockStateParser.serialize(state(id));
    }

    @Override public int parse(String name) {
        try {
            return id(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name, false).blockState());
        } catch (Exception e) {
            GalaxyCraft.LOG.warn("Unknown block {} on a saved planet: air instead", name);
            return AIR;
        }
    }

    @Override public int atlasColumns() {
        return atlas.columns;
    }

    @Override public int atlasRows() {
        return atlas.rows;
    }

    @Override public int updateShape(VoxelPlanet p, int cell) {
        Integer next = withNeighborhood(p, cell, (level, pos) -> {
            BlockState s = level.getBlockState(pos);
            RandomSource random = RandomSource.create(cell);
            for (Direction d : DIRECTIONS) {
                BlockPos n = pos.relative(d);
                s = s.updateShape(level, level, pos, d, n, level.getBlockState(n), random);
            }
            return id(s);
        });
        return next == null ? p.get(cell) : next;
    }

    @Override public boolean canSurvive(VoxelPlanet p, int cell, int id) {
        Boolean ok = withNeighborhood(p, cell, (level, pos) -> state(id).canSurvive(level, pos));
        return ok == null || ok;
    }

    /**
     * How the held block item goes onto a planet, by Minecraft's rules: the item's
     * getPlacementState (a torch, head or sign clicked onto a wall's side becomes its wall
     * version) with the player turned (for that call) to its look in the cell's axes, clicking the
     * face of the block cell is against, at the point clicked; what it then becomes next to its
     * neighbors (updateShape); refused where it cannot stay (canSurvive). Doors and tall plants
     * take the cell above too, beds the one at their head. Null for anything but a block item.
     */
    public Placer placer(ItemStack stack) {
        return placer(stack, InteractionHand.MAIN_HAND);
    }

    /** placer(stack), held in hand. */
    public Placer placer(ItemStack stack, InteractionHand hand) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        return (p, cell, face, hit, look) -> {
            BlockState placed = withNeighborhood(p, cell, (level, pos) -> {
                LocalPlayer player = mc.player;
                Direction clicked = DIRECTIONS[CellSpace.DIRECTION_OF_SIDE[face]];
                Vec3 at = new Vec3(pos.getX() + hit.x, pos.getY() + hit.y, pos.getZ() + hit.z);
                float yRot = player.getYRot(), xRot = player.getXRot(), yRotO = player.yRotO, xRotO = player.xRotO;
                float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
                float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -look.y))));
                BlockState s;
                try {
                    player.setYRot(yaw);
                    player.setXRot(pitch);
                    player.yRotO = yaw;
                    player.xRotO = pitch;
                    // Clicked on the block it goes against, as in Minecraft: that face's way is tried
                    // first (a wall torch on the wall), not only the look's (a torch on the floor below).
                    // Into short grass with air behind, the grass itself was clicked.
                    BlockPos against = pos.relative(clicked.getOpposite());
                    if (level.getBlockState(against).canBeReplaced()) against = pos;
                    s = ((BlockItemInvoker) item).galaxycraft$placementState(new BlockPlaceContext(player, hand, stack,
                            new BlockHitResult(at, clicked, against, false)));
                } finally {
                    player.setYRot(yRot);
                    player.setXRot(xRot);
                    player.yRotO = yRotO;
                    player.xRotO = xRotO;
                }
                if (s == null) return null;
                // Its other half first, where it goes, or its own updateShape takes it for broken.
                BlockPos other = otherHalf(s, pos);
                if (other != null) level.setBlock(other, otherHalfState(s), SCRATCH_FLAGS);
                RandomSource random = RandomSource.create(cell);
                for (Direction d : DIRECTIONS) {
                    BlockPos n = pos.relative(d);
                    s = s.updateShape(level, level, pos, d, n, level.getBlockState(n), random);
                }
                return s.isAir() || !s.canSurvive(level, pos) ? null : s;
            });
            if (placed == null) return List.of();
            List<int[]> out = new ArrayList<>();
            out.add(new int[] {cell, id(placed)});
            BlockPos other = otherHalf(placed, BlockPos.ZERO);
            if (other != null) {
                Direction d = Direction.getApproximateNearest(other.getX(), other.getY(), other.getZ());
                out.add(new int[] {p.grid.neighbor(cell, CellSpace.SIDE_OF_DIRECTION[d.ordinal()]), id(otherHalfState(placed))});
            }
            return out;
        };
    }

    /** Where the other half of a lower door or tall plant, or of a bed's foot, goes; null for other blocks. */
    private static BlockPos otherHalf(BlockState s, BlockPos pos) {
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER)
            return pos.above();
        if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT)
            return pos.relative(s.getValue(BlockStateProperties.HORIZONTAL_FACING));
        return null;
    }

    private static BlockState otherHalfState(BlockState s) {
        return s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                ? s.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
                : s.setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
    }

    /**
     * Runs fn on the client level with cell's 3×3×3 neighborhood written in at a scratch position
     * (the cell at its middle; x along the cell's j, y outward, z along its i; bedrock under the
     * planet's sealed center), and puts back what was there. Null if there is no level or player.
     */
    <T> T withNeighborhood(VoxelPlanet p, int cell, BiFunction<Level, BlockPos, T> fn) {
        Level level = mc.level;
        if (level == null || mc.player == null) return null;
        BlockPos at = new BlockPos(mc.player.getBlockX(), level.getMaxY() - SCRATCH_BELOW_TOP, mc.player.getBlockZ());
        int flags = SCRATCH_FLAGS;
        Map<BlockPos, BlockState> saved = new HashMap<>();
        try {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos pos = at.offset(dx, dy, dz);
                        saved.put(pos, level.getBlockState(pos));
                        level.setBlock(pos, stateAt(p, cell, dx, dy, dz), flags);
                    }
            return fn.apply(level, at);
        } finally {
            saved.forEach((pos, s) -> level.setBlock(pos, s, flags));
        }
    }

    /** The state of the cell dx, dy, dz model steps from cell (bedrock under the sealed center, air above). */
    BlockState stateAt(VoxelPlanet p, int cell, int dx, int dy, int dz) {
        int c = cell;
        int[][] steps = {{dy, CubeSphere.TOP, CubeSphere.BOTTOM}, {dx, CubeSphere.J_PLUS, CubeSphere.J_MINUS},
                {dz, CubeSphere.I_PLUS, CubeSphere.I_MINUS}};
        for (int[] s : steps)
            for (int k = 0; k < Math.abs(s[0]) && c >= 0; k++) {
                int next = p.grid.neighbor(c, s[0] > 0 ? s[1] : s[2]);
                if (next < 0 && s[1] == CubeSphere.TOP && s[0] < 0)
                    return net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState();
                c = next;
            }
        return c < 0 ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : state(p.get(c));
    }
}
