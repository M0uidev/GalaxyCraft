package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.client.mixin.AgeableMobRendererAccessor;
import dev.moui.galaxycraft.client.mixin.LivingEntityRendererInvoker;
import dev.moui.galaxycraft.client.mixin.ModelPartAccessor;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.view.EntityWire;
import dev.moui.galaxycraft.view.HeldItem;
import dev.moui.galaxycraft.voxel.PlanetDrops;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.AgeableMobRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * The planet's entities drawn by the game instead of by Minecraft: dropped items (DropsClient),
 * and what Minecraft runs in the shadow dimension (mobs, primed TNT, falling blocks). Mobs are
 * posed by their own renderers and models, then cut into their rigid pieces: each piece and
 * each texture goes to the game once per scene, and every frame only where each piece is
 * (EntityWire). Not drawn yet: armor, held items and other render layers of mobs.
 */
final class EntityClient {
    /** Entities farther from Mario than this (blocks) are not sent. */
    static final double RANGE = 64;
    /** Overlays: a hurt mob's red, a flashing TNT's white (RGBA, A how much). */
    static final int HURT = 0xFF000066, FLASH = 0xFFFFFFA0;
    private static final Object CUBE = new Object();

    private final PlanetSession session;
    private final DropsClient drops;
    private final Map<Object, Integer> modelIds = new HashMap<>(), skinIds = new HashMap<>();
    private final Map<ModelPart, Integer> partIds = new IdentityHashMap<>();
    private final List<byte[]> models = new ArrayList<>(), skins = new ArrayList<>();
    private final Set<Integer> sentModels = new HashSet<>(), sentSkins = new HashSet<>();
    private final Map<Object, HeldClient.Look> looks = new HashMap<>();
    private final Set<Class<?>> failed = new HashSet<>();
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;
    private boolean wasEmpty = true;

    EntityClient(PlanetSession session, DropsClient drops) {
        this.session = session;
        this.drops = drops;
    }

    /** Render thread, once per emulated frame: this frame's entities to the game. */
    void frame(BridgeClient bridge, int sceneId, Vector3d marioFeetGal, float pt) {
        if (sceneId != scene || bridge.hostPid() != host) {
            sentModels.clear();
            sentSkins.clear();
            scene = sceneId;
            host = bridge.hostPid();
            wasEmpty = false;
        }
        List<EntityWire.Piece> pieces = new ArrayList<>();
        if (session.active() && marioFeetGal != null) {
            Vector3d mario = session.localOf(marioFeetGal);
            for (PlanetDrops.Drop<ItemStack> d : drops.all())
                if (d.pos.distance(mario) < RANGE) drop(d, pt, pieces);
            ShadowWorld.Entities shadow = ShadowWorld.entities();
            if (shadow != null && shadow.planet() == session.planet())
                for (Entity e : shadow.list()) shadowEntity(shadow, e, mario, pt, pieces);
        }
        if (pieces.isEmpty() && wasEmpty) return;
        for (EntityWire.Piece p : pieces) {
            if (!send(bridge, Layout.MSG_MODEL, p.model(), models, sentModels)) return;
            if (!send(bridge, Layout.MSG_SKIN, p.skin(), skins, sentSkins)) return;
        }
        if (bridge.send(Layout.MSG_ENTITIES, EntityWire.frame(pieces))) wasEmpty = pieces.isEmpty();
    }

    /** Sends a model or skin the game does not have yet; false if the ring is full. */
    private static boolean send(BridgeClient bridge, int type, int id, List<byte[]> payloads, Set<Integer> sent) {
        if (sent.contains(id)) return true;
        if (!bridge.send(type, payloads.get(id))) return false;
        sent.add(id);
        return true;
    }

    // ---- dropped items ----

    private void drop(PlanetDrops.Drop<ItemStack> d, float pt, List<EntityWire.Piece> out) {
        Item item = d.item.getItem();
        HeldClient.Look look = look(item, () -> HeldClient.look(d.item));
        if (look == null) return;
        int skin = lookSkin(item, look), model = lookModel(look);
        if (skin < 0 || model < 0) return;
        // Minecraft's ground items: a block a quarter, an item half its size, bobbing and turning.
        double age = d.age + pt, phase = (System.identityHashCode(d) & 0xFFFF) / 65536.0 * Math.PI * 2;
        double bob = Math.sin(age / 10 + phase) * 0.1 + 0.1;
        double size = look.kind() == HeldItem.ITEM || look.kind() == HeldItem.TOOL ? 0.5 : 0.25;
        Matrix4d m = upright(d.pos);
        m.translate(0, bob + size / 2 + 0.05, 0).rotateY(age / 20 + phase).scale(size / 16, -size / 16, size / 16);
        out.add(new EntityWire.Piece(model, skin, 0, toGal(m)));
    }

