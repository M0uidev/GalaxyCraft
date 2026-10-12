package dev.moui.galaxycraft.toad;

import java.util.ArrayList;
import java.util.List;

/**
 * One talk with a Toad, as a state machine the HUD draws: pages of text typed letter by letter, then
 * a few things to ask. Knows lang keys only (the client resolves them and tells how long a text is).
 * Time is given to it ({@link #tick}), so it is deterministic. No Minecraft types.
 */
public final class ToadConversation {
    /** Letters typed per second. */
    public static final double CPS = 30;

    /** A page: a lang key and its arguments. */
    public record Page(String key, List<Object> args) {}

    public enum Choice { TRADE, PLACE, ADVICE, BYE }

    public enum Outcome { NONE, TRADE, CLOSED }

    private final ToadType type;
    private final List<Page> pages;
    private final List<Choice> choices;
    private int index;
    private double elapsed;
    private boolean choosing;
    private int highlight;
    private Choice lastAsked;
    private Outcome outcome = Outcome.NONE;

    public ToadConversation(ToadType type, List<Page> pages, List<Choice> choices) {
        if (pages.isEmpty()) throw new IllegalArgumentException("a conversation says something");
        this.type = type;
        this.pages = new ArrayList<>(pages);
        this.choices = List.copyOf(choices);
    }

    public ToadType type() {
        return type;
    }

    public List<Page> pages() {
        return List.copyOf(pages);
    }

    public List<Choice> choices() {
        return choices;
    }

    public Page page() {
        return pages.get(index);
    }

    /** Which page is showing (0 first); changes when the next one starts, for the voice's bookkeeping. */
    public int pageNumber() {
        return index;
    }

    /** Time passes: letters come. */
    public void tick(double seconds) {
        if (!choosing) elapsed += Math.max(0, seconds);
    }

    /** How many letters of a page text this long are showing. */
    public int visibleChars(int fullLength) {
        return Math.min(fullLength, (int) (elapsed * CPS));
    }

    /** A click or Enter: finish the typing, else the next page, past the last one the choices. */
    public void advance(int fullLength) {
        if (choosing || outcome != Outcome.NONE) return;
        if (visibleChars(fullLength) < fullLength) {
            elapsed = fullLength / CPS + 1;
        } else if (index + 1 < pages.size()) {
            index++;
            elapsed = 0;
        } else {
            choosing = true;
            highlight = 0;
        }
    }

    public boolean choosing() {
        return choosing;
    }

    public int highlight() {
        return highlight;
    }

    /** Moves the highlight (wraps). */
    public void move(int delta) {
        if (!choosing || choices.isEmpty()) return;
        highlight = Math.floorMod(highlight + delta, choices.size());
    }

    /** The highlighted choice taken. */
    public Outcome confirm() {
        return choose(highlight);
    }

    /** Choice i taken (ignored when there is none, or the choices are not showing). */
    public Outcome choose(int i) {
        if (!choosing || i < 0 || i >= choices.size()) return Outcome.NONE;
        Choice c = choices.get(i);
        switch (c) {
            case TRADE -> outcome = Outcome.TRADE;
            case BYE -> outcome = Outcome.CLOSED;
            default -> {
                lastAsked = c;
                choosing = false;
            }
        }
        return outcome;
    }

    /** The last "place" or "advice" asked (the caller adds its pages). */
    public Choice lastAsked() {
        return lastAsked;
    }

    /** More to say: shown next, then the choices come back. */
    public void add(List<Page> more) {
        if (more.isEmpty()) {
            choosing = true;
            return;
        }
        index = pages.size();
        pages.addAll(more);
        elapsed = 0;
        choosing = false;
    }

    public Outcome outcome() {
        return outcome;
    }
}
