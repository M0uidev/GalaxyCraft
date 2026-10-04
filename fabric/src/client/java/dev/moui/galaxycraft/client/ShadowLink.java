package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3d;

/**
 * The client's end of {@link ShadowWorld}: sends the planet's changes there, applies what
 * Minecraft changed back, and keeps the blocks around Mario running.
 */
public final class ShadowLink {
    /** Blocks around Mario that Minecraft runs (its simulation distance is about this). */
    static final double RANGE = 48;
    static final int FOLLOW_TICKS = 20;

    /** The planet in focus (the one nearest Mario): it can change from one tick to the next. */
    private final java.util.function.Supplier<PlanetSession> focus;
    private VoxelPlanet attached;
    private String stage = "";
    private int seq, sinceFollow;
    /** Cells the client changed: the number of its last change, until the server has applied it. */
    private final Map<Integer, Integer> pending = new HashMap<>();

    public ShadowLink(java.util.function.Supplier<PlanetSession> focus) {
        this.focus = focus;
    }

    public boolean available() {
        return ShadowWorld.available() && attached != null;
    }

    /** Client tick: Mario at marioGal in the galaxy. */
    public void tick(String stage, Vector3d marioGal) {
        if (!ShadowWorld.available()) return;
        VoxelPlanet p = session().active() ? session().planet() : null;
        if (p != attached || !stage.equals(this.stage)) {
            if (attached != null) attached.setListener(null);
            attached = p;
            this.stage = stage;
            pending.clear();
            sinceFollow = FOLLOW_TICKS;
            if (p != null) p.setListener(this::changed);
            ShadowWorld.attach(p, stage);
        }
        for (ShadowWorld.Change c; (c = ShadowWorld.pollChange()) != null; ) {
            Integer mine = pending.get(c.cell());
            if (c.planet() == attached && (mine == null || mine <= c.seq())) session().applyExternal(c.cell(), c.id());
        }
        int applied = ShadowWorld.applied();
        pending.values().removeIf(s -> s <= applied);
        for (Runnable r; (r = ShadowWorld.pollClient()) != null; ) r.run();
        if (p != null && marioGal != null && ++sinceFollow >= FOLLOW_TICKS) {
            sinceFollow = 0;
            follow(p, marioGal);
        }
    }

    private void changed(int cell, int id) {
        pending.put(cell, ++seq);
        ShadowWorld.set(attached, cell, id, seq);
    }

    /** The shadow's columns under the planet's chunks near Mario, with a block more around for the halo. */
    private void follow(VoxelPlanet p, Vector3d marioGal) {
        Set<Long> columns = new HashSet<>();
        var map = new dev.moui.galaxycraft.shadow.ShadowMap(p.grid, stage);
        for (int chunk : session().chunksNear(marioGal, RANGE)) {
            int[] cells = p.cellsOf(chunk);
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int c : cells) {
                x0 = Math.min(x0, map.x(c));
                x1 = Math.max(x1, map.x(c));
                z0 = Math.min(z0, map.z(c));
                z1 = Math.max(z1, map.z(c));
            }
            for (int cx = (x0 - 1) >> 4; cx <= (x1 + 1) >> 4; cx++)
                for (int cz = (z0 - 1) >> 4; cz <= (z1 + 1) >> 4; cz++) columns.add(ChunkPos.pack(cx, cz));
        }
        ShadowWorld.follow(p, columns, session().cellAt(marioGal));
    }

    private PlanetSession session() {
        return focus.get();
    }
}
