package dev.moui.galaxycraft.music;

import java.util.List;

/** Which games' songs the automatic music uses. */
public enum SourceMode {
    BOTH("Both games"), SMG2("Super Mario Galaxy 2"), MINECRAFT("Minecraft"), RANDOM("Random (any)");

    private final String label;

    SourceMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Space and planet only matter when songs are chosen by mood; Minecraft only and Random ignore it. */
    public Want apply(Want w) {
        boolean moody = this == BOTH || this == SMG2;
        return !moody && (w.kind() == Want.Kind.SPACE || w.kind() == Want.Kind.PLANET) ? Want.PLANET : w;
    }

    /** The enabled songs automatic music may pick for a mood want (SPACE or PLANET). */
    public List<Track> pool(List<Track> all, Want want) {
        Mood mood = want.kind() == Want.Kind.SPACE ? Mood.SPACE : Mood.PLANET;
        return all.stream().filter(Track::enabled).filter(t -> switch (this) {
            case BOTH -> t.mood() == mood;
            case SMG2 -> t.source() == Source.SMG2 && t.mood() == mood;
            case MINECRAFT -> t.source() == Source.MINECRAFT;
            case RANDOM -> t.mood() != null;
        }).toList();
    }
}
