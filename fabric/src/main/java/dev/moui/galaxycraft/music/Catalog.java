package dev.moui.galaxycraft.music;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * tracks.tsv: one song a line, tab separated: id, title, file, source (smg2|minecraft), mood
 * (space|planet|empty), tags (comma separated), enabled (true|false). Lines starting with # are notes.
 */
public final class Catalog {
    private Catalog() {}

    public static List<Track> parse(String text) {
        List<Track> out = new ArrayList<>();
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;
            Source source;
            switch (f[3].strip().toLowerCase()) {
                case "smg2" -> source = Source.SMG2;
                case "minecraft" -> source = Source.MINECRAFT;
                default -> { continue; }
            }
            Mood mood = switch (f[4].strip().toLowerCase()) {
                case "space" -> Mood.SPACE;
                case "planet" -> Mood.PLANET;
                default -> null;
            };
            List<String> tags = Arrays.stream(f[5].split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            String id = f[0].strip();
            int n = seen.merge(id, 1, Integer::sum); // ids must be unique: later copies become id#2, id#3...
            if (n > 1) id = id + "#" + n;
            out.add(new Track(id, f[1].strip(), f[2].strip(), source, mood, tags, f[6].strip().equals("true")));
        }
        return out;
    }

    public static String format(List<Track> tracks) {
        StringBuilder b = new StringBuilder("# id\ttitle\tfile\tsource\tmood\ttags\tenabled\n");
        for (Track t : tracks)
            b.append(t.id()).append('\t').append(t.title().replace('\t', ' ')).append('\t').append(t.file()).append('\t')
                    .append(t.source().name().toLowerCase()).append('\t').append(t.mood() == null ? "" : t.mood().name().toLowerCase())
                    .append('\t').append(String.join(",", t.tags())).append('\t').append(t.enabled()).append('\n');
        return b.toString();
    }

    public static List<Track> read(Path file) throws IOException {
        return Files.isRegularFile(file) ? parse(Files.readString(file, StandardCharsets.UTF_8)) : List.of();
    }

    public static void write(Path file, List<Track> tracks) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, format(tracks), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
