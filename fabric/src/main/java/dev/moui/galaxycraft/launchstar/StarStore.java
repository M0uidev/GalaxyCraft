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
                String id = o.get("id").getAsString();
                String partner = o.has("partner") && !o.get("partner").isJsonNull() ? o.get("partner").getAsString() : null;
                String code = o.has("code") ? o.get("code").getAsString()
                        : partner != null ? "link-" + shortId(id.compareTo(partner) < 0 ? id : partner) : "star-" + shortId(id);
                s.stars.add(new StarRecord(id, vec(o.getAsJsonArray("pos")), vec(o.getAsJsonArray("up")),
                        o.get("stage").getAsString(), o.get("body").getAsString(), code));
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
            o.addProperty("code", r.code());
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


    private static String shortId(String id) {
        return id.length() <= 4 ? id : id.substring(0, 4);
    }

    /** Codes are short, trimmed and compared without case. */
    public static String clean(String code) {
        String c = code == null ? "" : code.strip();
        return c.length() > MAX_CODE ? c.substring(0, MAX_CODE) : c;
    }

    public static final int MAX_CODE = 24;

    private boolean sameCode(StarRecord a, StarRecord b) {
        return a.code().equalsIgnoreCase(b.code());
    }

    /** The oldest other star of the same stage with the same code on another body. */
    public Optional<StarRecord> partnerOf(String id) {
        StarRecord r = get(id);
        if (r == null) return Optional.empty();
        return stars.stream().filter(x -> !x.id().equals(id) && x.stage().equals(r.stage()) && !x.body().equals(r.body()) && sameCode(x, r))
                .findFirst();
    }

    /** star-1, star-2...: the first not used as a code yet. */
    public String nextDefaultCode() {
        for (int n = 1; ; n++) {
            String c = "star-" + n;
            if (stars.stream().noneMatch(x -> x.code().equalsIgnoreCase(c))) return c;
        }
    }

    /**
     * Stores the star. With no code of its own it takes the code of the oldest star waiting alone on
     * another body (so the second star of a pair links by itself), else a new default code. The stored record.
     */
    public StarRecord place(StarRecord r) {
        stars.removeIf(x -> x.id().equals(r.id()));
        String code = clean(r.code());
        if (code.isEmpty()) {
            StarRecord waiting = stars.stream().filter(x -> x.stage().equals(r.stage()) && !x.body().equals(r.body())
                    && partnerOf(x.id()).isEmpty()).findFirst().orElse(null);
            code = waiting != null ? waiting.code() : nextDefaultCode();
        }
        StarRecord stored = r.withCode(code);
        stars.add(stored);
        return stored;
    }

    /** Gives a star another code (blank: unchanged); the star, or null if unknown. */
    public StarRecord setCode(String id, String code) {
        String c = clean(code);
        StarRecord r = get(id);
        if (r == null || c.isEmpty()) return r;
        StarRecord n = r.withCode(c);
        stars.set(stars.indexOf(r), n);
        return n;
    }

    public Optional<StarRecord> remove(String id) {
        StarRecord r = get(id);
        if (r == null) return Optional.empty();
        stars.remove(r);
        return Optional.of(r);
    }
}
