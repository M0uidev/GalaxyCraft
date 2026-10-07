package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.client.mixin.ModelPartAccessor;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.shadow.LiveBlocks;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.view.EntityWire;
import dev.moui.galaxycraft.view.HeldItem;
import dev.moui.galaxycraft.view.SkinImage;
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
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * The planet's entities drawn by the game instead of by Minecraft: dropped items (DropsClient),
 * and everything Minecraft runs in the shadow dimension (mobs, arrows, minecarts, boats, TNT,
 * falling blocks, armor stands, paintings...), drawn by its own renderer into EntityCapture and
 * cut into rigid pieces: each piece and each texture goes to the game once per scene, and every
 * frame only where each piece is (EntityWire).
 */
final class EntityClient {
    /** Entities farther from Mario than this (blocks) are never sent; the settings may say less. */
    static final double RANGE = 64;
    /** A hurt mob's overlay: red (RGBA, A how much). */
    static final int HURT = 0xFF000066;
    private static final Object CUBE = new Object();

    /** The planet in focus (the one nearest Mario): it can change from one tick to the next. */
    private final java.util.function.Supplier<PlanetSession> focus;
    private final DropsClient drops;
    private final Map<Object, Integer> modelIds = new HashMap<>(), skinIds = new HashMap<>();
    private final Map<ModelPart, Integer> partIds = new IdentityHashMap<>();
    private final List<byte[]> models = new ArrayList<>(), skins = new ArrayList<>();
    private final Set<Integer> sentModels = new HashSet<>(), sentSkins = new HashSet<>();
    private final Map<Object, HeldClient.Look> looks = new HashMap<>();
    private final Set<Class<?>> failed = new HashSet<>();
    private final ParticleClient particles = new ParticleClient();
    private final EntityCapture capture = new EntityCapture(this);
    private final CameraRenderState camera = new CameraRenderState();
    private final Map<RenderType, java.util.Optional<Identifier>> textures = new IdentityHashMap<>();
    /** Custom shapes made at most (they are made by their looks, which may keep changing). */
    static final int MAX_CUSTOM = 1024;
    private int customModels;
    /** Shadow entities drawn last frame and the shadow's frame around each (what the clicks can hit). */
    private final List<Seen> seen = new ArrayList<>();

    private record Seen(Entity entity, Matrix4d at, Vector3d pos) {}
    /** While the local player is captured: Minecraft blocks around it -> galaxy units (else null: planet blocks). */
    private Matrix4d galaxy;
    /** While the local player is captured: the skin (/skin) its body wears instead of its own, -1 none. */
    private int playerSkin = -1;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;
    private boolean wasEmpty = true;

    EntityClient(java.util.function.Supplier<PlanetSession> focus, DropsClient drops) {
        this.focus = focus;
        this.drops = drops;
        camera.orientation = new org.joml.Quaternionf(); // renderers that face the camera (thrown items) need one
        camera.pos = net.minecraft.world.phys.Vec3.ZERO;
    }

    /** The particles on the planet (to add the client's own: a block being broken, or placed). */
    ParticleClient particles() {
        return particles;
    }

    int particleCount() {
        return particles.all().size();
    }

    /** Client tick: the particles move; live blocks leave the chunk mesh (and baked ones come back). */
    void tick() {
        particles.tick(session().active() ? session().planet() : null);
        hideLive();
    }

    // ---- live blocks (LiveBlocks): drawn by their own renderer, not baked ----

    /** The planet whose cells are hidden now (null: none). */
    private dev.moui.galaxycraft.voxel.VoxelPlanet hiddenOn;

    private void hideLive() {
        var p = session().active() ? session().planet() : null;
        LiveBlocks.Snapshot live = ShadowWorld.live();
        Set<Integer> want = new HashSet<>();
        McBlocks blocks = PlanetClient.blocks();
        // Only a block its renderer draws whole leaves the mesh: a shelf keeps its own, its items come on top.
        if (p != null && live != null && live.planet() == p && blocks != null)
            for (LiveBlocks.Live l : live.list())
                if (blocks.drawnByRenderer(p.get(l.cell()))) want.add(l.cell());
        if (hiddenOn != null && hiddenOn != p)
            for (int c : hiddenOn.hiddenCells()) hiddenOn.hide(c, false);
        hiddenOn = p;
        if (p == null) return;
        for (int c : p.hiddenCells())
            if (!want.contains(c)) p.hide(c, false);
        for (int c : want) p.hide(c, true);
    }

