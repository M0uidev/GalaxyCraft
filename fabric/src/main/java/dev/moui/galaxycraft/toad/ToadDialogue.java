package dev.moui.galaxycraft.toad;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The lines a Toad says before the trade: lang keys with arguments, picked by rules, no AI. */
public final class ToadDialogue {
    /** A lang key and its arguments. */
    public record Line(String key, List<Object> args) {
        static Line of(String key, Object... args) {
            return new Line(key, List.of(args));
        }
    }

    private static final Set<String> STARS_WITH_NOTES = Set.of("red", "lava", "blue");

    private ToadDialogue() {}

    public static List<Line> lines(ToadType type, ToadContext c) {
        int tier = Math.max(0, Math.min(5, c.systemTier()));
        List<Line> out = new ArrayList<>();
        if (c.warpCoreTier() < tier && !c.visited()) out.add(Line.of("toad.dialogue.warp_core_low", tier, Math.max(0, c.warpCoreTier())));
        if (c.missingModule() != null) out.add(Line.of("toad.dialogue.missing_module", c.missingModule()));
        if (STARS_WITH_NOTES.contains(c.star())) out.add(Line.of("toad.dialogue.star." + c.star()));
        if (out.size() > 2) out = new ArrayList<>(out.subList(0, 2));
        out.add(Line.of("toad.dialogue." + type.id() + ".tier." + tier));
        return out;
    }
}
