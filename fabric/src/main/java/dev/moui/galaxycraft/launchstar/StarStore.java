package dev.moui.galaxycraft.launchstar;

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

/**
 * The Launch Stars of one galaxy, kept in a JSON file next to its planets. A star placed while
 * another waits unlinked on a different body links to the oldest such star; taking one away
 * leaves its partner waiting for a new one.
 */
public final class StarStore {
    private final Path file;
    private final List<StarRecord> stars = new ArrayList<>();

    private StarStore(Path file) {
        this.file = file;
    }

    public static StarStore load(Path file) {
        StarStore s = new StarStore(file);
        try {
            if (!Files.exists(file)) return s;
            for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                s.stars.add(new StarRecord(o.get("id").getAsString(), vec(o.getAsJsonArray("pos")), vec(o.getAsJsonArray("up")),
                        o.get("stage").getAsString(), o.get("body").getAsString(),
                        o.has("partner") && !o.get("partner").isJsonNull() ? o.get("partner").getAsString() : null));
            }
        } catch (IOException | RuntimeException e) {
            s.stars.clear(); // a damaged file loses its stars, not the world
        }
        return s;
    }

    private static double[] vec(JsonArray a) {
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    public void save() throws IOException {
        JsonArray a = new JsonArray();
        for (StarRecord r : stars) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.add("pos", arr(r.pos()));
            o.add("up", arr(r.up()));
            o.addProperty("stage", r.stage());
            o.addProperty("body", r.body());
            if (r.partner() != null) o.addProperty("partner", r.partner());
            a.add(o);
        }
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, a.toString());
    }

    private static JsonArray arr(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }

    public List<StarRecord> all() {
        return List.copyOf(stars);
    }

    public StarRecord get(String id) {
        return stars.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }

    public Optional<StarRecord> partnerOf(String id) {
        StarRecord r = get(id);
        return r == null || r.partner() == null ? Optional.empty() : Optional.ofNullable(get(r.partner()));
    }

    /** Stores the star, linked to the oldest waiting star on another body in the same stage; the stored record. */
    public StarRecord place(StarRecord r) {
        stars.removeIf(x -> x.id().equals(r.id()));
        StarRecord waiting = stars.stream()
                .filter(x -> x.partner() == null && x.stage().equals(r.stage()) && !x.body().equals(r.body())).findFirst().orElse(null);
        StarRecord stored = waiting == null ? r.withPartner(null) : r.withPartner(waiting.id());
        if (waiting != null) stars.set(stars.indexOf(waiting), waiting.withPartner(r.id()));
        stars.add(stored);
        return stored;
    }

    public Optional<StarRecord> remove(String id) {
        StarRecord r = get(id);
        if (r == null) return Optional.empty();
        stars.remove(r);
        for (int i = 0; i < stars.size(); i++)
            if (id.equals(stars.get(i).partner())) stars.set(i, stars.get(i).withPartner(null));
        return Optional.of(r);
    }
}