    /** A live block drawn by its own renderer where its cell is, lit by that cell. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void liveBlock(LiveBlocks.Snapshot live, LiveBlocks.Live l, Vector3d mario, net.minecraft.world.phys.Vec3 eye, float pt,
            List<EntityWire.Piece> out) {
        BlockEntity be = l.entity();
        // Not before its baked shape has left the mesh (one tick), so the two never show together.
        McBlocks blocks = PlanetClient.blocks();
        int id = session().planet().get(l.cell());
        if (failed.contains(be.getClass()) || blocks == null || blocks.drawnByRenderer(id) && !session().planet().hidden(l.cell())) return;
        try {
            BlockEntityRenderer r = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            if (r == null) return;
            BlockPos pos = be.getBlockPos();
            // The planet around the block's center, where a cell's bend is best matched by one
            // frame (from its corner, a diagonal sign's text leaned into its board).
            double[] f = live.map().frame(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (f == null) return;
            BlockEntityRenderState st = r.createRenderState();
            r.extractRenderState(be, st, pt, eye, null);
            // Glowing text's outline shows near the player: Mario here (Minecraft's player is not in the shadow).
            if (st instanceof net.minecraft.client.renderer.blockentity.state.SignRenderState sign)
                sign.drawOutline = dev.moui.galaxycraft.voxel.CellSpace.point(session().planet().grid, l.cell(), 0.5, 0.5, 0.5)
                        .distance(mario) < SIGN_OUTLINE;
            Matrix4d at = new Matrix4d(f[3], f[4], f[5], 0, f[6], f[7], f[8], 0, f[9], f[10], f[11], 0, f[0], f[1], f[2], 1)
                    .translate(-0.5, -0.5, -0.5);
            int from = out.size();
            capture.begin(at, out);
            r.submit(st, new PoseStack(), capture, camera);
            for (int i = from; i < out.size(); i++) cellOf.put(i, l.cell());
        } catch (RuntimeException ex) {
            failed.add(be.getClass());
            GalaxyCraft.LOG.warn("Not drawing {} in the game: {}", be.getType(), ex.toString());
        }
    }

    /** Glowing sign text has its outline within this many blocks (AbstractSignRenderer's 16). */
    static final double SIGN_OUTLINE = 16;

    /** This frame's pieces lit by a given cell (a live block's), by index; the others by where they are. */
    private final Map<Integer, Integer> cellOf = new HashMap<>();
    /** This frame's pieces that light nothing dims (glowing sign text), by index. */
    final Set<Integer> glowing = new HashSet<>();

    /**
     * The mob nearest along the look from eye (planet space) whose box it meets within reach
     * blocks; null if none.
     */
    Entity aimed(Vector3d eye, Vector3d look, double reach) {
        Entity best = null;
        double bestDist = reach;
        for (Seen s : seen) {
            if (!s.entity().isAlive() || !s.entity().isPickable()) continue;
            // Planet points into the shadow around the entity: there its box is a plain box.
            Matrix4d inv = new Matrix4d(s.at()).invertAffine();
            Vector3d from = inv.transformPosition(new Vector3d(eye)).add(s.pos());
            Vector3d to = inv.transformPosition(new Vector3d(look).normalize().mul(reach).add(eye)).add(s.pos());
            var hit = s.entity().getBoundingBox().inflate(s.entity().getPickRadius())
                    .clip(new net.minecraft.world.phys.Vec3(from.x, from.y, from.z), new net.minecraft.world.phys.Vec3(to.x, to.y, to.z));
            if (hit.isEmpty()) continue;
            Vector3d h = new Vector3d(hit.get().x, hit.get().y, hit.get().z).sub(s.pos());
            double d = s.at().transformPosition(h).distance(eye);
            if (d < bestDist) {
                bestDist = d;
                best = s.entity();
            }
        }
        return best;
    }

