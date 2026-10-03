package dev.moui.galaxycraft.voxel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.joml.Vector3d;

/** Planets on disk: one gzip file per stage (galaxy), written whole and atomically. */
public final class PlanetStore {
    private static final int MAGIC = 0x47585031; // "GXP1"

    /** A planet as saved: its grid, crust depth, center (galaxy units) and cells. */
    public record Saved(int n, double core, int layers, int depth, Vector3d center, byte[] cells) {}

    private final Path dir;

    public PlanetStore(Path dir) {
        this.dir = dir;
    }

    public Path file(String stage) {
        return dir.resolve(stage.replaceAll("[^A-Za-z0-9_.-]", "_") + ".gxplanet");
    }

    public void write(String stage, Saved s) throws IOException {
        Files.createDirectories(dir);
        Path tmp = file(stage).resolveSibling(file(stage).getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(s.n());
            out.writeDouble(s.core());
            out.writeInt(s.layers());
            out.writeInt(s.depth());
            out.writeDouble(s.center().x);
            out.writeDouble(s.center().y);
            out.writeDouble(s.center().z);
            out.writeInt(s.cells().length);
            out.write(s.cells());
        }
        Files.move(tmp, file(stage), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The stage's planet, if one was saved and the file is sound. */
    public Optional<Saved> read(String stage) throws IOException {
        Path f = file(stage);
        if (!Files.exists(f)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
            if (in.readInt() != MAGIC) throw new IOException(f + ": not a GalaxyCraft planet");
            int n = in.readInt();
            double core = in.readDouble();
            int layers = in.readInt(), depth = in.readInt();
            Vector3d center = new Vector3d(in.readDouble(), in.readDouble(), in.readDouble());
            int len = in.readInt();
            if (n <= 0 || layers <= 0 || len != 6L * n * n * layers) throw new IOException(f + ": bad size");
            byte[] cells = in.readNBytes(len);
            if (cells.length != len) throw new IOException(f + ": truncated");
            return Optional.of(new Saved(n, core, layers, depth, center, cells));
        }
    }

    public void delete(String stage) throws IOException {
        Files.deleteIfExists(file(stage));
    }
}
