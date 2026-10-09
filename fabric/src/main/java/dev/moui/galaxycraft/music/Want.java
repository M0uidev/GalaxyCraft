package dev.moui.galaxycraft.music;

/** What the place calls for: a mood's music, one song, or nothing. */
public record Want(Kind kind, String trackId) {
    public enum Kind { SPACE, PLANET, TRACK, SILENCE }

    public static final Want SPACE = new Want(Kind.SPACE, null), PLANET = new Want(Kind.PLANET, null),
            SILENCE = new Want(Kind.SILENCE, null);

    public static Want track(String id) {
        return new Want(Kind.TRACK, id);
    }

    public String encode() {
        return switch (kind) {
            case SPACE -> "space";
            case PLANET -> "planet";
            case SILENCE -> "silence";
            case TRACK -> "track:" + trackId;
        };
    }

    public static Want decode(String s) {
        if (s == null) return SPACE;
        return switch (s) {
            case "space" -> SPACE;
            case "planet" -> PLANET;
            case "silence" -> SILENCE;
            default -> s.startsWith("track:") && s.length() > 6 ? track(s.substring(6)) : SPACE;
        };
    }
}
