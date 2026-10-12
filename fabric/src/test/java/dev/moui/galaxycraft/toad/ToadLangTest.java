package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ToadLangTest {
    @Test void everyLineAToadCanSayHasText() throws IOException {
        JsonObject lang;
        try (var in = ToadLangTest.class.getResourceAsStream("/assets/galaxycraft/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (ToadType t : ToadType.values())
            for (int tier = 0; tier <= 5; tier++)
                for (String star : new String[] {"yellow", "red", "lava", "blue"})
                    for (var l : ToadDialogue.lines(t, new ToadContext(tier, star, 0, "engine", false)))
                        assertTrue(lang.has(l.key()), "missing lang key " + l.key());
    }
}
