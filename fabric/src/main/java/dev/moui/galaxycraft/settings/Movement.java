package dev.moui.galaxycraft.settings;

/** Who moves the player. */
public enum Movement {
    /** SMG2 moves Mario by his own physics (jumps, spins, long jumps) and the player follows him. */
    MARIO("Mario"),
    /** Minecraft moves the player by its own physics (walk, sprint, sneak, a 1.25-block jump) and Mario goes with it. */
    MINECRAFT("Minecraft");

    private final String label;

    Movement(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public Movement other() {
        return this == MARIO ? MINECRAFT : MARIO;
    }
}
