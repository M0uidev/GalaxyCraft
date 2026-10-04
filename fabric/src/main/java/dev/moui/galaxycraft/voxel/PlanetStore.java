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

/**
 * Planets on disk: one gzip file per stage (galaxy), written whole and atomically. GXP2: the grid,
 * crust depth and center, a palette of the block states used (Minecraft's text for them) and the
 * cells as indexes into it, so that saves outlive the game's block ids. GXP1 planets (a cell was a
 * {@link Material} ordinal with a fluid's level in the high nibble) are read too.
 */
public final class PlanetStore {
    private static final int MAGIC_V1 = 0x47585031, MAGIC = 0x47585032; // "GXP1", "GXP2"

    /** A planet as saved: its grid, crust depth, center (galaxy units) and cells (block ids). */
    public record Saved(int n, double core, int layers, int depth, Vector3d center, char[] cells, PlanetBiomes biomes) {
        public Saved(int n, double core, int layers, int depth, Vector3d center, char[] cells) {
            this(n, core, layers, depth, center, cells, null);
        }
    }

    /** After the cells, optional ("BIOM"): the biomes' names and, for more than one, a byte per column. */
    private static final int BIOMES = 0x42494F4D;

    private final Path dir;

    public PlanetStore(Path dir) {
        this.dir = dir;
    }

    public Path file(String stage) {
        return dir.resolve(stage.replaceAll("[^A-Za-z0-9_.-]", "_") + ".gxplanet");
    }

    /**
     * A stage's planets each have a file: the first (index 0) the one a stage always had, the others
     * <stage>.p<index>.gxplanet. Stage names are the game's (letters and digits), so no stage's
     * first file ends like another's.
     */
    public static String key(String stage, int index) {
        return index == 0 ? stage : stage + ".p" + index;
    }

    /** The indexes (below max) of a stage's planets on disk, in order. */
    public java.util.List<Integer> saved(String stage, int max) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < max; i++) if (Files.isRegularFile(file(key(stage, i)))) out.add(i);
        return out;
    }

    public void write(String stage, Saved s, Blocks blocks) throws IOException {
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
            // The palette: each id used, in order of first appearance.
            java.util.Map<Character, Character> index = new java.util.HashMap<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            char[] cells = s.cells();
            byte[] packed = new byte[2 * cells.length];
            for (int c = 0; c < cells.length; c++) {
                Character i = index.get(cells[c]);
                if (i == null) {
                    i = (char) names.size();
                    index.put(cells[c], i);
                    names.add(blocks.name(cells[c]));
                }
                packed[2 * c] = (byte) (i >> 8);
                packed[2 * c + 1] = (byte) (char) i;
            }
            out.writeInt(names.size());
            for (String name : names) out.writeUTF(name);
            out.writeInt(cells.length);
            out.write(packed);
            if (s.biomes() != null) {
                out.writeInt(BIOMES);
                out.writeInt(s.biomes().names().size());
                for (String name : s.biomes().names()) out.writeUTF(name);
                if (!s.biomes().uniform()) out.write(s.biomes().columns());
            }
        }
        Files.move(tmp, file(stage), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The stage's planet in blocks' ids, if one was saved and the file is sound. */
    public Optional<Saved> read(String stage, Blocks blocks) throws IOException {
        Path f = file(stage);
        if (!Files.exists(f)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
            int magic = in.readInt();
            if (magic != MAGIC && magic != MAGIC_V1) throw new IOException(f + ": not a GalaxyCraft planet");
            int n = in.readInt();
            double core = in.readDouble();
            int layers = in.readInt(), depth = in.readInt();
            Vector3d center = new Vector3d(in.readDouble(), in.readDouble(), in.readDouble());
            int[] palette = magic == MAGIC ? palette(in, blocks) : v1Palette(blocks);
            int len = in.readInt();
            if (n <= 0 || layers <= 0 || len != 6L * n * n * layers) throw new IOException(f + ": bad size");
            byte[] raw = in.readNBytes(magic == MAGIC ? 2 * len : len);
            if (raw.length != (magic == MAGIC ? 2 * len : len)) throw new IOException(f + ": truncated");
            char[] cells = new char[len];
            for (int c = 0; c < len; c++) {
                int i = magic == MAGIC ? (raw[2 * c] & 0xFF) << 8 | raw[2 * c + 1] & 0xFF : raw[c] & 0xFF;
                if (i >= palette.length) throw new IOException(f + ": bad cell");
                cells[c] = (char) palette[i];
            }
            return Optional.of(new Saved(n, core, layers, depth, center, cells, biomes(in, 6 * n * n)));
        }
    }

    /** The biomes after the cells; null for a planet saved before planets had them. */
    private static PlanetBiomes biomes(DataInputStream in, int columns) throws IOException {
        int magic;
        try {
            magic = in.readInt();
        } catch (java.io.EOFException e) {
            return null;
        }
        if (magic != BIOMES) throw new IOException("bad biomes");
        int count = in.readInt();
        if (count < 1 || count > 256) throw new IOException("bad biomes");
        String[] names = new String[count];
        for (int i = 0; i < count; i++) names[i] = in.readUTF();
        byte[] cols = count == 1 ? null : in.readNBytes(columns);
        if (cols != null && cols.length != columns) throw new IOException("truncated biomes");
        try {
            return PlanetBiomes.of(names, cols);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static int[] palette(DataInputStream in, Blocks blocks) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 65536) throw new IOException("bad palette");
        int[] ids = new int[size];
        for (int i = 0; i < size; i++) ids[i] = blocks.parse(in.readUTF());
        return ids;
    }

    /** GXP1: a byte was a Material ordinal (low nibble) and a fluid's level (high nibble). */
    private static int[] v1Palette(Blocks blocks) {
        Material[] all = Material.values();
        int[] ids = new int[256];
        for (int b = 0; b < 256; b++) {
            int m = b & 0x0F, level = b >> 4;
            ids[b] = m >= all.length ? Blocks.AIR
                    : all[m].fluid() ? blocks.fluidState(all[m].fluidKind(), level) : blocks.id(all[m]);
        }
        return ids;
    }

    public void delete(String stage) throws IOException {
        Files.deleteIfExists(file(stage));
    }
}
