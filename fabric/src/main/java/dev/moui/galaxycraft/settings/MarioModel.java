package dev.moui.galaxycraft.settings;

/** The body Mario movement draws (Mario movement only; Minecraft movement is always Steve). */
public enum MarioModel {
    /** Steve's boxes on Mario's skeleton, with the player's skin. */
    STEVE("Steve's body"),
    /** The game's own Mario, as SMG2 draws him. */
    ORIGINAL("Original Mario");

    private final String label;

    MarioModel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
