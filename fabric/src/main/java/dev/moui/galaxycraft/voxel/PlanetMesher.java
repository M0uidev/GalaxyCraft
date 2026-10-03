package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.geom.Tri;
import dev.moui.galaxycraft.kcl.KclWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/**
 * Turns a chunk into what SMG2 needs: a GX display list (quads in vertex format 7: position f32,
 * color RGBA8, texture coordinate u8 with 2 fraction bits) and a KCL. Only sides facing air are
 * kept. Positions are in galaxy units relative to the planet's center.
 */
public final class PlanetMesher {
    /** GX_QUADS | GX_VTXFMT7. */
    public static final int GX_QUADS_FMT7 = 0x80 | 7;
    public static final int VERTEX_BYTES = 12 + 4 + 2;
    /** Minecraft's face shading: top, bottom, then the two pairs of sides. */
    private static final int[] SHADE = {255, 128, 204, 204, 153, 153};
    private static final int ATLAS_TILES = 4;

    public record Quad(Vector3d[] corners, int tile, int side) {}

    public record ChunkMesh(byte[] displayList, byte[] kcl) {
        public boolean empty() {
            return displayList.length == 0;
        }
    }

    private PlanetMesher() {}

    /** The visible sides of a chunk, corners in blocks (see {@link CubeSphere#side}). */
    public static List<Quad> quads(VoxelPlanet p, int chunk) {
        List<Quad> out = new ArrayList<>();
        for (int c : p.cellsOf(chunk)) {
            Material m = p.get(c);
            if (!m.solid()) continue;
            for (int s = 0; s < 6; s++) {
                int nb = p.grid.neighbor(c, s);
                if (s == CubeSphere.BOTTOM && nb < 0) continue; // faces the sealed center
                if (p.get(nb).solid()) continue;
                int tile = s == CubeSphere.TOP ? m.top : s == CubeSphere.BOTTOM ? m.bottom : m.side;
                out.add(new Quad(p.grid.side(c, s), tile, s));
            }
        }
        return out;
    }

    public static ChunkMesh mesh(VoxelPlanet p, int chunk, double unitsPerBlock) {
        List<Quad> quads = quads(p, chunk);
        if (quads.isEmpty()) return new ChunkMesh(new byte[0], new byte[0]);
        if (quads.size() * 4 > 0xFFFF) throw new IllegalStateException("chunk too detailed for one draw");
        int size = 3 + quads.size() * 4 * VERTEX_BYTES;
        ByteBuffer dl = ByteBuffer.allocate((size + 31) & ~31).order(ByteOrder.BIG_ENDIAN);
        dl.put((byte) GX_QUADS_FMT7).putShort((short) (quads.size() * 4));
        List<Tri> tris = new ArrayList<>();
        for (Quad q : quads) {
            Vector3d[] v = new Vector3d[4];
            for (int k = 0; k < 4; k++) v[k] = new Vector3d(q.corners()[k]).mul(unitsPerBlock);
            int tx = q.tile() % ATLAS_TILES, ty = q.tile() / ATLAS_TILES, shade = SHADE[q.side()];
            // q[0], q[1] bottom edge (v = 1), q[2], q[3] top edge (v = 0), left to right then back.
            int[][] st = {{tx, ty + 1}, {tx + 1, ty + 1}, {tx + 1, ty}, {tx, ty}};
            for (int k = 0; k < 4; k++) {
                dl.putFloat((float) v[k].x).putFloat((float) v[k].y).putFloat((float) v[k].z);
                dl.put((byte) shade).put((byte) shade).put((byte) shade).put((byte) 255);
                dl.put((byte) st[k][0]).put((byte) st[k][1]);
            }
            tris.add(Tri.of(v[0], v[1], v[2]));
            tris.add(Tri.of(v[0], v[2], v[3]));
        }
        return new ChunkMesh(dl.array(), KclWriter.write(tris));
    }
}
