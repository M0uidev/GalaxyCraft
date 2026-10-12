package dev.moui.galaxycraft.toad;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The Toads of one galaxy, kept in a JSON file next to its planets (like ShipStore). */
public final class ToadStore {
    private final Path file;
    private final List<ToadRecord> toads = new ArrayList<>();
    /** Planets whose outposts were already put (a removed Toad does not come back). */
    private final java.util.Set<String> placed = new java.util.LinkedHashSet<>();

    private ToadStore(Path file) {
        this.file = file;
    }

    public static ToadStore load(Path file) {
        ToadStore s = new ToadStore(file);
        try {
            if (!Files.exists(file)) return s;
            JsonElement root = JsonParser.parseString(Files.readString(file));
            JsonArray a;
            if (root.isJsonArray()) a = root.getAsJsonArray(); // before outposts: a plain list of Toads
            else {
                a = root.getAsJsonObject().getAsJsonArray("toads");
                if (root.getAsJsonObject().has("placed"))
                    for (JsonElement k : root.getAsJsonObject().getAsJsonArray("placed")) s.placed.add(k.getAsString());
            }
            for (JsonElement e : a) {
                JsonObject o = e.getAsJsonObject();
                ToadType type = null;
                for (ToadType t : ToadType.values())
                    if (t.id().equals(o.get("type").getAsString())) type = t;
                if (type == null) continue; // a type this version does not know is skipped
                JsonArray p = o.getAsJsonArray("pos");
                JsonArray q = o.has("rot") ? o.getAsJsonArray("rot") : null;
                double[] rot = q == null || q.size() != 4 ? new double[] {0, 0, 0, 1}
                        : new double[] {q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble(), q.get(3).getAsDouble()};
                JsonArray h = o.has("home") ? o.getAsJsonArray("home") : null;
                double[] home = h == null || h.size() != 3 ? null
                        : new double[] {h.get(0).getAsDouble(), h.get(1).getAsDouble(), h.get(2).getAsDouble()};
                s.toads.add(new ToadRecord(o.get("id").getAsString(), type,
                        new double[] {p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()},
                        rot, o.has("stage") ? o.get("stage").getAsString() : "", home));
            }
        } catch (IOException | RuntimeException e) {
            s.toads.clear(); // a damaged file loses its Toads, not the world
            s.placed.clear();
        }
        return s;
    }

    public List<ToadRecord> all() {
        return List.copyOf(toads);
    }

    /** Whether this planet's outposts were put already. */
    public boolean wasPlaced(String planetKey) {
        return placed.contains(planetKey);
    }

    public void markPlaced(String planetKey) {
        placed.add(planetKey);
    }

    public void put(ToadRecord r) {
        toads.removeIf(x -> x.id().equals(r.id()));
        toads.add(r);
    }

    public Optional<ToadRecord> remove(String id) {
        for (ToadRecord r : toads)
            if (r.id().equals(id)) {
                toads.remove(r);
                return Optional.of(r);
            }
        return Optional.empty();
    }

    public void save() throws IOException {
        JsonArray a = new JsonArray();
        for (ToadRecord r : toads) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("type", r.type().id());
            JsonArray p = new JsonArray();
            for (double v : r.pos()) p.add(v);
            o.add("pos", p);
            JsonArray q = new JsonArray();
            for (double v : r.rot()) q.add(v);
            o.add("rot", q);
            o.addProperty("stage", r.stage());
            if (r.home() != null) {
                JsonArray h = new JsonArray();
                for (double v : r.home()) h.add(v);
                o.add("home", h);
            }
            a.add(o);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        JsonObject root = new JsonObject();
        root.add("toads", a);
        JsonArray k = new JsonArray();
        for (String key : placed) k.add(key);
        root.add("placed", k);
        Files.writeString(file, root.toString());
    }
}
