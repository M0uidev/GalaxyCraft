package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.AstFile;
import dev.moui.galaxycraft.music.AstSource;
import dev.moui.galaxycraft.music.Catalog;
import dev.moui.galaxycraft.music.Mood;
import dev.moui.galaxycraft.music.PcmSource;
import dev.moui.galaxycraft.music.Source;
import dev.moui.galaxycraft.music.Track;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/** The songs the player can hear: soundtrack/tracks.tsv (SMG2) plus Minecraft's own music/game songs. */
final class MusicLibrary {
    /** -Dgalaxycraft.soundtrackDir points tests at a scratch folder instead of the game's. */
    private final Path dir = System.getProperty("galaxycraft.soundtrackDir") != null
            ? Path.of(System.getProperty("galaxycraft.soundtrackDir"))
            : FabricLoader.getInstance().getGameDir().resolve("soundtrack");
    private final Path tsv = dir.resolve("tracks.tsv");
    private final Path mcTsv = dir.resolve("minecraft.tsv");
    private List<Track> tracks = List.of();

    List<Track> tracks() {
        return tracks;
    }

    /** Reads the catalog again and finds Minecraft's songs (the player's mood/enabled choices for them are in minecraft.tsv). */
    void reload() {
        List<Track> all = new ArrayList<>();
        try {
            all.addAll(Catalog.read(tsv));
        } catch (IOException e) {
            System.err.println("GalaxyCraft: could not read " + tsv + ": " + e);
        }
        all.addAll(minecraftTracks());
        tracks = List.copyOf(all);
    }

    private List<Track> minecraftTracks() {
        List<Track> found = new ArrayList<>();
        Map<Identifier, Resource> res = Minecraft.getInstance().getResourceManager()
                .listResources("sounds/music", id -> id.getPath().endsWith(".ogg") && id.getPath().contains("/game/"));
        for (Identifier id : res.keySet()) {
            String path = id.getPath(); // sounds/music/game/calm1.ogg
            String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
            found.add(new Track("mc:" + id.getNamespace() + ":" + path, "Minecraft: " + name, id.toString(),
                    Source.MINECRAFT, Mood.PLANET, List.of(), true));
        }
        found.sort((a, b) -> a.id().compareTo(b.id()));
        List<Track> saved;
        try {
            saved = Catalog.read(mcTsv);
        } catch (IOException e) {
            return found;
        }
        return found.stream().map(t -> saved.stream().filter(s -> s.id().equals(t.id())).findFirst()
                .map(s -> t.withEnabled(s.enabled()).withMood(s.mood())).orElse(t)).toList();
    }

    /** Saves the player's mood/enabled choices. */
    private void save() {
        try {
            Catalog.write(tsv, tracks.stream().filter(t -> t.source() == Source.SMG2).toList());
            Catalog.write(mcTsv, tracks.stream().filter(t -> t.source() == Source.MINECRAFT).toList());
        } catch (IOException e) {
            System.err.println("GalaxyCraft: could not save the music catalog: " + e);
        }
    }

    void replace(Track old, Track now) {
        List<Track> l = new ArrayList<>(tracks);
        l.replaceAll(t -> t.id().equals(old.id()) ? now : t);
        tracks = List.copyOf(l);
        save();
    }

    /** Opens a song for playing; null (and one log line) if its file is missing or broken. */
    PcmSource open(Track t) {
        try {
            if (t.source() == Source.SMG2) {
                Path f = dir.resolve(t.file());
                if (!Files.isRegularFile(f)) throw new IOException("not on disk: " + f);
                return new AstSource(AstFile.open(f));
            }
            InputStream in = Minecraft.getInstance().getResourceManager().open(Identifier.parse(t.file()));
            return new OggSource(in);
        } catch (IOException | RuntimeException e) {
            System.err.println("GalaxyCraft: cannot play " + t.title() + ": " + e);
            return null;
        }
    }
}
