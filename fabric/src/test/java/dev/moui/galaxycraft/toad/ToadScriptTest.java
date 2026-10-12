package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.moui.galaxycraft.universe.StarType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ToadScriptTest {
    @Test void everyTypeAndTierHasAGreetingPlaceAndAdvice() {
        for (ToadType t : ToadType.values())
            for (int tier = 0; tier <= 5; tier++) {
                ToadContext ctx = ToadContext.forSystem(tier, StarType.YELLOW);
                assertFalse(ToadScript.open(t, ctx, true).page().key().isEmpty());
                assertFalse(ToadScript.place(t, ctx).isEmpty());
                assertFalse(ToadScript.advice(t, ctx).isEmpty());
            }
    }

    @Test void noTradeNoTradeChoice() {
        var without = ToadScript.open(ToadType.GUIDE, ToadContext.home(), false);
        assertFalse(without.choices().contains(ToadConversation.Choice.TRADE));
        assertTrue(ToadScript.open(ToadType.GUIDE, ToadContext.home(), true).choices().contains(ToadConversation.Choice.TRADE));
        assertEquals(ToadConversation.Choice.BYE, without.choices().getLast());
    }

    @Test void theMissingModuleIsAdvisedWithItsName() {
        ToadContext ctx = new ToadContext(2, "yellow", 0, "engine", true);
        var pages = ToadScript.advice(ToadType.TECHNICIAN, ctx);
        assertEquals(2, pages.size());
        assertEquals("toad.dialogue.advice.module", pages.get(1).key());
        assertEquals("engine", pages.get(1).args().get(0));
    }

    @Test void unknownOrOutOfRangeTierStillSaysSomething() {
        ToadContext ctx = new ToadContext(99, "yellow", 0, null, false);
        assertEquals("toad.dialogue.place.tier.5", ToadScript.place(ToadType.GUIDE, ctx).getFirst().key());
        assertEquals("toad.dialogue.guide.advice.5", ToadScript.advice(ToadType.GUIDE, ctx).getFirst().key());
    }

    @Test void everyKeyUsedIsInTheLangFileAndFitsAPage() throws IOException {
        JsonObject lang = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/galaxycraft/lang/en_us.json"))).getAsJsonObject();
        for (ToadType t : ToadType.values())
            for (int tier = 0; tier <= 5; tier++)
                for (StarType star : StarType.values()) {
                    ToadContext ctx = new ToadContext(tier, ToadContext.forSystem(tier, star).star(), 0, "engine", tier % 2 == 0);
                    var pages = new ArrayList<ToadConversation.Page>();
                    pages.addAll(ToadScript.open(t, ctx, true).pages());
                    pages.addAll(ToadScript.place(t, ctx));
                    pages.addAll(ToadScript.advice(t, ctx));
                    for (var p : pages) {
                        assertTrue(lang.has(p.key()), "missing " + p.key());
                        assertTrue(lang.get(p.key()).getAsString().length() <= ToadScript.PAGE_CHARS, p.key());
                    }
                }
        for (var c : ToadConversation.Choice.values()) assertTrue(lang.has("toad.choice." + c.name().toLowerCase()), c.name());
        assertTrue(lang.has("toad.hud.next") && lang.has("toad.hud.pick") && lang.has("toad.dialogue.bye"));
    }
}
