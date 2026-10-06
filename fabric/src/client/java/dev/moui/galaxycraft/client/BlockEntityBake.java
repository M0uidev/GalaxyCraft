package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.client.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * What a block's own block entity renderer draws for it (a chest, a bed, a sign's board), as
 * faces in the block's space: its models' cubes with their texture (a sprite of one of Minecraft's
 * sheets, or an entity texture) and the texture's pixels each corner samples, and block-model
 * quads (a bell's frame) with their block sprite. Drawn once, at rest: a block entity made for the
 * state at the origin, no level data (no sign text, banner patterns or items).
 */
final class BlockEntityBake implements SubmitNodeCollector {
    /**
     * One face: four corners (x, y, z in blocks, the face's front counter-clockwise) and either a
     * texture with its size and the pixels each corner samples, or a block sprite with 0..1
     * coordinates in it; tint 0xRRGGBB (white for none).
     */
    record Face(float[] pos, float[] uv, Identifier texture, int width, int height, Identifier sprite, int tint) {}

    private final List<Face> out = new ArrayList<>();
    private final java.util.function.Function<Identifier, int[]> sizeOf;

    private BlockEntityBake(java.util.function.Function<Identifier, int[]> sizeOf) {
        this.sizeOf = sizeOf;
    }

    /**
     * The faces state's renderer draws; empty if its block has no renderer or the renderer cannot
     * draw without a level of its own. sizeOf gives a texture file's width and height (null if it
     * cannot be read).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<Face> bake(Minecraft mc, BlockState state, java.util.function.Function<Identifier, int[]> sizeOf) {
        if (!(state.getBlock() instanceof EntityBlock eb)) return List.of();
        BlockEntityBake c = new BlockEntityBake(sizeOf);
        try {
            BlockEntity be = eb.newBlockEntity(BlockPos.ZERO, state);
            if (be == null) return List.of();
            if (mc.level != null) be.setLevel(mc.level);
            BlockEntityRenderer r = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            if (r == null) return List.of();
            BlockEntityRenderState s = r.createRenderState();
            r.extractRenderState(be, s, 0f, Vec3.ZERO, null);
            r.submit(s, new PoseStack(), c, new CameraRenderState());
        } catch (RuntimeException e) {
            GalaxyCraft.LOG.debug("No baked look for {}: {}", state, e.toString());
            return List.of();
        }
        return c.out;
    }

    // ---- models: their cubes ----

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay,
            int color, UvMapping uv, int outline) {
        Identifier texture = uv instanceof TextureAtlasSprite s
                ? s.contents().name().withPath(p -> "textures/" + p + ".png")
                : EntityClient.renderTypeTexture(renderType);
        if (texture == null) return;
        int[] size = sizeOf.apply(texture);
        if (size == null) return;
        if (!(state instanceof Unit)) ((Model) model).setupAnim(state);
        PoseStack ps = new PoseStack();
        ps.last().pose().set(poseStack.last().pose());
        walk(model.root(), ps, texture, size, color & 0xFFFFFF);
    }

    /** As ModelPart.render: each visible cube's faces, where the pose puts them. */
    private void walk(ModelPart part, PoseStack ps, Identifier texture, int[] size, int tint) {
        ModelPartAccessor a = (ModelPartAccessor) (Object) part;
        if (!part.visible) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw) {
            Matrix4f m = ps.last().pose();
            boolean mirrored = m.determinant3x3() < 0;
            for (ModelPart.Cube cube : a.galaxycraft$cubes())
                for (ModelPart.Polygon poly : cube.polygons) {
                    if (poly.vertices().length != 4) continue;
                    float[] pos = new float[12], uvPx = new float[8];
                    for (int k = 0; k < 4; k++) {
                        // A mirroring pose turns the face's back to the front: its corners go the other way.
                        ModelPart.Vertex v = poly.vertices()[mirrored ? 3 - k : k];
                        Vector3f p = m.transformPosition(v.x() / 16f, v.y() / 16f, v.z() / 16f, new Vector3f());
                        pos[3 * k] = p.x;
                        pos[3 * k + 1] = p.y;
                        pos[3 * k + 2] = p.z;
                        uvPx[2 * k] = v.u() * size[0];
                        uvPx[2 * k + 1] = v.v() * size[1];
                    }
                    out.add(new Face(pos, uvPx, texture, size[0], size[1], null, tint));
                }
        }
        for (ModelPart child : a.galaxycraft$children().values()) walk(child, ps, texture, size, tint);
        ps.popPose();
    }

    // ---- block models (a bell's frame, a lectern's book stand) ----

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tints, int light,
            int overlay, int outline) {
        Matrix4f m = new Matrix4f(poseStack.last().pose());
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = new ArrayList<>(part.getQuads(null));
            for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
            for (BakedQuad q : quads) {
                TextureAtlasSprite sprite = q.materialInfo().sprite();
                float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
                float[] pos = new float[12], uv = new float[8];
                for (int k = 0; k < 4; k++) {
                    var p = m.transformPosition(q.position(k).x(), q.position(k).y(), q.position(k).z(), new Vector3f());
                    pos[3 * k] = p.x;
                    pos[3 * k + 1] = p.y;
                    pos[3 * k + 2] = p.z;
                    long packed = q.packedUV(k);
                    uv[2 * k] = du == 0 ? 0 : (net.minecraft.client.model.geom.builders.UVPair.unpackU(packed) - sprite.getU0()) / du;
                    uv[2 * k + 1] = dv == 0 ? 0 : (net.minecraft.client.model.geom.builders.UVPair.unpackV(packed) - sprite.getV0()) / dv;
                }
                int tint = q.materialInfo().isTinted() && tints.length > q.materialInfo().tintIndex()
                        ? tints[q.materialInfo().tintIndex()] & 0xFFFFFF : 0xFFFFFF;
                out.add(new Face(pos, uv, null, 0, 0, sprite.contents().name(), tint));
            }
        }
    }

    /** Fabric's renderer hands block models over as a mesh: its quads, as the block's baked ones. */
    @Override
    public void submitBlockModel(PoseStack poseStack, java.util.function.Function<net.minecraft.client.renderer.chunk.ChunkSectionLayer, RenderType> types,
            boolean flag, List<BlockStateModelPart> parts, net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh mesh, int[] tints, int light,
            int overlay, int outline) {
        if (parts != null && !parts.isEmpty()) submitBlockModel(poseStack, (RenderType) null, parts, tints, light, overlay, outline);
        if (mesh == null || mesh.size() == 0) return;
        Matrix4f m = new Matrix4f(poseStack.last().pose());
        var mc = Minecraft.getInstance();
        var any = mc.getModelManager().getBlockStateModelSet().get(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        var finder = ((net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas) mc.getTextureManager()
                .getTexture(any.particleMaterial().sprite().atlasLocation())).spriteFinder();
        mesh.forEach(q -> {
            TextureAtlasSprite sprite = finder.find(q);
            if (sprite == null) return;
            float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
            float[] pos = new float[12], uv = new float[8];
            for (int k = 0; k < 4; k++) {
                Vector3f p = m.transformPosition(q.x(k), q.y(k), q.z(k), new Vector3f());
                pos[3 * k] = p.x;
                pos[3 * k + 1] = p.y;
                pos[3 * k + 2] = p.z;
                uv[2 * k] = du == 0 ? 0 : (q.u(k) - sprite.getU0()) / du;
                uv[2 * k + 1] = dv == 0 ? 0 : (q.v(k) - sprite.getV0()) / dv;
            }
            int tint = q.tintIndex() >= 0 && tints.length > q.tintIndex() ? tints[q.tintIndex()] & 0xFFFFFF : 0xFFFFFF;
            out.add(new Face(pos, uv, null, 0, 0, sprite.contents().name(), tint));
        });
    }

    // ---- not baked: items, text, custom geometry and the rest belong to the block entity's data ----

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int outline, int[] tints,
            ItemQuads quads, ItemStackRenderState.FoilType foil) {}

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int light) {}

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, CustomGeometryRenderer renderer) {}

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {}

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 at, int offset, Component name, boolean seeThrough, int light,
            CameraRenderState camera) {}

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean shadow, Font.DisplayMode mode,
            int light, int color, int background, int outline) {}

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color, Font.DisplayMode mode,
            int light) {}

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {}

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leash) {}

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light,
            int overlay, int outline, ModelFeatureRenderer.CrumblingOverlay crumbling) {}

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress, boolean flag) {}

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean flag) {}

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {}

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean flag) {}
}
