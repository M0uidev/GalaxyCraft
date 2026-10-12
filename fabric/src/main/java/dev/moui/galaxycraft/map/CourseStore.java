package dev.moui.galaxycraft.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.moui.galaxycraft.universe.UPos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The trips of one world's ships by ship id, in a JSON file next to its ships.json. A damaged file loses its trips, not the world. */
public final class CourseStore {
    private final Path file;
    private final Map<String, Trip> trips = new LinkedHashMap<>();

    private CourseStore(Path file) {
        this.file = file;
    }

    public static CourseStore load(Path file) {
        CourseStore s = new CourseStore(file);
        try {
            if (!Files.exists(file)) return s;
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<String, Trip> read = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                JsonObject t = e.getValue().getAsJsonObject();
                JsonObject c = t.getAsJsonObject("course"), r = t.getAsJsonObject("orbit");
                Course course = new Course(pos(c.getAsJsonArray("from")), pos(c.getAsJsonArray("to")), c.get("start").getAsLong(),
                        c.get("speed").getAsDouble());
                Orbit orbit = new Orbit(pos(r.getAsJsonArray("center")), r.get("radius").getAsDouble(), r.get("phase").getAsDouble(),
                        r.get("rate").getAsDouble(), r.get("epoch").getAsLong());
                read.put(e.getKey(), new Trip(course, orbit));
            }
            s.trips.putAll(read);
        } catch (IOException | RuntimeException e) {
            s.trips.clear();
        }
        return s;
    }

    public Optional<Trip> get(String id) {
        return Optional.ofNullable(trips.get(id));
    }

    public void put(String id, Trip t) {
        trips.put(id, t);
    }

    public Optional<Trip> remove(String id) {
        return Optional.ofNullable(trips.remove(id));
    }

    public Map<String, Trip> all() {
        return Map.copyOf(trips);
    }

    /** How many trips end orbiting this center (to give each ship its own phase). */
    public int orbitingAround(UPos center) {
        int n = 0;
        for (Trip t : trips.values()) if (t.orbit().center().equals(center)) n++;
        return n;
    }

    public void save() throws IOException {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Trip> e : trips.entrySet()) {
            Trip t = e.getValue();
            JsonObject c = new JsonObject(), r = new JsonObject(), j = new JsonObject();
            c.add("from", arr(t.course().from()));
            c.add("to", arr(t.course().to()));
            c.addProperty("start", t.course().startTick());
            c.addProperty("speed", t.course().speed());
            r.add("center", arr(t.orbit().center()));
            r.addProperty("radius", t.orbit().radius());
            r.addProperty("phase", t.orbit().phase());
            r.addProperty("rate", t.orbit().rate());
            r.addProperty("epoch", t.orbit().epochTick());
            j.add("course", c);
            j.add("orbit", r);
            o.add(e.getKey(), j);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, o.toString());
    }

    private static JsonArray arr(UPos p) {
        JsonArray a = new JsonArray();
        a.add(p.cx());
        a.add(p.cy());
        a.add(p.cz());
        a.add(p.x());
        a.add(p.y());
        a.add(p.z());
        return a;
    }

    private static UPos pos(JsonArray a) {
        if (a.size() != 6) throw new IllegalArgumentException("position needs 6 numbers");
        return new UPos(a.get(0).getAsLong(), a.get(1).getAsLong(), a.get(2).getAsLong(), a.get(3).getAsDouble(),
                a.get(4).getAsDouble(), a.get(5).getAsDouble());
    }
}