    /** Planet blocks around a planet point, y away from the center (the planet's up there). */
    private static Matrix4d upright(Vector3d at) {
        Vector3d up = new Vector3d(at).normalize();
        Vector3d x = Math.abs(up.y) < 0.9 ? new Vector3d(0, 1, 0).cross(up).normalize() : new Vector3d(1, 0, 0).cross(up).normalize();
        Vector3d z = new Vector3d(x).cross(up);
        return new Matrix4d(x.x, x.y, x.z, 0, up.x, up.y, up.z, 0, z.x, z.y, z.z, 0, at.x, at.y, at.z, 1);
    }

    /** Planet blocks to galaxy units, then 3x4 row-major. */
    private double[] toGal(Matrix4d planet) {
        Vector3d c = session.galOf(new Vector3d());
        double u = session.galOf(new Vector3d(1, 0, 0)).sub(c).x;
        Matrix4d g = new Matrix4d().translation(c).scale(u).mul(planet);
        return new double[] {g.m00(), g.m10(), g.m20(), g.m30(), g.m01(), g.m11(), g.m21(), g.m31(), g.m02(), g.m12(),
                g.m22(), g.m32()};
    }

    // ---- shadow entities ----

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void shadowEntity(ShadowWorld.Entities shadow, Entity e, Vector3d mario, float pt, List<EntityWire.Piece> out) {
        if (failed.contains(e.getClass())) return;
        try {
            EntityRenderer r = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e);
            EntityRenderState st = r.createRenderState(e, pt);
            double[] f = shadow.map().frame(st.x, st.y, st.z);
            if (f == null || new Vector3d(f[0], f[1], f[2]).distance(mario) > RANGE) return;
            // The shadow's blocks around the entity, as planet blocks (its frame there).
            Matrix4d at = new Matrix4d(f[3], f[4], f[5], 0, f[6], f[7], f[8], 0, f[9], f[10], f[11], 0, f[0], f[1], f[2], 1);
            PoseStack ps = new PoseStack();
            if (e instanceof PrimedTnt tnt) {
                float fuse = tnt.getFuse() - pt + 1;
                block(tnt.getBlockState(), 1 + (fuse < 10 ? TntRenderer.getSwellAmount(fuse) : 0),
                        TntRenderer.isLit(fuse) ? FLASH : 0, at, out);
            } else if (e instanceof FallingBlockEntity fb) {
                block(fb.getBlockState(), 1, 0, at, out);
            } else if (r instanceof LivingEntityRenderer lr && st instanceof LivingEntityRenderState ls) {
                living(lr, ls, at, ps, out);
            }
        } catch (RuntimeException ex) {
            // Some renderers need what only the client's own entities have: those stay unseen.
            failed.add(e.getClass());
            GalaxyCraft.LOG.warn("Not drawing {} in the game: {}", e.getType(), ex.toString());
        }
    }

    /** A block entity (TNT, falling sand): its block's cube, scaled around its center. */
    private void block(BlockState state, double scale, int overlay, Matrix4d at, List<EntityWire.Piece> out) {
        HeldClient.Look look = look(state, () -> HeldClient.look(state));
        if (look == null) return;
        int skin = lookSkin(state, look), model = lookModel(look);
        if (skin < 0 || model < 0) return;
        Matrix4d m = new Matrix4d(at).translate(0, 0.5, 0).scale(scale / 16, -scale / 16, scale / 16);
        out.add(new EntityWire.Piece(model, skin, overlay, toGal(m)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void living(LivingEntityRenderer r, LivingEntityRenderState st, Matrix4d at, PoseStack ps, List<EntityWire.Piece> out) {
        if (st.isInvisible) return;
        int skin = entitySkin(r.getTextureLocation(st));
        if (skin < 0) return;
        LivingEntityRendererInvoker inv = (LivingEntityRendererInvoker) r;
        // As LivingEntityRenderer.submit poses the model.
        ps.scale(st.scale, st.scale, st.scale);
        inv.galaxycraft$setupRotations(st, ps, st.bodyRot, st.scale);
        ps.scale(-1, -1, 1);
        inv.galaxycraft$scale(st, ps);
        ps.translate(0, -1.501F, 0);
        EntityModel model = r instanceof AgeableMobRenderer am
                ? (st.isBaby ? ((AgeableMobRendererAccessor) am).galaxycraft$babyModel() : ((AgeableMobRendererAccessor) am).galaxycraft$adultModel())
                : r.getModel();
        model.setupAnim(st);
        walk(model.root(), ps, at, skin, st.hasRedOverlay ? HURT : 0, out);
    }

    /** As ModelPart.render: each visible piece with cubes, where the pose puts it. */
    private void walk(ModelPart part, PoseStack ps, Matrix4d at, int skin, int overlay, List<EntityWire.Piece> out) {
        ModelPartAccessor a = (ModelPartAccessor) (Object) part;
        if (!part.visible || (a.galaxycraft$cubes().isEmpty() && a.galaxycraft$children().isEmpty())) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw && !a.galaxycraft$cubes().isEmpty()) {
            int model = partModel(part, a);
            if (model >= 0) {
                Matrix4f pose = ps.last().pose();
                Matrix4d m = new Matrix4d(at).mul(new Matrix4d(pose)).scale(1 / 16.0);
                out.add(new EntityWire.Piece(model, skin, overlay, toGal(m)));
            }
        }
        for (ModelPart child : a.galaxycraft$children().values()) walk(child, ps, at, skin, overlay, out);
        ps.popPose();
    }

    // ---- models and skins, made once ----

    private int partModel(ModelPart part, ModelPartAccessor a) {
        Integer id = partIds.get(part);
        if (id != null) return id;
        List<EntityWire.Quad> quads = new ArrayList<>();
        for (ModelPart.Cube cube : a.galaxycraft$cubes())
            for (ModelPart.Polygon poly : cube.polygons) {
                if (poly.vertices().length != 4) continue;
                float[][] pos = new float[4][], uv = new float[4][];
                for (int k = 0; k < 4; k++) {
                    ModelPart.Vertex v = poly.vertices()[k];
                    pos[k] = new float[] {v.x(), v.y(), v.z()};
                    uv[k] = new float[] {v.u(), v.v()};
                }
                quads.add(new EntityWire.Quad(pos, uv, EntityWire.shade(poly.normal().x(), poly.normal().y(), poly.normal().z())));
            }
        id = addModel(quads);
        partIds.put(part, id);
        return id;
    }

    /** How an item or block looks, worked out once (also when it cannot be shown: null). */
    private HeldClient.Look look(Object key, Supplier<HeldClient.Look> make) {
        if (!looks.containsKey(key)) looks.put(key, make.get());
        return looks.get(key);
    }

    private int lookModel(HeldClient.Look look) {
        boolean cube = look.kind() == HeldItem.BLOCK || look.kind() == HeldItem.CUBE;
        Object key = cube ? CUBE : look;
        Integer id = modelIds.get(key);
        if (id == null) {
            id = addModel(cube ? EntityWire.cube(3) : EntityWire.flatItem(look.bands()[0], 1));
            modelIds.put(key, id);
        }
        return id;
    }

    private int addModel(List<EntityWire.Quad> quads) {
        if (models.size() >= Layout.ENT_MAX_MODELS) return -1;
        byte[] payload = EntityWire.model(models.size(), quads);
        if (payload == null) return -1;
        models.add(payload);
        return models.size() - 1;
    }

    /** An item's or block's skin: its bands stacked (a cube's three faces, or the flat sprite). */
    private int lookSkin(Object key, HeldClient.Look look) {
        Integer id = skinIds.get(key);
        if (id != null) return id;
        int[][] bands = look.kind() == HeldItem.CUBE ? new int[][] {look.bands()[0], look.bands()[0], look.bands()[0]}
                : look.bands();
        int[] argb = new int[16 * 16 * bands.length];
        for (int b = 0; b < bands.length; b++) System.arraycopy(bands[b], 0, argb, b * 256, 256);
        id = addSkin(16, 16 * bands.length, argb);
        skinIds.put(key, id);
        return id;
    }

    /** A mob's texture, read from the resources (at most ENT_SKIN_MAX a side: larger ones shrink). */
    private int entitySkin(Identifier texture) {
        Integer id = skinIds.get(texture);
        if (id != null) return id;
        int result = -1;
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(texture); NativeImage img = NativeImage.read(in)) {
            int w = img.getWidth(), h = img.getHeight(), div = 1;
            while (w / div > Layout.ENT_SKIN_MAX || h / div > Layout.ENT_SKIN_MAX) div *= 2;
            int sw = w / div, sh = h / div;
            int[] argb = new int[sw * sh];
            for (int y = 0; y < sh; y++)
                for (int x = 0; x < sw; x++) argb[y * sw + x] = img.getPixel(x * div, y * div);
            result = addSkin(sw, sh, argb);
        } catch (Exception ex) {
            GalaxyCraft.LOG.warn("No texture {} for the game: {}", texture, ex.toString());
        }
        skinIds.put(texture, result);
        return result;
    }

    private int addSkin(int w, int h, int[] argb) {
        if (skins.size() >= Layout.ENT_MAX_SKINS) return -1;
        skins.add(EntityWire.skin(skins.size(), w, h, argb));
        return skins.size() - 1;
    }
}
