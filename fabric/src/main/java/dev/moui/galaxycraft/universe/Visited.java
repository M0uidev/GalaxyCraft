package dev.moui.galaxycraft.universe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** The systems this galaxy's player has been to, kept in a JSON file next to its planets. Home always counts. */
public final class Visited {
    private final Path file;
    private final Set<Universe.Sector> sectors = new LinkedHashSet<>();

    private Visited(Path file) {
        this.file = file;
    }

    public static Visited load(Path file) {
        Visited v = new Visited(file);
        try {
            if (Files.exists(file)) {
                for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray())
                    SystemIndex.parse(e.getAsString()).ifPresent(v.sectors::add);
            }
        } catch (Exception e) {
            v.sectors.clear(); // a corrupt file never crashes the game: nothing visited but home
        }
        return v;
    }

    public boolean has(Universe.Sector s) {
        return s.equals(Universe.Sector.HOME) || sectors.contains(s);
    }

    /** True when the sector was not visited before. */
    public boolean mark(Universe.Sector s) {
        return !s.equals(Universe.Sector.HOME) && sectors.add(s);
    }

    public void save() throws IOException {
        JsonArray a = new JsonArray();
        for (Universe.Sector s : sectors) a.add(SystemIndex.name(s));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, a.toString());
    }
}
