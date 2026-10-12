package dev.moui.galaxycraft.toad;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.ship.model.BckReader;
import dev.moui.galaxycraft.ship.model.BdlReader;
import dev.moui.galaxycraft.ship.model.Rarc;
import dev.moui.galaxycraft.ship.model.ShipModels;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The Toad's model: SMG2's own Kinopio (Kinopio.arc, copied by the launcher from the player's disc
 * next to the ship's) as rigid pieces per joint, in pixels (16 a block) scaled so it is
 * {@link #HEIGHT_PX} tall, with the arc's animations (Wait.bck, Talk.bck...). A box of the same size
 * on one joint stands in where the model is missing or unreadable, so the same code draws both.
 * Pieces are in their joint's space: a frame's matrix of a piece is {@link #pieceMatrix}.
 */
public final class ToadModels {
    /** The Toad's height in pixels (16 a block): a block and a half. */
    public static final float HEIGHT_PX = 24f;
    /** In Kinopio.arc (fixed by the disc): the cap's texture on the head joint and the vest's flat ones. */
    public static final int CAP_TEXTURE = 8, HEAD_JOINT = 8;
    public static final int[] VEST_TEXTURES = {5, 7};
    /** The stand-in's tint, RGBA. */
    private static final int BOX_TINT = 0xE8B040FF;
    private static final String ARC = "Kinopio.arc";

    /** One piece: the joint it hangs on, the texture it came with (a Kinopio.arc texture index) and its quads. */
    public record RigPart(int joint, int texture, ShipModels.PartModel part) {}

    /** The loaded model. */
    public record Loaded(int[] parent, float[][] rest, List<RigPart> parts, float fit, float[] origin, Map<String, BckReader.Anim> anims) {}

    private static volatile Loaded loaded;
    private static volatile Path dir;
    private static final Map<String, List<ShipModels.PartModel>> GOODS = new ConcurrentHashMap<>();

    private ToadModels() {}

    /** Reads Kinopio.arc from the launcher's ship directory; anything missing is a box. */
    public static synchronized void load(Path d) {
        dir = d;
        GOODS.clear();
        BdlReader.Rig rig = null;
        Map<String, BckReader.Anim> anims = new HashMap<>();
        try {
            Path f = d.resolve(ARC);
            if (Files.isRegularFile(f)) {
                Map<String, byte[]> files = new TreeMap<>(Rarc.read(Files.readAllBytes(f)));
                byte[] bdl = files.entrySet().stream()
                        .filter(e -> e.getKey().endsWith(".bdl") || e.getKey().endsWith(".bmd"))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                if (bdl != null) rig = BdlReader.readRig(bdl);
                for (var e : files.entrySet())
                    if (e.getKey().endsWith(".bck"))
                        try {
                            anims.put(e.getKey().substring(0, e.getKey().length() - 4), BckReader.read(e.getValue()));
                        } catch (IllegalArgumentException x) {
                            GalaxyCraft.LOG.warn("The Toad's {} could not be read ({})", e.getKey(), x.getMessage());
                        }
            }
        } catch (IOException | IllegalArgumentException e) {
            GalaxyCraft.LOG.warn("The Toad's {} could not be read ({}); a box stands in", ARC, e.getMessage());
            rig = null;
        }
        use(rig, anims);
    }

    /** Uses a rig and its animations (null rig: the box). Tests. */
    static synchronized void use(BdlReader.Rig rig, Map<String, BckReader.Anim> anims) {
        Loaded l = rig == null ? null : build(rig, anims);
        loaded = l != null ? l : box();
    }

    public static Loaded get() {
        Loaded l = loaded;
        if (l == null) {
            use(null, Map.of());
            l = loaded;
        }
        return l;
    }

    private static Loaded build(BdlReader.Rig rig, Map<String, BckReader.Anim> anims) {
        if (rig.parts().isEmpty()) return null;
        Matrix4f[] w = ToadPose.worlds(rig.parent(), rig.rest());
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        Vector3f v = new Vector3f();
        for (BdlReader.Part p : rig.parts())
            for (BdlReader.Tri t : p.tris())
                for (float[] c : t.pos()) {
                    w[p.joint()].transformPosition(v.set(c[0], c[1], c[2]));
                    float[] a = {v.x, v.y, v.z};
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], a[i]);
                        hi[i] = Math.max(hi[i], a[i]);
                    }
                }
        if (!(hi[1] > lo[1])) return null;
        float fit = HEIGHT_PX / (hi[1] - lo[1]);
        List<RigPart> parts = new ArrayList<>();
        for (BdlReader.Part p : rig.parts()) {
            BdlReader.Texture tex = p.texture() >= 0 && p.texture() < rig.textures().size() ? rig.textures().get(p.texture()) : null;
            ShipModels.Skin skin = tex == null ? new ShipModels.Skin(4, 4, whites()) : new ShipModels.Skin(tex.width(), tex.height(), tex.argb());
            for (int from = 0; from < p.tris().size(); from += ShipModels.MAX_QUADS) {
                var chunk = p.tris().subList(from, Math.min(p.tris().size(), from + ShipModels.MAX_QUADS));
                parts.add(new RigPart(p.joint(), p.texture(), new ShipModels.PartModel(ShipModels.quads(chunk, fit), skin, 0xFFFFFFFF)));
            }
        }
        float[] origin = {(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2};
        return new Loaded(rig.parent(), rig.rest(), parts, fit, origin, Map.copyOf(anims));
    }

    private static int[] whites() {
        int[] white = new int[16];
        java.util.Arrays.fill(white, 0xFFFFFFFF);
        return white;
    }

    /** A box as wide as a Toad (a third of its height), one joint, no animations. */
    private static Loaded box() {
        float w = 1f / 3, h = 1f;
        float[][] c = new float[8][];
        for (int i = 0; i < 8; i++) c[i] = new float[] {(i & 1) == 0 ? -w : w, (i & 2) == 0 ? 0 : h, (i & 4) == 0 ? -w : w};
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}};
        List<BdlReader.Tri> tris = new ArrayList<>();
        for (int[] f : faces) {
            tris.add(new BdlReader.Tri(new float[][] {c[f[0]], c[f[1]], c[f[2]]}, uv));
            tris.add(new BdlReader.Tri(new float[][] {c[f[0]], c[f[2]], c[f[3]]}, uv));
        }
        float fit = HEIGHT_PX / h;
        ShipModels.PartModel part = new ShipModels.PartModel(ShipModels.quads(tris, fit), new ShipModels.Skin(4, 4, whites()), BOX_TINT);
        return new Loaded(new int[] {-1}, new float[][] {{1, 1, 1, 0, 0, 0, 0, 0, 0}},
                List.of(new RigPart(0, -1, part)), fit, new float[] {0, 0, 0}, Map.of());
    }

    /**
     * The matrix of a piece of joint j with world matrix w (model units, from {@link ToadPose}): pixels
     * to pixels, with the feet at the origin, the Toad's up +y.
     */
    public static Matrix4f pieceMatrix(Loaded l, Matrix4f jointWorld) {
        return new Matrix4f()
                .translate(-l.origin()[0] * l.fit(), -l.origin()[1] * l.fit(), -l.origin()[2] * l.fit())
                .scale(l.fit()).mul(jointWorld).scale(1f / l.fit());
    }

    /**
     * What a Toad carries (a KinopioGoods arc in the ship directory), in the model's scale and the
     * space of the joint it hangs on; empty if the arc is not there or unreadable.
     */
    public static List<ShipModels.PartModel> goods(String arc) {
        return GOODS.computeIfAbsent(arc, a -> {
            Path d = dir;
            if (d == null) return List.of();
            try {
                Path f = d.resolve(a + ".arc");
                if (!Files.isRegularFile(f)) return List.of();
                Map<String, byte[]> files = new TreeMap<>(Rarc.read(Files.readAllBytes(f)));
                byte[] bdl = files.entrySet().stream().filter(e -> e.getKey().endsWith(".bdl") || e.getKey().endsWith(".bmd"))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                if (bdl == null) return List.of();
                BdlReader.Model m = BdlReader.read(bdl);
                float fit = get().fit();
                List<ShipModels.PartModel> out = new ArrayList<>();
                for (BdlReader.Shape s : m.shapes()) {
                    BdlReader.Texture t = s.texture() >= 0 && s.texture() < m.textures().size() ? m.textures().get(s.texture()) : null;
                    ShipModels.Skin skin = t == null ? new ShipModels.Skin(4, 4, whites()) : new ShipModels.Skin(t.width(), t.height(), t.argb());
                    for (int from = 0; from < s.tris().size(); from += ShipModels.MAX_QUADS) {
                        var chunk = s.tris().subList(from, Math.min(s.tris().size(), from + ShipModels.MAX_QUADS));
                        out.add(new ShipModels.PartModel(ShipModels.quads(chunk, fit), skin, 0xFFFFFFFF));
                    }
                }
                return out;
            } catch (IOException | IllegalArgumentException e) {
                GalaxyCraft.LOG.warn("The Toad's {} could not be read ({})", a, e.getMessage());
                return List.of();
            }
        });
    }
}
