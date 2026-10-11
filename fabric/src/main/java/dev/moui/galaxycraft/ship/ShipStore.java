package dev.moui.galaxycraft.ship;

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

/** The ships of one galaxy, kept in a JSON file next to its planets. */
public final class ShipStore {
    private final Path file;
    private final List<ShipRecord> ships = new ArrayList<>();

    private ShipStore(Path file) {
        this.file = file;
    }

    public static ShipStore load(Path file) {
        ShipStore s = new ShipStore(file);
        try {
            if (!Files.exists(file)) return s;
            JsonArray a = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (JsonElement e : a) {
                JsonObject o = e.getAsJsonObject();
                JsonArray p = o.getAsJsonArray("pos");
                ShipModules m = new ShipModules(o.get("hull").getAsInt(), o.get("engine").getAsInt(),
                        o.get("rudder").getAsInt(), o.get("cabin").getAsInt()).repaired();
                JsonArray q = o.has("rot") ? o.getAsJsonArray("rot") : null;
                double[] rot = q == null || q.size() != 4 ? new double[] {0, 0, 0, 1}
                        : new double[] {q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble(), q.get(3).getAsDouble()};
                s.ships.add(new ShipRecord(o.get("id").getAsString(), m,
                        new double[] {p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()}, rot,
                        o.has("stage") ? o.get("stage").getAsString() : ""));
            }
        } catch (IOException | RuntimeException e) {
            s.ships.clear(); // a damaged file loses its ships, not the world
        }
        return s;
    }

    public List<ShipRecord> all() {
        return List.copyOf(ships);
    }

    public void put(ShipRecord r) {
        ships.removeIf(x -> x.id().equals(r.id()));
        ships.add(r);
    }

    public Optional<ShipRecord> remove(String id) {
        for (ShipRecord r : ships)
            if (r.id().equals(id)) {
                ships.remove(r);
                return Optional.of(r);
            }
        return Optional.empty();
    }

    public void save() throws IOException {
        JsonArray a = new JsonArray();
        for (ShipRecord r : ships) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("hull", r.modules().hull());
            o.addProperty("engine", r.modules().engine());
            o.addProperty("rudder", r.modules().rudder());
            o.addProperty("cabin", r.modules().cabin());
            JsonArray p = new JsonArray();
            for (double v : r.pos()) p.add(v);
            o.add("pos", p);
            JsonArray q = new JsonArray();
            for (double v : r.rot()) q.add(v);
            o.add("rot", q);
            o.addProperty("stage", r.stage());
            a.add(o);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, a.toString());
    }
}
