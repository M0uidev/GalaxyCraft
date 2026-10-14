package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Planet observers and the session's follower mode: what multiplayer's edit feed stands on. */
class SharedEditsTest {
    static final Vector3d MARIO = new Vector3d(0, 0, 0);

    static PlanetSession session(Blocks b) {
        PlanetSession s = new PlanetSession(80);
        s.spawn(VoxelPlanet.ofRadius(16, b), MARIO, new Vector3d(0, 1, 0));
        s.update(3, 100, MARIO);
        return s;
    }

    static Blocks leafyBlocks() {
        Blocks c = CubeBlocks.INSTANCE;
        return new Blocks() {
            @Override public BlockInfo info(int id) { return c.info(id); }
            @Override public int id(Material m) { return c.id(m); }
            @Override public Material material(int id) { return c.material(id); }
            @Override public int fluidState(int fluid, int level) { return c.fluidState(fluid, level); }
            @Override public boolean faceVisible(int id, int n, int side) { return c.faceVisible(id, n, side); }
            @Override public String name(int id) { return c.name(id); }
            @Override public int parse(String name) { return c.parse(name); }
            @Override public int atlasColumns() { return c.atlasColumns(); }
            @Override public int atlasRows() { return c.atlasRows(); }
            @Override public boolean leaves(int id) { return id == CubeBlocks.GLASS; }
            @Override public boolean log(int id) { return id == c.id(Material.OBSIDIAN); }
        };
    }

    @Test
    void observersHearEverythingButRemoteChanges() {
        Blocks b = CubeBlocks.INSTANCE;
        VoxelPlanet p = VoxelPlanet.ofRadius(16, b);
        int cell = p.grid.cellAt(new Vector3d(0, 18.5, 0));
        List<int[]> seen = new ArrayList<>(), told = new ArrayList<>();
        p.addObserver((c, id) -> seen.add(new int[] {c, id}));
        p.setListener((c, id) -> told.add(new int[] {c, id}));
        int stone = b.id(Material.STONE);
        p.set(cell, stone);
        p.setQuietly(cell + 1, stone);
        p.setFromRemote(cell + 2, stone);
        assertEquals(2, seen.size(), "set and setQuietly");
        assertEquals(1, told.size(), "only set tells the listener");
        assertEquals(stone, p.get(cell + 2), "a remote change is made all the same");
        p.removeObserver(p == null ? null : (c, id) -> {});
        assertEquals(2, seen.size());
    }

    @Test
    void aPlayersBreakIsTheOnlyChangeInTheirScope() {
        PlanetSession s = session(CubeBlocks.INSTANCE);
        List<Boolean> scope = new ArrayList<>();
        s.planet().addObserver((c, id) -> scope.add(s.playerEditing()));
        Vector3d top = new Vector3d(s.center()).add(0, 17.5 * 80, 0);
        assertTrue(s.breakBlock(top, new Vector3d(0, -1, 0)));
        assertFalse(scope.isEmpty());
        assertTrue(scope.stream().allMatch(x -> x), "in scope");
        scope.clear();
        int cell = s.planet().grid.cellAt(new Vector3d(0, 15.5, 0));
        s.applyExternal(cell, CubeBlocks.INSTANCE.id(Material.STONE));
        s.applyShared(cell, Blocks.AIR);
        assertEquals(2, scope.size());
        assertTrue(scope.stream().noneMatch(x -> x), "not the player's");
        assertFalse(s.playerEditing());
    }

    @Test
    void applyRemoteChangesTheCellWithoutTellingOrSaving() {
        PlanetSession s = session(CubeBlocks.INSTANCE);
        s.clean();
        List<int[]> seen = new ArrayList<>();
        s.planet().addObserver((c, id) -> seen.add(new int[] {c, id}));
        int cell = s.planet().grid.cellAt(new Vector3d(0, 15.5, 0));
        s.applyRemote(cell, Blocks.AIR);
        assertEquals(Blocks.AIR, s.planet().get(cell));
        assertTrue(seen.isEmpty());
        assertFalse(s.unsaved());
        s.applyShared(cell, CubeBlocks.INSTANCE.id(Material.STONE));
        assertTrue(s.unsaved(), "the host keeps what a guest built");
        assertEquals(1, seen.size());
    }

    private static int leavesLeft(PlanetSession s, int base, boolean follower) {
        s.setFollower(follower);
        VoxelPlanet p = s.planet();
        Blocks b = p.blocks;
        int log = b.id(Material.OBSIDIAN), leaf = CubeBlocks.GLASS;
        p.set(base, log);
        for (int k = 1; k <= 3; k++) p.set(base + k, leaf);
        p.set(base, Blocks.AIR);
        s.touchLeaves(base);
        for (int t = 0; t < 80; t++) s.update(3, 100, MARIO);
        int left = 0;
        for (int k = 1; k <= 3; k++) if (p.get(base + k) == leaf) left++;
        return left;
    }

    @Test
    void aFollowerLetsNoLeafFallTheHostDoes() {
        for (boolean follower : new boolean[] {false, true}) {
            PlanetSession s = session(leafyBlocks());
            int base = s.planet().grid.cellAt(new Vector3d(0, 18.5, 0));
            assertEquals(follower ? 3 : 0, leavesLeft(s, base, follower), "follower=" + follower);
        }
    }

    @Test
    void aFollowerDoesNotRunFluids() {
        for (boolean follower : new boolean[] {false, true}) {
            PlanetSession s = session(CubeBlocks.INSTANCE);
            s.setFollower(follower);
            VoxelPlanet p = s.planet();
            int cell = p.grid.cellAt(new Vector3d(0, 20.5, 0));
            p.set(cell, Material.WATER);
            for (int t = 0; t < 60; t++) s.update(3, 100, MARIO);
            int water = 0;
            for (int c = cell - 3000; c < cell + 3000; c++) if (c >= 0 && p.info(c).isFluid()) water++;
            if (follower) assertEquals(1, water, "stays one source");
            else assertTrue(water > 1, "flows: " + water);
        }
    }
}
