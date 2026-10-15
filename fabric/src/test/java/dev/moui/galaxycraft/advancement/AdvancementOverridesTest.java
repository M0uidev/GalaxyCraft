package dev.moui.galaxycraft.advancement;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The vanilla advancements that cannot fire in a galaxy of planets are overridden by our data pack as impossible and
 * hidden. Checked against the vanilla files on the classpath: every override replaces a real advancement (a typo would
 * make a stray new one), keeps its parent, has no display, and the ones that still work are left alone.
 */
class AdvancementOverridesTest {
    private static final String DIR = "data/minecraft/advancement/";
    /** What stays vanilla: roots of the tabs we keep and the advancements the planets can really earn. */
    private static final Set<String> KEPT = Set.of("story/root", "story/mine_stone", "story/upgrade_tools", "story/smelt_iron",
            "story/iron_tools", "story/mine_diamond", "story/lava_bucket", "story/obtain_armor", "story/deflect_arrow",
            "story/shiny_gear", "story/enchant_item", "story/form_obsidian", "adventure/root", "adventure/kill_a_mob",
            "adventure/kill_all_mobs", "adventure/trade", "adventure/shoot_arrow", "adventure/hero_of_the_village",
            "husbandry/root", "husbandry/breed_an_animal", "husbandry/tame_an_animal", "husbandry/bred_all_animals",
            "husbandry/balanced_diet");

    private static List<Path> overrides() throws Exception {
        Path root = Path.of("src/main/resources/" + DIR);
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList());
        }
    }

    private static String id(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.substring(s.indexOf(DIR) + DIR.length(), s.length() - 5);
    }

    /** The vanilla file of an advancement: the one on the classpath that is not ours. */
    private static JsonObject vanilla(String id) throws Exception {
        Enumeration<URL> all = AdvancementOverridesTest.class.getClassLoader().getResources(DIR + id + ".json");
        while (all.hasMoreElements()) {
            URL u = all.nextElement();
            if (u.toString().contains("/resources/main/") || u.toString().contains("src/main/resources")) continue;
            try (var in = new InputStreamReader(u.openStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(in).getAsJsonObject();
            }
        }
        return null;
    }

    @Test
    void everyOverrideReplacesARealAdvancementAndHidesIt() throws Exception {
        List<Path> files = overrides();
        assertTrue(files.size() > 50, "the overrides exist: " + files.size());
        List<String> bad = new ArrayList<>();
        for (Path p : files) {
            String id = id(p);
            JsonObject mine;
            try (var in = Files.newBufferedReader(p)) {
                mine = JsonParser.parseReader(in).getAsJsonObject();
            }
            JsonObject real = vanilla(id);
            if (real == null) {
                bad.add(id + ": no such vanilla advancement");
                continue;
            }
            if (mine.has("display")) bad.add(id + ": still shown");
            if (mine.has("rewards")) bad.add(id + ": gives rewards");
            if (id.startsWith("recipes/")) bad.add(id + ": recipes stay vanilla");
            if (KEPT.contains(id)) bad.add(id + ": must stay vanilla");
            var crit = mine.getAsJsonObject("criteria");
            if (crit == null || crit.size() != 1 || !crit.getAsJsonObject("never").get("trigger").getAsString().equals("minecraft:impossible"))
                bad.add(id + ": not impossible");
            String parent = real.has("parent") ? real.get("parent").getAsString() : null;
            String mineParent = mine.has("parent") ? mine.get("parent").getAsString() : null;
            if (!Objects.equals(parent, mineParent)) bad.add(id + ": parent changed " + parent + " -> " + mineParent);
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void keptAdvancementsAreStillThere() throws Exception {
        Set<String> mine = new HashSet<>();
        for (Path p : overrides()) mine.add(id(p));
        for (String id : KEPT) {
            assertNotNull(vanilla(id), id);
            assertFalse(mine.contains(id), id);
        }
    }

    @Test
    void everyParentOfAnOverrideExists() throws Exception {
        for (Path p : overrides()) {
            JsonObject real = vanilla(id(p));
            if (real != null && real.has("parent")) {
                String parent = real.get("parent").getAsString().replace("minecraft:", "");
                assertNotNull(vanilla(parent), id(p) + " -> " + parent);
            }
        }
    }
}
