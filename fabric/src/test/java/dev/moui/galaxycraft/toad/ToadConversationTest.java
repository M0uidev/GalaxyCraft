package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToadConversationTest {
    private static ToadConversation talk(boolean trade) {
        var choices = trade
                ? List.of(ToadConversation.Choice.TRADE, ToadConversation.Choice.PLACE, ToadConversation.Choice.ADVICE, ToadConversation.Choice.BYE)
                : List.of(ToadConversation.Choice.PLACE, ToadConversation.Choice.ADVICE, ToadConversation.Choice.BYE);
        return new ToadConversation(ToadType.GUIDE, List.of(
                new ToadConversation.Page("a", List.of()), new ToadConversation.Page("b", List.of())), choices);
    }

    @Test void lettersAppearOverTime() {
        ToadConversation c = talk(true);
        assertEquals(0, c.visibleChars(100));
        c.tick(1.0);
        assertEquals((int) ToadConversation.CPS, c.visibleChars(100));
        c.tick(10);
        assertEquals(100, c.visibleChars(100)); // never past the text
    }

    @Test void advanceFinishesTheTypingThenPages() {
        ToadConversation c = talk(true);
        c.advance(50);
        assertEquals(50, c.visibleChars(50));
        assertEquals("a", c.page().key());
        c.advance(50);
        assertEquals("b", c.page().key());
        assertEquals(0, c.visibleChars(50)); // the next page starts typing again
    }

    @Test void afterTheLastPageTheChoicesShow() {
        ToadConversation c = talk(true);
        c.advance(10); // finish a
        c.advance(10); // to b
        c.advance(10); // finish b
        assertFalse(c.choosing());
        c.advance(10); // past b: the choices
        assertTrue(c.choosing());
        c.advance(10); // not a way out: still choosing
        assertTrue(c.choosing());
    }

    @Test void choicesByNumberAndByHighlight() {
        ToadConversation c = talk(true);
        for (int i = 0; i < 4; i++) c.advance(10);
        assertEquals(ToadConversation.Outcome.TRADE, c.choose(0));
        ToadConversation d = talk(true);
        for (int i = 0; i < 4; i++) d.advance(10);
        d.move(3);
        assertEquals(3, d.highlight());
        d.move(1); // wraps
        assertEquals(0, d.highlight());
        d.move(-1);
        assertEquals(ToadConversation.Outcome.CLOSED, d.confirm()); // highlight 3 = Bye
    }

    @Test void aNumberWithoutAChoiceIsIgnored() {
        ToadConversation c = talk(false);
        for (int i = 0; i < 4; i++) c.advance(10);
        assertEquals(ToadConversation.Outcome.NONE, c.choose(3));
        assertEquals(ToadConversation.Outcome.NONE, c.choose(-1));
        assertTrue(c.choosing());
    }

    @Test void choosingBeforeTheChoicesShowDoesNothing() {
        ToadConversation c = talk(true);
        assertEquals(ToadConversation.Outcome.NONE, c.choose(0));
        assertFalse(c.choosing());
    }

    @Test void placeAndAdviceAddPagesThenComeBackToTheChoices() {
        ToadConversation c = talk(true);
        for (int i = 0; i < 4; i++) c.advance(10);
        assertEquals(ToadConversation.Outcome.NONE, c.choose(1)); // Tell me about this place
        assertEquals(ToadConversation.Choice.PLACE, c.lastAsked());
        c.add(List.of(new ToadConversation.Page("place", List.of())));
        assertFalse(c.choosing());
        assertEquals("place", c.page().key());
        c.advance(5); // finish typing
        c.advance(5); // past the last page: choices again
        assertTrue(c.choosing());
        assertEquals(0, c.highlight());
    }

    @Test void aFreshConversationHasNoOutcome() {
        assertEquals(ToadConversation.Outcome.NONE, talk(true).outcome());
    }
}
