package dev.moui.galaxycraft.settings;

/**
 * How sharp Dolphin draws the game: its internal resolution, anti-aliasing and anisotropic
 * filtering together. The launcher (core/videoquality.js) and tools/gxplay.sh turn the choice
 * into Dolphin's settings when the game starts; changed while playing, the mod tells Dolphin
 * (GXC_MSG_QUALITY) and it applies at once. The numbers are the same table in all three.
 */
public enum VideoQuality {
    LOW("Low (native)", 1, 1, 0),
    MEDIUM("Medium (2x, MSAA 2x)", 2, 2, 2),
    HIGH("High (3x, MSAA 4x)", 3, 4, 3),
    ULTRA("Ultra (4x, MSAA 4x)", 4, 4, 3);

    private final String label;
    private final int scale;
    private final int msaa;
    private final int anisotropy;

    VideoQuality(String label, int scale, int msaa, int anisotropy) {
        this.label = label;
        this.scale = scale;
        this.msaa = msaa;
        this.anisotropy = anisotropy;
    }

    /** Dolphin's internal resolution multiplier. */
    public int scale() {
        return scale;
    }

    /** Dolphin's MSAA samples. */
    public int msaa() {
        return msaa;
    }

    /** Dolphin's anisotropic filtering level: 0 is 1x, up to 4 for 16x. */
    public int anisotropy() {
        return anisotropy;
    }

    public String label() {
        return label;
    }
}
