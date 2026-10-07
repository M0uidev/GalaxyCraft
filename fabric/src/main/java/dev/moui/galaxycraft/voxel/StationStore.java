package dev.moui.galaxycraft.voxel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A world's stations on disk, one gzip file each by id (<id>.gxstation), written whole and
 * atomically. GXS1: id, name, placed or packed (and the stage), center, rotation, bounds, grid
 * size, block count (so a listing needs no cells), then the cells as PlanetStore writes them.
 */
public final class StationStore {
    private static final int MAGIC = 0x47585331; // "GXS1"
    private static final String EXT = ".gxstation";

    /** A station's file without its cells: enough to list it and to know whether it is near. */
    public record Header(String id, String name, String stage, Vector3d center, Quaterniond rotation, int blocks, int spanX,
            int spanY, int spanZ) {}

    private final Path dir;

    public StationStore(Path dir) {
        this.dir = dir;
    }

    public Path file(String id) {
        return dir.resolve(id.replaceAll("[^A-Za-z0-9_-]", "_") + EXT);
    }

    public void write(Station s, Blocks blocks) throws IOException {
        Files.createDirectories(dir);
        Path f = file(s.id), tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeUTF(s.id);
            out.writeUTF(s.name);
            out.writeBoolean(s.stage != null);
            if (s.stage != null) out.writeUTF(s.stage);
            out.writeDouble(s.center.x);
            out.writeDouble(s.center.y);
            out.writeDouble(s.center.z);
            out.writeDouble(s.rotation.x);
            out.writeDouble(s.rotation.y);
            out.writeDouble(s.rotation.z);
            out.writeDouble(s.rotation.w);
            StationShape.Bounds b = s.bounds;
            for (int v : new int[] {b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1()}) out.writeInt(v);
            FlatGrid g = s.grid();
            for (int v : new int[] {g.n, g.layers, g.ox, g.oy, g.oz}) out.writeInt(v);
            out.writeInt(s.blockCount());
            PlanetStore.writeCells(out, s.planet.cells(), blocks);
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private record Head(Header header, StationShape.Bounds bounds, StationShape.Size size) {}

    private static Head head(DataInputStream in, Path f) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException(f + ": not a station");
        String id = in.readUTF(), name = in.readUTF();
        String stage = in.readBoolean() ? in.readUTF() : null;
        Vector3d center = new Vector3d(in.readDouble(), in.readDouble(), in.readDouble());
        Quaterniond rotation = new Quaterniond(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
        StationShape.Bounds b = new StationShape.Bounds(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        StationShape.Size size = new StationShape.Size(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        if (size.n() <= 0 || size.n() > 1024 || size.layers() <= 0 || size.layers() > 256) throw new IOException(f + ": bad size");
        int blocks = in.readInt();
        return new Head(new Header(id, name, stage, center, rotation, blocks, b.spanX(), b.spanY(), b.spanZ()), b, size);
    }

    public Station read(String id, Blocks blocks) throws IOException {
        return read(id, blocks, blocks::parse);
    }

    /** As {@link #read(String, Blocks)}, a block's text turned into its id by parse (another thread's lookup). */
    public Station read(String id, Blocks blocks, java.util.function.ToIntFunction<String> parse) throws IOException {
        Path f = file(id);
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
            Head h = head(in, f);
            FlatGrid g = Station.grid(h.size(), h.header().rotation());
            char[] cells = PlanetStore.readCells(in, g.cellCount(), parse);
            Station s = new Station(h.header().id(), h.header().name(), h.header().rotation(), VoxelPlanet.flat(g, cells, blocks), h.bounds());
            s.stage = h.header().stage();
            s.center = h.header().center();
            return s;
        }
    }

    /** Every station's header, by id; files that cannot be read are left out (and logged). */
    public List<Header> list() {
        List<Header> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(EXT)).sorted().toList()) {
                try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
                    out.add(head(in, f).header());
                } catch (IOException e) {
                    dev.moui.galaxycraft.GalaxyCraft.LOG.warn("Station file {} could not be read: {}", f, e.toString());
                }
            }
        } catch (IOException e) {
            dev.moui.galaxycraft.GalaxyCraft.LOG.warn("Stations in {} could not be listed: {}", dir, e.toString());
        }
        return out;
    }
}
