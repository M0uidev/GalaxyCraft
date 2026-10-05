package dev.moui.galaxycraft.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * GalaxyCraft's settings, in the order the settings screen shows them, kept in a properties file
 * (config/galaxycraft.properties): each change is written at once. A new option is one more
 * {@link #add}: the screen and the file pick it up by its kind.
 */
public final class Settings {
    private final Path file;
    private final List<Setting<?>> all = new ArrayList<>();
    private final Properties stored = new Properties();

    /** file: where they are kept (null: nowhere, for tests). */
    public Settings(Path file) {
        this.file = file;
        if (file == null || !Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stored.load(r);
        } catch (IOException | IllegalArgumentException e) {
            stored.clear(); // unreadable: the defaults, and the next change writes a good file
        }
    }

    /** Registers a setting, loaded from the file if it is there. */
    public <S extends Setting<?>> S add(S setting) {
        for (Setting<?> s : all)
            if (s.key().equals(setting.key())) throw new IllegalArgumentException("Two settings named " + setting.key());
        setting.load(stored.getProperty(setting.key()));
        setting.whenChanged(this::save);
        all.add(setting);
        return setting;
    }

    public List<Setting<?>> all() {
        return Collections.unmodifiableList(all);
    }

    /** Writes every setting (a failure leaves the old file: the settings stay as set this session). */
    public void save() {
        if (file == null) return;
        Properties out = new Properties();
        out.putAll(stored); // settings this version no longer has stay for the one that does
        for (Setting<?> s : all) out.setProperty(s.key(), s.encode());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                out.store(w, "GalaxyCraft settings");
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("GalaxyCraft: could not save the settings: " + e);
        }
    }
}