    /**
     * Each piece in the light where it is, as Minecraft lights entities: the planet's block light
     * and sky light there (the brighter of its cell and the one above, so a piece sunk into the
     * ground is not black) under the sky's light of the hour, times the piece's own tint.
     */
    private List<EntityWire.Piece> lit(List<EntityWire.Piece> pieces) {
        if (!session().active()) return pieces;
        var p = session().planet();
        int sky = PlanetClient.skyLight();
        java.util.Map<Integer, Integer> byCell = new java.util.HashMap<>();
        List<EntityWire.Piece> out = new ArrayList<>(pieces.size());
        for (EntityWire.Piece piece : pieces) {
            double[] m = piece.mtx();
            int cell = cellOf.getOrDefault(out.size(), session().cellAt(new Vector3d(m[3], m[7], m[11])));
            if (glowing.contains(out.size())) {
                out.add(piece);
                continue;
            }
            int light = byCell.computeIfAbsent(cell, c -> {
                if (c < 0) return sky;
                int up = p.grid.neighbor(c, dev.moui.galaxycraft.voxel.CubeSphere.TOP);
                int l = dev.moui.galaxycraft.voxel.PlanetMesher.lightRGBA(Math.max(p.light().sky(c), p.light().sky(up)),
                        Math.max(p.light().block(c), p.light().block(up)));
                int a = l & 0xFF, rgb = 0;
                for (int sh = 16; sh >= 0; sh -= 8) {
                    int ch = (l >>> (sh + 8) & 0xFF) + a * (sky >> sh & 0xFF) / 255;
                    rgb |= Math.min(255, ch) << sh;
                }
                return rgb;
            });
            int t = piece.tint(), tinted = t & 0xFF;
            for (int sh = 24; sh >= 8; sh -= 8)
                tinted |= ((t >>> sh & 0xFF) * (light >> (sh - 8) & 0xFF) / 255) << sh;
            out.add(new EntityWire.Piece(piece.model(), piece.skin(), piece.overlay(), tinted, m));
        }
        return out;
    }

