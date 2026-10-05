package dev.moui.galaxycraft.settings;

/** Who moves the player. */
public enum Movement {
    /** SMG2 moves Mario by his own physics (jumps, spins, long jumps) and the player follows him. */
    MARIO("Mario"),
    /** Minecraft moves the player by its own physics (Steve, the skin, the hand) and Mario goes with it. */
    MINECRAFT("Minecraft"),
    /**
     * SMG2 still moves Mario (his collision, made for planets), at Minecraft's walk, sprint (Ctrl)
     * and sneak (Shift) speeds, with Minecraft's 1.25-block jump and none of Mario's moves.
     */
    MARIO_MC("Mario at Minecraft's speeds");

    private final String label;

    Movement(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** F6: Mario's movement or Minecraft's. */
    public Movement other() {
        return this == MARIO ? MINECRAFT : MARIO;
    }
}
