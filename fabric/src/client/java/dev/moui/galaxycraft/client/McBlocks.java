package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.voxel.Atlas;
import dev.moui.galaxycraft.voxel.BlockInfo;
import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.BoxModel;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.Material;
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
        this.tileOf = sprites;
        this.tiles = new ArrayList<>(sprites.size());
        for (Identifier name : sprites.keySet()) tiles.add(image(name));
        this.atlas = Atlas.of(tiles);
        for (Material m : Material.values()) materialIds[m.ordinal()] = parse(m.state);
        GalaxyCraft.LOG.info("Planet blocks: {} states, {} sprites in a {}x{} atlas", count, tiles.size(), atlas.width(),
                atlas.height());
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

    /** A tile's 16×16 ARGB image (for what Steve holds). */
    int[] tileImage(int tile) {
        return tiles.get(tile);
    }

    BlockState state(int id) {
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
                    water ? WATER_TINT : WHITE);
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
                List.copyOf(quads), boxes, bounds, particle, WHITE);
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
            if (source != null) tint = source.color(state) & 0xFFFFFF;
        }
        return new ModelQuad(pos, uv, tileOf.getOrDefault(sprite.contents().name(), 0), tint, cull);
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
     * How the held block item goes onto a planet, by Minecraft's rules: getStateForPlacement with
     * the player turned (for that call) to its look in the cell's axes, clicking the cell's face
     * and point; what it then becomes next to its neighbors (updateShape); refused where it cannot
     * stay (canSurvive). Doors and tall plants take the cell above too, beds the one at their head.
     * Null for anything but a block item.
     */
    public Placer placer(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        Block block = item.getBlock();
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
                    s = block.getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack,
                            new BlockHitResult(at, clicked, pos, false)));
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
