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

    private ToadStore(Path file) {
        this.file = file;
    }

    public static ToadStore load(Path file) {
        ToadStore s = new ToadStore(file);
        try {
            if (!Files.exists(file)) return s;
            JsonArray a = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (JsonElement e : a) {
                JsonObject o = e.getAsJsonObject();
                ToadType type = null;
                for (ToadType t : ToadType.values())
                    if (t.id().equals(o.get("type").getAsString())) type = t;
                if (type == null) continue; // a type this version does not know is skipped
                JsonArray p = o.getAsJsonArray("pos");
                s.toads.add(new ToadRecord(o.get("id").getAsString(), type,
                        new double[] {p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()},
                        o.get("yaw").getAsDouble(), o.has("stage") ? o.get("stage").getAsString() : ""));
            }
        } catch (IOException | RuntimeException e) {
            s.toads.clear(); // a damaged file loses its Toads, not the world
        }
        return s;
    }

    public List<ToadRecord> all() {
        return List.copyOf(toads);
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
            o.addProperty("yaw", r.yaw());
            o.addProperty("stage", r.stage());
            a.add(o);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, a.toString());
    }
}
