package dev.moui.galaxycraft.voxel;

import java.util.Objects;

/**
 * Breaking blocks as Minecraft's MultiPlayerGameMode does, a client tick at a time. Held down on a
 * block, the attack button adds the block's destroy progress every tick (its hardness against the
 * tool in hand, Efficiency, Haste: Player.getDestroySpeed) until it reaches 1 and the block
 * breaks; DELAY ticks later the next block aimed at starts. So holding the button down mines one
 * block after another at the tool's real speed. A block that breaks within a tick (short grass,
 * flowers, or anything with Efficiency V and Haste II) goes at once, one a tick; in creative mode
 * every block does, one every DELAY ticks. Aiming at another block, letting go or changing tools
 * starts over.
 *
 * No Minecraft types, so it is unit tested: the caller says what is aimed at (a cell and the block
 * in it) and how much of it a tick breaks.
 */
public final class Mining {
    /** Ticks after a block breaks before the next one starts (Minecraft's destroyDelay). */
    public static final int DELAY = 5;
    /** A hit sound every this many ticks of breaking. */
    public static final int HIT_SOUND_TICKS = 4;

    /**
     * What a tick did. broke: the cell broken (-1 none); hitSound: the aimed block's hit sound
     * plays; working: the button worked on the aimed block (Minecraft's crack particle on its side
     * and the arm's swing); crack and stage: the cell being broken and how far (Minecraft's crack
     * texture, 0..9), -1 none.
     */
    public record Step(int broke, boolean hitSound, boolean working, int crack, int stage) {
        public static final Step NONE = new Step(-1, false, false, -1, -1);
    }

    private boolean destroying;
    private int target = -1, targetBlock = -1;
    private Object targetTool;
    private double progress;
    private int ticks, delay;

    /**
     * One client tick. pressed: the button went down this tick (a click); held: it is down; cell
     * and block: what the crosshair is on (cell -1: no block); tool: what is in hand (another one
     * starts over, as Minecraft compares the stacks); perTick: the progress a tick makes on that
     * block (BlockState.getDestroyProgress: 0 never breaks, 1 or more breaks at once).
     */
    public Step tick(boolean pressed, boolean held, int cell, int block, Object tool, double perTick, boolean creative) {
        if (!held || cell < 0) {
            stop();
            return Step.NONE;
        }
        int broke = -1;
        boolean hit = false;
        if (pressed) broke = start(cell, block, tool, perTick, creative);
        // Minecraft's continueDestroyBlock, the same tick as the click too (unless the click broke it).
        if (broke < 0 || delay > 0) {
            if (delay > 0) delay--;
            else if (creative) {
                broke = cell;
                delay = DELAY;
                destroying = false;
            } else if (destroying && same(cell, block, tool)) {
                progress += perTick;
                hit = ticks % HIT_SOUND_TICKS == 0;
                ticks++;
                if (progress >= 1) {
                    broke = cell;
                    destroying = false;
                    progress = 0;
                    ticks = 0;
                    delay = DELAY;
                }
            } else broke = start(cell, block, tool, perTick, creative);
        }
        return new Step(broke, hit, true, destroying ? target : -1, stage());
    }

    /** Let go (or nothing aimed at): what was being broken heals at once, as in Minecraft. */
    public void stop() {
        destroying = false;
        progress = 0;
        ticks = 0;
    }

    /** How far the block being broken is, as Minecraft's crack stages: -1 (none or under a tenth) to 9. */
    public int stage() {
        return destroying ? Math.min(9, (int) (progress * 10) - 1) : -1;
    }

    /** Minecraft's startDestroyBlock: the cell broken at once, or -1 (breaking it starts, or goes on). */
    private int start(int cell, int block, Object tool, double perTick, boolean creative) {
        if (creative) {
            destroying = false;
            delay = DELAY;
            return cell;
        }
        if (destroying && same(cell, block, tool)) return -1;
        if (perTick >= 1) {
            destroying = false;
            return cell;
        }
        destroying = true;
        target = cell;
        targetBlock = block;
        targetTool = tool;
        progress = 0;
        ticks = 0;
        return -1;
    }

    private boolean same(int cell, int block, Object tool) {
        return cell == target && block == targetBlock && Objects.equals(tool, targetTool);
    }
}
