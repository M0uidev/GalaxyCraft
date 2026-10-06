package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MiningTest {
    static final Object PICKAXE = "pickaxe", SHOVEL = "shovel";

    /** Holds the button on cell for ticks ticks (pressed on the first): the steps. */
    static List<Mining.Step> hold(Mining m, int cell, int block, Object tool, double perTick, boolean creative, int ticks) {
        List<Mining.Step> out = new ArrayList<>();
        for (int t = 0; t < ticks; t++) out.add(m.tick(t == 0, true, cell, block, tool, perTick, creative));
        return out;
    }

    static int brokenAt(List<Mining.Step> steps) {
        for (int t = 0; t < steps.size(); t++) if (steps.get(t).broke() >= 0) return t;
        return -1;
    }

    @Test void stoneWithAWoodenPickaxeTakesMinecraftsTime() {
        // Stone (hardness 1.5), wooden pickaxe (speed 2), right tool: 2 / 1.5 / 30 a tick, 23 ticks.
        double perTick = 2 / 1.5 / 30;
        List<Mining.Step> steps = hold(new Mining(), 7, 1, PICKAXE, perTick, false, 40);
        assertEquals(22, brokenAt(steps), "the click's tick is the first of 23");
        assertEquals(7, steps.get(22).broke());
        assertEquals(1, steps.stream().filter(s -> s.broke() >= 0).count(), "nothing else in the cell");
    }

    @Test void crackStagesGrowWithProgress() {
        double perTick = 0.05; // 20 ticks
        List<Mining.Step> steps = hold(new Mining(), 7, 1, PICKAXE, perTick, false, 20);
        assertEquals(-1, steps.get(0).stage(), "a twentieth: no crack yet");
        assertEquals(7, steps.get(0).crack());
        assertEquals(0, steps.get(1).stage(), "a tenth");
        assertEquals(4, steps.get(10).stage(), "eleven twentieths");
        assertEquals(8, steps.get(18).stage(), "nineteen twentieths");
        int last = -1;
        for (Mining.Step s : steps.subList(0, 19)) {
            assertTrue(s.stage() >= last, "never back");
            last = s.stage();
        }
        assertEquals(-1, steps.get(19).crack(), "broken: no crack");
        assertEquals(-1, steps.get(19).stage());
    }

    @Test void hitSoundsEveryFourTicks() {
        List<Mining.Step> steps = hold(new Mining(), 7, 1, PICKAXE, 0.01, false, 12);
        for (int t = 0; t < 12; t++) assertEquals(t % 4 == 0, steps.get(t).hitSound(), "tick " + t);
        assertTrue(steps.stream().allMatch(Mining.Step::working), "particles and swings all along");
    }

    @Test void holdingItDownBreaksOneBlockAfterAnotherWithTheDelay() {
        // A shovel with Efficiency on dirt: 4 ticks a block, 5 ticks of delay, and the tick the next one starts.
        Mining m = new Mining();
        List<Integer> broke = new ArrayList<>();
        int cell = 100;
        for (int t = 0; t < 60; t++) {
            Mining.Step s = m.tick(t == 0, true, cell, 2, SHOVEL, 0.26, false);
            if (s.broke() >= 0) {
                broke.add(t);
                cell++; // the next block of the tunnel comes under the crosshair
            }
        }
        assertEquals(List.of(3, 13, 23, 33, 43, 53), broke, "a block every 10 ticks");
    }

    @Test void instantBlocksGoOneATick() {
        // Short grass: hardness 0 (progress infinite), and Efficiency V + Haste II on stone.
        Mining m = new Mining();
        int cell = 0;
        List<Integer> broke = new ArrayList<>();
        for (int t = 0; t < 5; t++) {
            Mining.Step s = m.tick(t == 0, true, cell, 3, SHOVEL, Double.POSITIVE_INFINITY, false);
            if (s.broke() >= 0) {
                broke.add(t);
                cell++;
            }
        }
        assertEquals(List.of(0, 1, 2, 3, 4), broke);
    }

    @Test void creativeBreaksEveryBlockAtOnceThenWaits() {
        Mining m = new Mining();
        int cell = 0;
        List<Integer> broke = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            Mining.Step s = m.tick(t == 0, true, cell, 1, PICKAXE, 0.0001, true);
            if (s.broke() >= 0) {
                broke.add(t);
                cell++;
            }
        }
        // As Minecraft: the click's own tick counts down the delay once, a held break's does not.
        assertEquals(List.of(0, 5, 11), broke);
    }

    @Test void lookingAwayOrLettingGoStartsOver() {
        Mining m = new Mining();
        hold(m, 7, 1, PICKAXE, 0.1, false, 5);
        assertEquals(4, m.stage(), "half of it");
        Mining.Step other = m.tick(false, true, 8, 1, PICKAXE, 0.1, false);
        assertEquals(8, other.crack(), "the new block");
        assertEquals(-1, other.stage(), "from nothing");
        hold(m, 8, 1, PICKAXE, 0.1, false, 5);
        assertEquals(Mining.Step.NONE, m.tick(false, false, 8, 1, PICKAXE, 0.1, false), "let go");
        assertEquals(-1, m.stage());
        Mining.Step back = m.tick(true, true, 8, 1, PICKAXE, 0.1, false);
        assertEquals(0, back.stage(), "healed: only this tick's tenth");
    }

    @Test void anotherToolOrAnotherBlockStartsOver() {
        Mining m = new Mining();
        hold(m, 7, 1, PICKAXE, 0.1, false, 5);
        assertEquals(-1, m.tick(false, true, 7, 1, SHOVEL, 0.1, false).stage(), "another tool");
        hold(m, 7, 1, SHOVEL, 0.1, false, 4);
        assertTrue(m.stage() >= 2);
        assertEquals(-1, m.tick(false, true, 7, 9, SHOVEL, 0.1, false).stage(), "a door opened: another block");
    }

    @Test void unbreakableBlocksNeverBreakButAreHit() {
        List<Mining.Step> steps = hold(new Mining(), 7, 1, PICKAXE, 0, false, 100);
        assertEquals(-1, brokenAt(steps));
        assertTrue(steps.stream().allMatch(s -> s.stage() == -1), "no cracks on bedrock");
        assertTrue(steps.get(4).hitSound());
    }

    @Test void nothingAimedAtStops() {
        Mining m = new Mining();
        hold(m, 7, 1, PICKAXE, 0.1, false, 5);
        assertEquals(Mining.Step.NONE, m.tick(false, true, -1, -1, PICKAXE, 0, false));
        assertEquals(-1, m.stage());
    }
}
