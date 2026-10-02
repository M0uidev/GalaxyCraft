package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.proto.Layout;
import org.junit.jupiter.api.Test;

class ViewTest {
    @Test void f5CyclesThroughTheGalaxyView() {
        assertEquals(View.BACK, View.FIRST.next(true));
        assertEquals(View.FRONT, View.BACK.next(true));
        assertEquals(View.GALAXY, View.FRONT.next(true));
        assertEquals(View.FIRST, View.GALAXY.next(true));
    }

    @Test void withoutMarioModeTheCycleIsVanilla() {
        assertEquals(View.FIRST, View.FRONT.next(false));
        assertEquals(View.FIRST, View.GALAXY.next(false));
    }

    @Test void vanillaCameraTypeOrdinals() {
        // CameraType: FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT.
        assertEquals(View.FRONT, View.of(2, false));
        assertEquals(View.GALAXY, View.of(1, true));
        assertEquals(1, View.GALAXY.cameraTypeOrdinal());
        assertEquals(0, View.FIRST.cameraTypeOrdinal());
    }

    @Test void protocolIds() {
        assertEquals(Layout.VIEW_FIRST, View.FIRST.protocolId());
        assertEquals(Layout.VIEW_BACK, View.BACK.protocolId());
        assertEquals(Layout.VIEW_FRONT, View.FRONT.protocolId());
        assertEquals(Layout.VIEW_GALAXY, View.GALAXY.protocolId());
    }
}