    /** Render thread, once per emulated frame: this frame's entities to the game. */
    void frame(BridgeClient bridge, int sceneId, Vector3d marioFeetGal, float pt, GravityFrame self) {
        if (sceneId != scene || bridge.hostPid() != host) {
            sentModels.clear();
            sentSkins.clear();
            scene = sceneId;
            host = bridge.hostPid();
            wasEmpty = false;
        }
        List<EntityWire.Piece> pieces = new ArrayList<>();
        seen.clear();
        cellOf.clear();
        glowing.clear();
        if (session().active() && marioFeetGal != null) {
            Vector3d mario = session().localOf(marioFeetGal);
            for (PlanetDrops.Drop<ItemStack> d : drops.all())
                if (d.pos.distance(mario) < range()) drop(d, pt, pieces);
            ShadowWorld.Entities shadow = ShadowWorld.entities();
            if (shadow != null && shadow.planet() == session().planet())
                for (Entity e : shadow.list()) shadowEntity(shadow, e, mario, pt, pieces);
            LiveBlocks.Snapshot live = ShadowWorld.live();
            if (live != null && live.planet() == session().planet()) {
                int mc = session().planet().grid.cellAt(mario);
                net.minecraft.world.phys.Vec3 eye = mc < 0 ? net.minecraft.world.phys.Vec3.ZERO
                        : new net.minecraft.world.phys.Vec3(live.map().x(mc) + 0.5, live.map().y(mc) + 1.6, live.map().z(mc) + 0.5);
                for (LiveBlocks.Live l : live.list()) liveBlock(live, l, mario, eye, pt, pieces);
            }
            if (GalaxyOptions.PARTICLES.get())
                for (ParticleClient.Live l : particles.all()) particle(l, mario, pt, pieces);
        }
        if (self != null) self(self, pt, pieces);
        else if (session().active() && marioFeetGal != null) heldPieces(session().cellAt(marioFeetGal), pieces);
        if (pieces.isEmpty() && wasEmpty) return;
        pieces = lit(pieces);
        for (EntityWire.Piece p : pieces) {
            if (!send(bridge, Layout.MSG_MODEL, p.model() & ~(EntityWire.BILLBOARD | HELD | LEFT), models, sentModels)) return;
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
        Matrix4d m = upright(d.pos, session().planet().up(d.pos));
        m.translate(0, bob + size / 2 + 0.05, 0).rotateY(age / 20 + phase).scale(size / 16, -size / 16, size / 16);
        out.add(new EntityWire.Piece(model, skin, 0, -1, toGal(m)));
    }

    /** Planet blocks around a planet point, y the planet's up there. */
    private static Matrix4d upright(Vector3d at, Vector3d up) {
        Vector3d x = Math.abs(up.y) < 0.9 ? new Vector3d(0, 1, 0).cross(up).normalize() : new Vector3d(1, 0, 0).cross(up).normalize();
        Vector3d z = new Vector3d(x).cross(up);
        return new Matrix4d(x.x, x.y, x.z, 0, up.x, up.y, up.z, 0, z.x, z.y, z.z, 0, at.x, at.y, at.z, 1);
    }

    /** Planet blocks (or, while the player is captured, Minecraft blocks around it) to galaxy units, then 3x4 row-major. */
    double[] toGal(Matrix4d planet) {
        Matrix4d g;
        if (galaxy != null) g = new Matrix4d(galaxy).mul(planet);
        else {
            Vector3d c = session().galOf(new Vector3d());
            double u = session().galOf(new Vector3d(1, 0, 0)).sub(c).x;
            g = new Matrix4d().translation(c).scale(u).mul(planet);
        }
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
            if (f == null || new Vector3d(f[0], f[1], f[2]).distance(mario) > range()) return;
            // The shadow's blocks around the entity, as planet blocks (its frame there).
            Matrix4d at = new Matrix4d(f[3], f[4], f[5], 0, f[6], f[7], f[8], 0, f[9], f[10], f[11], 0, f[0], f[1], f[2], 1);
            seen.add(new Seen(e, at, new Vector3d(st.x, st.y, st.z)));
            // Its own renderer draws it, into the game's pieces (EntityCapture).
            PoseStack ps = new PoseStack();
            net.minecraft.world.phys.Vec3 offset = r.getRenderOffset(st);
            ps.translate(offset.x, offset.y, offset.z);
            capture.begin(at, out);
            r.submit(st, ps, capture, camera);
        } catch (RuntimeException ex) {
            // Some renderers need what only the client's own entities have: those stay unseen.
            failed.add(e.getClass());
            GalaxyCraft.LOG.warn("Not drawing {} in the game: {}", e.getType(), ex.toString());
        }
    }

    /** A particle, facing the camera: its sprite now, or a bit of its block's side. */
    private void particle(ParticleClient.Live l, Vector3d mario, float pt, List<EntityWire.Piece> out) {
        Vector3d at = new Vector3d(l.pos).fma(pt, l.vel);
        if (at.distance(mario) > range()) return;
        int skin, model;
        if (l.block != null) {
            HeldClient.Look look = look(l.block, () -> HeldClient.look(l.block));
            if (look == null) return;
            skin = lookSkin(l.block, look);
            model = squareModel("bit" + l.bit, (l.bit % 4) / 4f, (1 + (l.bit / 4) / 4f) / 3, (l.bit % 4 + 1) / 4f,
                    (1 + (l.bit / 4 + 1) / 4f) / 3);
        } else {
            skin = entitySkin(l.frame());
            model = squareModel("square", 0, 0, 1, 1);
        }
        if (skin < 0 || model < 0) return;
        Matrix4d m = new Matrix4d().translation(at).scale(l.size / 16);
        out.add(new EntityWire.Piece(model | EntityWire.BILLBOARD, skin, 0, -1, toGal(m)));
    }

