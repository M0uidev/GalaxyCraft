package dev.moui.galaxycraft.toad;

import dev.moui.galaxycraft.toad.ToadConversation.Choice;
import dev.moui.galaxycraft.toad.ToadConversation.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What a Toad says and offers: the opening pages, and the pages for "tell me about this place" and
 * "where should I go". Lang keys only (the client resolves them); picked by rules over the context,
 * no AI. No Minecraft types.
 */
public final class ToadScript {
    /** The longest a page's text may be (the HUD box shows about this much). */
    public static final int PAGE_CHARS = 140;

    private static final Set<String> STAR_NOTES = Set.of("red", "lava", "blue");

    private ToadScript() {}

    /** The talk begins: the greeting, then what can be asked (Trade only when it sells something). */
    public static ToadConversation open(ToadType type, ToadContext ctx, boolean canTrade) {
        List<Page> pages = new ArrayList<>();
        for (ToadDialogue.Line l : ToadDialogue.lines(type, ctx)) pages.add(new Page(l.key(), l.args()));
        List<Choice> choices = new ArrayList<>();
        if (canTrade) choices.add(Choice.TRADE);
        choices.add(Choice.PLACE);
        choices.add(Choice.ADVICE);
        choices.add(Choice.BYE);
        return new ToadConversation(type, pages, choices);
    }

    /** About this place: its star, its ring, whether the player has been. */
    public static List<Page> place(ToadType type, ToadContext ctx) {
        int tier = Math.max(0, Math.min(5, ctx.systemTier()));
        List<Page> out = new ArrayList<>();
        if (STAR_NOTES.contains(ctx.star())) out.add(new Page("toad.dialogue.star." + ctx.star(), List.of()));
        out.add(new Page("toad.dialogue.place.tier." + tier, List.of()));
        out.add(new Page(ctx.visited() ? "toad.dialogue.place.visited" : "toad.dialogue.place.new", List.of()));
        return out;
    }

    /** Where to go next: what this type advises at this tier, and the module the ship lacks. */
    public static List<Page> advice(ToadType type, ToadContext ctx) {
        int tier = Math.max(0, Math.min(5, ctx.systemTier()));
        List<Page> out = new ArrayList<>();
        out.add(new Page("toad.dialogue." + type.id() + ".advice." + tier, List.of()));
        if (ctx.missingModule() != null) out.add(new Page("toad.dialogue.advice.module", List.of(ctx.missingModule())));
        return out;
    }
}
