package dev.moui.galaxycraft.settings;

/**
 * How sharp Dolphin draws the game: its internal resolution, anti-aliasing and anisotropic
 * filtering together. The launcher (core/videoquality.js) and tools/gxplay.sh turn the choice
 * into Dolphin's settings when the game starts, so a change applies the next time it starts.
 */
public enum VideoQuality {
    LOW("Low (native)"),
    MEDIUM("Medium (2x, MSAA 2x)"),
    HIGH("High (3x, MSAA 4x)"),
    ULTRA("Ultra (4x, MSAA 4x)");

    private final String label;

    VideoQuality(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