    private int squareModel(String key, float u0, float v0, float u1, float v1) {
        Integer id = modelIds.get(key);
        if (id == null) {
            id = addModel(EntityWire.square(u0, v0, u1, v1));
            modelIds.put(key, id);
        }
        return id;
    }

    /** A block as its cube of faces (a falling block), m: its center, a pixel to a block, y down. */
    void blockPiece(BlockState state, Matrix4d m, int overlay, List<EntityWire.Piece> out) {
        HeldClient.Look look = look(state, () -> HeldClient.look(state));
        if (look == null) return;
        int skin = lookSkin(state, look), model = lookModel(look);
        if (skin >= 0 && model >= 0) out.add(new EntityWire.Piece(model, skin, overlay, -1, toGal(m)));
    }

    /**
     * Baked quads of an item or block model (positions in blocks), one piece per sprite: each
     * sprite's quads a model (made once per source and tints), its texture a skin.
     */
    void quadPieces(Object source, List<BakedQuad> quads, int[] tints, Matrix4d m, int overlay, List<EntityWire.Piece> out) {
        Map<TextureAtlasSprite, List<BakedQuad>> bySprite = new IdentityHashMap<>();
        for (BakedQuad q : quads) bySprite.computeIfAbsent(q.materialInfo().sprite(), k -> new ArrayList<>()).add(q);
        for (var entry : bySprite.entrySet()) {
            TextureAtlasSprite sprite = entry.getKey();
            int skin = spriteSkin(sprite);
            if (skin < 0) continue;
            QuadsKey key = new QuadsKey(new Ident(source), sprite, java.util.Arrays.hashCode(tints));
            Integer model = modelIds.get(key);
            if (model == null) {
                List<EntityWire.Quad> converted = new ArrayList<>();
                for (BakedQuad q : entry.getValue()) converted.add(quad(q, sprite, tints));
                model = addModel(converted);
                modelIds.put(key, model);
            }
            if (model >= 0) out.add(new EntityWire.Piece(model, skin, overlay, -1, toGal(m)));
        }
    }

