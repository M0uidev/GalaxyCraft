package dev.moui.galaxycraft.music;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** The music each station plays while the player is on it: station id to a {@link Want}. */
public final class StationMusic {
    private final Path file;
    private final Properties map = new Properties();

    public StationMusic(Path file) {
        this.file = file;
        if (file == null || !Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            map.load(r);
        } catch (IOException | IllegalArgumentException e) {
            map.clear();
        }
    }

    public Want get(String stationId) {
        return Want.decode(map.getProperty(stationId));
    }

    public void set(String stationId, Want w) {
        if (w.equals(Want.SPACE)) map.remove(stationId);
        else map.setProperty(stationId, w.encode());
        save();
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                map.store(w, "Station music");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("GalaxyCraft: could not save station music: " + e);
        }
    }
}
