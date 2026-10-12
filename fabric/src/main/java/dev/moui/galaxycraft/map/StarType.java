package dev.moui.galaxycraft.map;

/** The kinds of star a system can have (colors for the map). Only YELLOW exists until the progression rework. */
public enum StarType {
    YELLOW(0xFFFFE680), RED_DWARF(0xFFFF6A4D), LAVA(0xFFFF9A2E), BLUE(0xFF7FB2FF);

    public final int argb;

    StarType(int argb) {
        this.argb = argb;
    }
}
