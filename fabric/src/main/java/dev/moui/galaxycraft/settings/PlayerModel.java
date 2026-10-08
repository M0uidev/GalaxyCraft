package dev.moui.galaxycraft.settings;

/** Who the player is drawn as with Minecraft's movement. */
public enum PlayerModel {
    /** Minecraft's player model, drawn as one of the planet's entities. */
    STEVE("Steve"),
    /** SMG2's Mario, moved by the player, walking, running and jumping as the player does. */
    MARIO("Mario");

    private final String label;

    PlayerModel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
