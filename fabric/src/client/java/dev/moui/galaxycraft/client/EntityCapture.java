package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.moui.galaxycraft.client.mixin.ModelPartAccessor;
import dev.moui.galaxycraft.view.EntityWire;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * What an entity's own Minecraft renderer submits, turned into the game's pieces (EntityClient):
 * its models (with their layers: armor, held items, wool, saddles), item and block models (thrown
 * items, a minecart's block, TNT) and custom quads (paintings). Name tags, shadows, flames,
 * leashes and outlines are left out.
 */
final class EntityCapture implements SubmitNodeCollector {
    private final EntityClient owner;
    private Matrix4d at;
    private List<EntityWire.Piece> out;

    EntityCapture(EntityClient owner) {
        this.owner = owner;
    }

    /** What follows is drawn around a shadow entity whose blocks are at (shadow -> planet). */
    void begin(Matrix4d at, List<EntityWire.Piece> out) {
        this.at = at;
        this.out = out;
    }

    /** Pose (blocks) -> planet, a model pixel to a block. */
    private Matrix4d place(Matrix4f pose) {
        return new Matrix4d(at).mul(new Matrix4d(pose)).scale(1 / 16.0);
    }

    // ---- models ----

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay,
            int color, UvMapping uv, int outline) {
        int skin = uv instanceof TextureAtlasSprite s ? owner.spriteSkin(s) : owner.renderTypeSkin(renderType);
        if (skin < 0) return;
        // Minecraft poses a model when it draws it (a part alone, Unit, is posed already).
        if (!(state instanceof Unit)) ((Model) model).setupAnim(state);
        PoseStack ps = new PoseStack();
        ps.last().pose().set(poseStack.last().pose());
        walk(model.root(), ps, skin, overlay(overlay), tint(color));
    }

    /** As ModelPart.render: each visible piece with cubes, where the pose puts it. */
    private void walk(ModelPart part, PoseStack ps, int skin, int overlay, int tint) {
        ModelPartAccessor a = (ModelPartAccessor) (Object) part;
        if (!part.visible || (a.galaxycraft$cubes().isEmpty() && a.galaxycraft$children().isEmpty())) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw && !a.galaxycraft$cubes().isEmpty()) {
            int model = owner.partModel(part, a);
            if (model >= 0) out.add(new EntityWire.Piece(model, skin, overlay, tint, owner.toGal(place(ps.last().pose()))));
        }
        for (ModelPart child : a.galaxycraft$children().values()) walk(child, ps, skin, overlay, tint);
        ps.popPose();
    }

    /** Minecraft's overlay coordinates as the game's overlay: red when hurt, white flashes (TNT, creepers). */
    static int overlay(int coords) {
        int u = coords & 0xFFFF, v = coords >>> 16;
        if (v < 8) return EntityClient.HURT;
        return u > 0 ? 0xFFFFFF00 | Math.min(255, u * 255 / 15) * 3 / 5 : 0;
    }

    /** ARGB as RGBA (-1: white). */
    static int tint(int argb) {
        return argb << 8 | argb >>> 24;
    }

    // ---- items and blocks ----

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int outline, int[] tints,
            ItemQuads quads, ItemStackRenderState.FoilType foil) {
        owner.quadPieces(quads.all(), quads.all(), tints, place(new Matrix4f(poseStack.last().pose())), overlay(overlay), out);
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tints, int light,
            int overlay, int outline) {
        Matrix4d m = place(new Matrix4f(poseStack.last().pose()));
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = new ArrayList<>(part.getQuads(null));
            for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
            owner.quadPieces(part, quads, tints, m, overlay(overlay), out);
        }
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int light) {
        Matrix4d m = new Matrix4d(at).mul(new Matrix4d(poseStack.last().pose())).translate(0.5, 0.5, 0.5).scale(1 / 16.0, -1 / 16.0, 1 / 16.0);
        owner.blockPiece(state.blockState, m, 0, out);
    }

    // ---- custom quads (paintings) ----

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, CustomGeometryRenderer renderer) {
        if (!renderType.primitiveTopology().name().equals("QUADS")) return;
        int skin = owner.renderTypeSkin(renderType);
        if (skin < 0) return;
        Recorder r = new Recorder();
        renderer.render(new PoseStack().last(), r);
        int model = owner.customModel(r.done());
        if (model >= 0) out.add(new EntityWire.Piece(model, skin, 0, -1, owner.toGal(place(new Matrix4f(poseStack.last().pose())))));
    }

    /** Quads written to a vertex consumer: positions (as pixels), texture coordinates and colors. */
    static final class Recorder implements VertexConsumer {
        final List<EntityWire.Quad> quads = new ArrayList<>();
        private final float[][] pos = new float[4][], uv = new float[4][];
        private int n = -1, rgba = -1;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            flush();
            n++;
            pos[n] = new float[] {x * 16, y * 16, z * 16};
            uv[n] = new float[] {0, 0};
            return this;
        }

        private void flush() {
            if (n == 3) {
                quads.add(new EntityWire.Quad(pos.clone(), uv.clone(), rgba));
                n = -1;
            }
        }

        List<EntityWire.Quad> done() {
            flush();
            return quads;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            rgba = r << 24 | g << 16 | b << 8 | a;
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            rgba = tint(argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (n >= 0) uv[n] = new float[] {u, v};
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    // ---- not drawn by the game ----

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
