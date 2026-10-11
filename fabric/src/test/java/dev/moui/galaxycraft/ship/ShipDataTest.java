package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import org.junit.jupiter.api.Test;

class ShipDataTest {
    private static JsonObject json(String path) {
        var in = ShipDataTest.class.getResourceAsStream("/" + path);
        assertNotNull(in, path + " is missing");
        return JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
    }

    @Test
    void everyShipRecipeHasAResultInOurNamespace() {
        for (String r : new String[] {"ship_table", "ship_hull", "ship_engine", "ship_rudder", "ship_cabin", "toad_core"})
            assertTrue(json("data/galaxycraft/recipe/" + r + ".json").getAsJsonObject("result")
                    .get("id").getAsString().startsWith("galaxycraft:"), r);
    }

    @Test
    void theTableNeedsNoAdvancement() {
        assertFalse(json("data/galaxycraft/recipe/ship_table.json").has("advancement"));
    }

    @Test
    void namesAreTranslated() {
        var lang = json("assets/galaxycraft/lang/en_us.json");
        for (String k : new String[] {"block.galaxycraft.ship_table", "item.galaxycraft.ship_hull", "item.galaxycraft.ship_engine",
                "item.galaxycraft.ship_rudder", "item.galaxycraft.ship_cabin", "item.galaxycraft.toad_core", "item.galaxycraft.toad_ship"})
            assertTrue(lang.has(k), k);
    }

    @Test
    void theAdvancementsHangOffTheRootAndRequireNothingElse() {
        assertEquals("galaxycraft:ship/root", json("data/galaxycraft/advancement/ship/toad_core.json").get("parent").getAsString());
        assertFalse(json("data/galaxycraft/advancement/ship/root.json").has("parent"));
    }
}
