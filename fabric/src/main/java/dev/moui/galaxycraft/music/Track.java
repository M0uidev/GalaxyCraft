package dev.moui.galaxycraft.music;

import java.util.List;

/** One song. mood is null for a song in no automatic pool; file is relative to its source's folder. */
public record Track(String id, String title, String file, Source source, Mood mood, List<String> tags, boolean enabled) {
    public Track {
        tags = List.copyOf(tags);
    }

    public Track withMood(Mood m) {
        return new Track(id, title, file, source, m, tags, enabled);
    }

    public Track withEnabled(boolean on) {
        return new Track(id, title, file, source, mood, tags, on);
    }
}
