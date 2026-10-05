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
     * pitch).
     */
    public record Spot(int planet, double dx, double dy, double dz, float yaw, float pitch) {
        boolean usable() {
            return planet >= 0 && Double.isFinite(dx + dy + dz) && dx * dx + dy * dy + dz * dz > 1e-12;
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

    public Optional<Spot> spot() {
        Path f = dir.resolve("player.json");
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
        Files.createDirectories(dir);
        Path tmp = dir.resolve("player.json.tmp");
        Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("player.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
