package dev.moui.galaxycraft.voxel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Planet blueprints on disk: one JSON file per name, written whole and atomically. Beside them, the
 * last one the editor had open (not one of the saved ones), so it opens on it again.
 */
public final class BlueprintStore {
    private static final String EXT = ".json";
    private final Path dir;

    public BlueprintStore(Path dir) {
        this.dir = dir;
    }

    public Path file(String name) {
        return dir.resolve(name.replaceAll("[^A-Za-z0-9_.-]", "_") + EXT);
    }

    /** The names of the saved blueprints, sorted. */
    public List<String> list() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(EXT))
                    .map(f -> f.substring(0, f.length() - EXT.length())).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }

    public void write(PlanetBlueprint b) throws IOException {
        write(file(b.name()), b);
    }

    private Path last() {
        return dir.resolveSibling("last-blueprint" + EXT);
    }

    public void writeLast(PlanetBlueprint b) throws IOException {
        write(last(), b);
    }

    /** The last one the editor had open; empty if none or unreadable (it is only a convenience). */
    public Optional<PlanetBlueprint> readLast() {
        try {
            return Files.exists(last()) ? Optional.of(PlanetBlueprint.fromJson(Files.readString(last(), StandardCharsets.UTF_8))) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void write(Path f, PlanetBlueprint b) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, b.toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Optional<PlanetBlueprint> read(String name) throws IOException {
        Path f = file(name);
        if (!Files.exists(f)) return Optional.empty();
        try {
            return Optional.of(PlanetBlueprint.fromJson(Files.readString(f, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new IOException(f + ": " + e.getMessage(), e);
        }
    }

    public void delete(String name) throws IOException {
        Files.deleteIfExists(file(name));
    }
}
