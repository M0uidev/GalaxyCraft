package dev.moui.galaxycraft.view;

import dev.moui.galaxycraft.proto.Layout;

/**
 * The perspective F5 picks: Minecraft's three, plus SMG2's own camera in Mario mode. Minecraft
 * draws the Galaxy view as THIRD_PERSON_BACK (Steve visible, no hand), from the game's camera.
 */
public enum View {
    FIRST(Layout.VIEW_FIRST, 0),
    BACK(Layout.VIEW_BACK, 1),
    FRONT(Layout.VIEW_FRONT, 2),
    GALAXY(Layout.VIEW_GALAXY, 1);

    private final int protocolId;
    private final int cameraTypeOrdinal;

    View(int protocolId, int cameraTypeOrdinal) {
        this.protocolId = protocolId;
        this.cameraTypeOrdinal = cameraTypeOrdinal;
    }

    /** From Minecraft's CameraType ordinal (FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT). */
    public static View of(int cameraTypeOrdinal, boolean galaxy) {
        if (galaxy) return GALAXY;
        return switch (cameraTypeOrdinal) {
            case 1 -> BACK;
            case 2 -> FRONT;
            default -> FIRST;
        };
    }

    /** What F5 goes to; the Galaxy view only exists in Mario mode. */
    public View next(boolean marioMode) {
        return switch (this) {
            case FIRST -> BACK;
            case BACK -> FRONT;
            case FRONT -> marioMode ? GALAXY : FIRST;
            case GALAXY -> FIRST;
        };
    }

    public int protocolId() {
        return protocolId;
    }

    public int cameraTypeOrdinal() {
        return cameraTypeOrdinal;
    }
}