    private record Ident(Object o) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Ident i && i.o == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(o);
        }
    }

    private record QuadsKey(Ident source, TextureAtlasSprite sprite, int tints) {}

    /** A baked quad in pixels, its sprite's own texture coordinates, shaded and tinted as Minecraft. */
    private static EntityWire.Quad quad(BakedQuad q, TextureAtlasSprite s, int[] tints) {
        float[][] pos = new float[4][], uv = new float[4][];
        for (int k = 0; k < 4; k++) {
            var p = q.position(k);
            pos[k] = new float[] {p.x() * 16, p.y() * 16, p.z() * 16};
            long packed = q.packedUV(k);
            uv[k] = new float[] {(UVPair.unpackU(packed) - s.getU0()) / (s.getU1() - s.getU0()),
                    (UVPair.unpackV(packed) - s.getV0()) / (s.getV1() - s.getV0())};
        }
        var n = q.direction().getUnitVec3f();
        int rgba = EntityWire.shade(n.x(), -n.y(), n.z()); // baked models have y up
        int ti = q.materialInfo().tintIndex();
        if (q.materialInfo().isTinted() && ti >= 0 && ti < tints.length) rgba = multiply(rgba, EntityCapture.tint(tints[ti]));
        return new EntityWire.Quad(pos, uv, rgba);
    }

    private static int multiply(int a, int b) {
        int out = 0;
        for (int sh = 0; sh < 32; sh += 8) out |= ((a >>> sh & 0xFF) * (b >>> sh & 0xFF) / 255) << sh;
        return out;
    }

    /** Custom quads (a painting): a model per shape, made once. */
    int customModel(List<EntityWire.Quad> quads) {
        int hash = 1;
        for (EntityWire.Quad q : quads)
            hash = 31 * hash + java.util.Arrays.deepHashCode(q.pos()) * 7 + java.util.Arrays.deepHashCode(q.uv()) + q.rgba();
        String key = "custom" + quads.size() + ":" + hash;
        Integer id = modelIds.get(key);
        if (id == null) {
            if (++customModels > MAX_CUSTOM) return -1; // ever-changing shapes would use up the ids
            id = addModel(quads);
            modelIds.put(key, id);
        }
        return id;
    }

    /** A model's texture from its render type: the image it samples (null: none, or an atlas). */
    int renderTypeSkin(RenderType type) {
        Identifier texture = textures.computeIfAbsent(type, EntityClient::textureOf).orElse(null);
        if (texture == null) return -1;
        // The player's body, whatever skin Minecraft gave it (a default one, or downloaded), wears /skin's.
        if (playerSkin >= 0 && (texture.getPath().startsWith("textures/entity/player/") || texture.getPath().startsWith("skins/")))
            return playerSkin;
        return entitySkin(texture);
    }

    /** The texture a render type draws with (null if it has none or it cannot be read). */
    static Identifier renderTypeTexture(RenderType type) {
        return textureOf(type).orElse(null);
    }

    private static java.util.Optional<Identifier> textureOf(RenderType type) {
        try {
            java.lang.reflect.Field state = RenderType.class.getDeclaredField("state");
            state.setAccessible(true);
            Object setup = state.get(type);
            java.lang.reflect.Field textures = setup.getClass().getDeclaredField("textures");
            textures.setAccessible(true);
            for (Object binding : ((Map<?, ?>) textures.get(setup)).values()) {
                java.lang.reflect.Method location = binding.getClass().getDeclaredMethod("location");
                location.setAccessible(true);
                return java.util.Optional.of((Identifier) location.invoke(binding));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            GalaxyCraft.LOG.warn("No texture in {}: {}", type, e.toString());
        }
        return java.util.Optional.empty();
    }

    /** A font page as a skin; -1 until it has been read back. */
    int fontSkin(com.mojang.renderpearl.api.textures.GpuTexture page) {
        return FontPages.skin(page, (wh, argb) -> addSkin(wh[0], wh[1], argb));
    }

    /** A sprite's own image (its first frame), as a skin. */
    int spriteSkin(TextureAtlasSprite s) {
        Identifier name = s.contents().name();
        return entitySkin(name.withPath(p -> "textures/" + p + ".png"), true);
    }

    // ---- models and skins, made once ----

    int partModel(ModelPart part, ModelPartAccessor a) {
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
        return entitySkin(texture, false);
    }

    /** firstFrame: an animated sprite's frames stack downward, only the top square is used. */
    private int entitySkin(Identifier texture, boolean firstFrame) {
        Integer id = skinIds.get(texture);
        if (id != null) return id;
        int result = -1;
        // A downloaded one (a player head's skin) is no resource: Minecraft keeps its pixels.
        if (texture.getPath().startsWith("skins/") // only there: getTexture would load any other
                && Minecraft.getInstance().getTextureManager().getTexture(texture) instanceof net.minecraft.client.renderer.texture.DynamicTexture dt
                && dt.getPixels() != null)
            result = skinOf(dt.getPixels(), false);
        else
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(texture); NativeImage img = NativeImage.read(in)) {
                result = skinOf(img, firstFrame);
            } catch (Exception ex) {
                GalaxyCraft.LOG.warn("No texture {} for the game: {}", texture, ex.toString());
            }
        skinIds.put(texture, result);
        return result;
    }

    /** An image as a skin, at most ENT_SKIN_MAX a side (larger ones shrink). */
    private int skinOf(NativeImage img, boolean firstFrame) {
        int w = img.getWidth(), h = firstFrame ? Math.min(img.getHeight(), img.getWidth()) : img.getHeight(), div = 1;
        while (w / div > Layout.ENT_SKIN_MAX || h / div > Layout.ENT_SKIN_MAX) div *= 2;
        int sw = w / div, sh = h / div;
        int[] argb = new int[sw * sh];
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++) argb[y * sw + x] = img.getPixel(x * div, y * div);
        return addSkin(sw, sh, argb);
    }

    private int addSkin(int w, int h, int[] argb) {
        if (skins.size() >= Layout.ENT_MAX_SKINS) return -1;
        skins.add(EntityWire.skin(skins.size(), w, h, argb));
        return skins.size() - 1;
    }

    private PlanetSession session() {
        return focus.get();
    }

    private static double range() {
        return Math.min(RANGE, GalaxyOptions.ENTITY_RANGE.get());
    }

    // ---- the player itself (Minecraft movement) ----

    /**
     * The local player drawn by its own renderer where it is, as SMG2 draws no Mario then: its
     * pieces placed from the gravity frame (Minecraft blocks -> galaxy units) instead of a planet.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void self(GravityFrame frame, float pt, List<EntityWire.Piece> out) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || failed.contains(player.getClass())) return;
        try {
            EntityRenderer r = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
            EntityRenderState st = r.createRenderState(player, pt);
            Vector3d o = frame.toGal(new Vector3d(st.x, st.y, st.z));
            Vector3d x = frame.toGal(new Vector3d(st.x + 1, st.y, st.z)).sub(o);
            Vector3d y = frame.toGal(new Vector3d(st.x, st.y + 1, st.z)).sub(o);
            Vector3d z = frame.toGal(new Vector3d(st.x, st.y, st.z + 1)).sub(o);
            galaxy = new Matrix4d(x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0, o.x, o.y, o.z, 1);
            playerSkin = customSkin();
            PoseStack ps = new PoseStack();
            net.minecraft.world.phys.Vec3 offset = r.getRenderOffset(st);
            ps.translate(offset.x, offset.y, offset.z);
            capture.begin(new Matrix4d(), out);
            r.submit(st, ps, capture, camera);
        } catch (RuntimeException ex) {
            failed.add(player.getClass());
            GalaxyCraft.LOG.warn("Not drawing the player in the game: {}", ex.toString());
        } finally {
            galaxy = null;
            playerSkin = -1;
        }
    }

    /** EntityWire model id flags: the piece is held in Steve's hand (its matrix in Minecraft's hand frame), the left one. */
    static final int HELD = 0x4000, LEFT = 0x2000;

    /**
     * What Minecraft draws with its own model in the player's hands (a shield, its banner pattern
     * and its blocking pose included), as pieces the game puts in Steve's hands; lit where Mario is.
     */
    private void heldPieces(int cell, List<EntityWire.Piece> out) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        for (int hand = HeldItem.MAIN; hand <= HeldItem.OFF; hand++) {
            ItemStack stack = hand == HeldItem.MAIN ? player.getMainHandItem() : player.getOffhandItem();
            if (stack.isEmpty() || !HeldClient.drawnAsModel(stack)) continue;
            int from = out.size();
            try {
                var state = new net.minecraft.client.renderer.item.ItemStackRenderState();
                Minecraft.getInstance().getItemModelResolver().updateForLiving(state, stack,
                        hand == HeldItem.MAIN ? net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                                : net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND, player);
                galaxy = new Matrix4d(); // the hand frame as it is: the game puts it in his hand
                capture.begin(new Matrix4d(), out);
                state.submit(new PoseStack(), capture, EntityCapture.FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0);
            } catch (RuntimeException ex) {
                GalaxyCraft.LOG.warn("Not drawing {} in Steve's hand: {}", stack, ex.toString());
            } finally {
                galaxy = null;
            }
            int flags = HELD | (hand == HeldItem.OFF ? LEFT : 0);
            for (int i = from; i < out.size(); i++) {
                EntityWire.Piece p = out.get(i);
                out.set(i, new EntityWire.Piece(p.model() | flags, p.skin(), p.overlay(), p.tint(), p.mtx()));
                cellOf.put(i, cell);
            }
        }
    }

    /** /skin's skin as one of the game's textures (made again when it changes), -1 none. */
    private int customSkin() {
        SkinClient.Skin s = SkinClient.current();
        if (s == null) return -1;
        Integer id = skinIds.get(s);
        if (id == null) {
            id = addSkin(SkinImage.SIZE, SkinImage.SIZE, s.argb());
            skinIds.put(s, id);
        }
        return id;
    }
}
