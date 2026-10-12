package dev.moui.galaxycraft.input;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModKeyDefaultsTest {
    @Test void noTwoModActionsShareADefaultKey() {
        Map<String, String> seen = new HashMap<>();
        ModKeyDefaults.MOD.forEach((action, key) -> {
            String other = seen.put(key, action);
            assertNull(other, action + " and " + other + " both default to key " + key);
        });
    }

    @Test void noModDefaultTakesAVanillaKey() {
        Map<String, String> vanilla = new HashMap<>();
        ModKeyDefaults.VANILLA.forEach((action, key) -> vanilla.put(key, action));
        ModKeyDefaults.MOD.forEach((action, key) ->
                assertFalse(vanilla.containsKey(key), action + " takes vanilla's " + vanilla.get(key) + " (key " + key + ")"));
    }

    @Test void theMapAndTheMusicPlayerAreOnDifferentKeys() {
        assertNotEquals(ModKeyDefaults.MOD.get("map"), ModKeyDefaults.MOD.get("music.open"));
    }
}
