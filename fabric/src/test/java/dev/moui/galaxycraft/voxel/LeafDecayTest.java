package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeafDecayTest {
    /** CubeBlocks with glass as leaves, obsidian as logs and a slab as leaves a player placed. */
    private static final Blocks B = new Blocks() {
        final Blocks c = CubeBlocks.INSTANCE;
        @Override public BlockInfo info(int id) { return c.info(id); }
        @Override public int id(Material m) { return c.id(m); }
        @Override public Material material(int id) { return c.material(id); }
        @Override public int fluidState(int fluid, int level) { return c.fluidState(fluid, level); }
        @Override public boolean faceVisible(int id, int n, int side) { return c.faceVisible(id, n, side); }
        @Override public String name(int id) { return c.name(id); }
        @Override public int parse(String name) { return c.parse(name); }
        @Override public int atlasColumns() { return c.atlasColumns(); }
        @Override public int atlasRows() { return c.atlasRows(); }
        @Override public boolean leaves(int id) { return id == CubeBlocks.GLASS || id == CubeBlocks.SLAB; }
        @Override public boolean log(int id) { return id == c.id(Material.OBSIDIAN); }
        @Override public boolean persistentLeaves(int id) { return id == CubeBlocks.SLAB; }
    };
    private static final int LEAF = CubeBlocks.GLASS, PLACED = CubeBlocks.SLAB, LOG = B.id(Material.OBSIDIAN);

    private static VoxelPlanet planet() {
        return VoxelPlanet.ofRadius(16, B);
    }

    /** A cell in the air above the middle of a column, with room above. */
    private static int base(VoxelPlanet p) {
        int layers = p.grid.layers;
        return (p.grid.cellCount() / layers / 2) * layers + layers - 20;
    }

    /** A log at base and leaves in a column above it, up to top (cells above are +1 each). */
    private static int trunkWithLeaves(VoxelPlanet p, int top) {
        int b = base(p);
        p.set(b, LOG);
        for (int k = 1; k <= top; k++) p.set(b + k, LEAF);
        return b;
    }

    @Test void leavesWithALogAreHeld() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 6);
        assertTrue(LeafDecay.held(p, b + 6));
        assertEquals(0, LeafDecay.orphans(p, b + 3).length);
    }

    @Test void aLeafSevenStepsFromTheLogFalls() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 7);
        assertTrue(LeafDecay.held(p, b + 6));
        assertFalse(LeafDecay.held(p, b + 7));
    }

    @Test void removingTheLogDropsEveryLeafItHeld() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 5);
        p.set(b, Blocks.AIR);
        int[] gone = LeafDecay.orphans(p, b);
        assertEquals(5, gone.length, Arrays.toString(gone));
    }

    @Test void twoLogsHoldOneCrown() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 5);
        p.set(b + 6, LOG); // a second tree's log on top of the crown
        p.set(b, Blocks.AIR);
        assertEquals(0, LeafDecay.orphans(p, b).length);
    }

    @Test void persistentLeavesStayButCarryTheDistance() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 3);
        p.set(b + 4, PLACED);
        p.set(b + 5, LEAF);
        p.set(b, Blocks.AIR);
        int[] gone = LeafDecay.orphans(p, b);
        assertTrue(Arrays.stream(gone).noneMatch(c -> c == b + 4), "the player's leaf stays");
        assertEquals(List.of(b + 1, b + 2, b + 3, b + 5), Arrays.stream(gone).sorted().boxed().toList());
    }

    @Test void leafWithNoLogAtLoadIsLeftAlone() {
        VoxelPlanet p = planet();
        int b = base(p);
        p.set(b + 1, LEAF); // an old saved leaf with no log, far from any change
        p.set(b + 50, LOG);
        p.set(b + 50, Blocks.AIR);
        assertEquals(0, LeafDecay.orphans(p, b + 50).length);
    }

    @Test void theQueueLetsLeavesGoOverSeveralTicksNotAtOnce() {
        VoxelPlanet p = planet();
        int b = trunkWithLeaves(p, 5);
        p.set(b, Blocks.AIR);
        LeafDecay.Queue q = new LeafDecay.Queue();
        q.touch(p, b);
        assertEquals(0, q.tick(p).length, "not on the very first tick");
        int total = 0;
        for (int t = 0; t < 60; t++) total += q.tick(p).length;
        assertEquals(5, total);
    }
}
