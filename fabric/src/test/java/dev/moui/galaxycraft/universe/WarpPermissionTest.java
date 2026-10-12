package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WarpPermissionTest {
    @Test void creativeOrCheatsOpenTheWarp() {
        assertTrue(WarpPermission.allowed(true, false));
        assertTrue(WarpPermission.allowed(false, true));
        assertTrue(WarpPermission.allowed(true, true));
    }

    @Test void survivalWithoutCheatsIsRefused() {
        assertFalse(WarpPermission.allowed(false, false));
    }

    @Test void theRefusalSaysWhatToDo() {
        String msg = WarpPermission.refusal();
        assertTrue(msg.contains("creative"), msg);
        assertTrue(msg.contains("cheats"), msg);
    }
}
