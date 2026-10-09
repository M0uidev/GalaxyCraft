package dev.moui.galaxycraft.voxel;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * A Minecraft world's galaxy, in the world's folder (saves/&lt;world&gt;/galaxycraft): its planets
 * ({@link PlanetStore}'s files) and where the player stands, kept apart from any other world's.
 */
public final class GalaxySave {
    private static final Gson GSON = new Gson();
    private final Path dir;

    /**
     * Where the player stands: on the planet of that file index (PlanetStore.key), in the direction
     * (dx, dy, dz) from its center (galaxy axes, any length), looking that way (Minecraft's yaw and
     * pitch). system: null for the world's own galaxy; else a generated system's sector
     * (SystemIndex.name), and planet is n in it. station: the id of a station the player stands
     * on, instead of a planet; (dx, dy, dz) is then where, in the station's own space (blocks).
     */
    public record Spot(int planet, double dx, double dy, double dz, float yaw, float pitch, String system, String station) {
        public Spot(int planet, double dx, double dy, double dz, float yaw, float pitch) {
            this(planet, dx, dy, dz, yaw, pitch, null, null);
        }

        public Spot(int planet, double dx, double dy, double dz, float yaw, float pitch, String system) {
            this(planet, dx, dy, dz, yaw, pitch, system, null);
        }

        /** On a station: where the player stands in its space (blocks), looking yaw and pitch. */
        public static Spot onStation(String id, double x, double y, double z, float yaw, float pitch) {
            return new Spot(-1, x, y, z, yaw, pitch, null, id);
        }

        boolean usable() {
            return (planet >= 0 || station != null && !station.isBlank()) && Double.isFinite(dx + dy + dz)
                    && (station != null || dx * dx + dy * dy + dz * dz > 1e-12);
        }
    }

    private GalaxySave(Path dir) {
        this.dir = dir;
    }

    public static GalaxySave of(Path worldDir) {
        return new GalaxySave(worldDir.resolve("galaxycraft"));
    }

    public Path planets() {
        return dir.resolve("planets");
    }

    /** The world has never been entered with GalaxyCraft: no planet, no starter kit given yet. */
    public boolean isNew() {
        return !Files.isDirectory(dir);
    }

    public void markMade() throws IOException {
        Files.createDirectories(planets());
    }

    /** The world's planets: the options it was made with and the catalog (galaxy.json). */
    public record Galaxy(int version, GalaxyCatalog.Options options, java.util.List<GalaxyCatalog.Entry> entries) {
        /** Its world layout (GalaxyCatalog.LAYOUT): saves from before layouts (version 0 or 1) are 1. */
        public int layout() {
            return Math.max(1, version);
        }
    }

    public Optional<Galaxy> galaxy() {
        Path f = dir.resolve("galaxy.json");
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            Galaxy g = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Galaxy.class);
            return g != null && g.options() != null && g.entries() != null && !g.entries().isEmpty() ? Optional.of(g) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Written whole and atomically, as the spot. */
    public void writeGalaxy(Galaxy g) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve("galaxy.json.tmp");
        Files.writeString(tmp, GSON.toJson(g), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("galaxy.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The catalog of a world made before catalogs: one entry per planet file of the stage, where it
     * was and as big as it is (no blueprint: the file is all there is of it).
     */
    public Galaxy fromFiles(PlanetStore store, String stage) {
        java.util.List<GalaxyCatalog.Entry> entries = new java.util.ArrayList<>();
        for (int index : store.saved(stage, GalaxyCatalog.MAX)) {
            try {
                Optional<PlanetStore.Header> h = store.header(PlanetStore.key(stage, index));
                if (h.isEmpty()) continue;
                org.joml.Vector3d c = h.get().center();
                entries.add(new GalaxyCatalog.Entry(index, c.x, c.y, c.z, (int) Math.round(h.get().surface()),
                        GalaxyCatalog.Kind.BLUEPRINT, null, null, 0));
            } catch (IOException e) {
                // An unreadable file stays out; PlanetClient says so when it fails to load it.
            }
        }
        GalaxyCatalog.Options d = GalaxyCatalog.Options.defaults(0);
        GalaxyCatalog.Options o = new GalaxyCatalog.Options(Math.max(1, entries.size()), d.minRadius(), d.maxRadius(), d.first(),
                d.spacing(), 0);
        return new Galaxy(1, o, java.util.List.copyOf(entries));
    }

    public Optional<Spot> spot() {
        return read("player.json");
    }

    /** Where the player comes back after dying: the last bed slept in (its top), if any. */
    public Optional<Spot> bed() {
        return read("bed.json");
    }

    public void writeBed(Spot s) throws IOException {
        write("bed.json", s);
    }

    /** The bed is gone: the player comes back where they last stood. */
    public void clearBed() throws IOException {
        Files.deleteIfExists(dir.resolve("bed.json"));
    }

    private Optional<Spot> read(String name) {
        Path f = dir.resolve(name);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            Spot s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Spot.class);
            return s != null && s.usable() ? Optional.of(s) : Optional.empty();
        } catch (IOException | JsonParseException e) {
            return Optional.empty();
        }
    }

    /** Written whole and atomically: a crash midway leaves the last spot. */
    public void writeSpot(Spot s) throws IOException {
        write("player.json", s);
    }

    private void write(String name, Spot s) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".tmp");
        Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
