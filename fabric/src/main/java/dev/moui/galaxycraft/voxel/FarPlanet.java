package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import org.joml.Vector3d;

/**
 * A planet of the galaxy's catalog that is only drawn: far from Mario, it is a planet record with
 * no gravity and no chunks (the game keeps its far view alone), and its six faces as far view
 * parts in slots 0..5 (PlanetLod.coarse). Its id comes from the same pool as PlanetSession's.
 */
public final class FarPlanet {
    private final Vector3d center;
    private final double surface, unitsPerBlock;
    private int id;
    private PlanetLod.Part[] parts;
    private int patches, version;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;
    private final Deque<PlanetSession.Msg> queue = new ArrayDeque<>();

    /** center: galaxy units; surface: radius of its ground, blocks. */
    public FarPlanet(Vector3d center, double surface, double unitsPerBlock) {
        this.center = new Vector3d(center);
        this.surface = surface;
        this.unitsPerBlock = unitsPerBlock;
        id = PlanetSession.takeId();
    }

    public int id() {
        return id;
    }

    public Vector3d center() {
        return center;
    }

    /** Patches along a face's edge of the mesh it has (0: none yet). */
    public int patches() {
        return patches;
    }

    /** Its faces at that many patches: sent again (the game takes the newer version). */
    public void mesh(int patches, PlanetLod.Part[] parts) {
        this.patches = patches;
        this.parts = parts;
        version++;
        queue.removeIf(m -> m.type() == Layout.MSG_CHUNK);
        if (id != 0 && scene != Integer.MIN_VALUE) queueParts();
    }

    /** Once per tick: a new scene or host has nothing of it, so it is all sent again. */
    public void update(int sceneId, int hostPid) {
        if (id == 0 || sceneId == scene && hostPid == host) return;
        scene = sceneId;
        host = hostPid;
        queue.clear();
        queue.add(new PlanetSession.Msg(Layout.MSG_PLANET, payload(0)));
        queueParts();
    }

    /** The game drops it; its id is free again. */
    public void remove() {
        if (id == 0) return;
        queue.clear();
        queue.add(new PlanetSession.Msg(Layout.MSG_PLANET, payload(PlanetSession.PLANET_GONE)));
        PlanetSession.freeId(id);
        id = 0;
    }

    public PlanetSession.Msg peek() {
        return queue.peek();
    }

    public void sent() {
        queue.poll();
    }

    public int queued() {
        return queue.size();
    }

    private void queueParts() {
        if (parts == null) return;
        for (int f = 0; f < parts.length; f++) {
            byte[] dl = parts[f].displayList();
            ByteBuffer b = ByteBuffer.allocate(32 + dl.length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(id << 24 | PlanetSession.FAR_VIEW | f).putInt(version).putInt(dl.length).putInt(0);
            for (float x : parts[f].sphere()) b.putFloat(x);
            b.put(dl);
            queue.add(new PlanetSession.Msg(Layout.MSG_CHUNK, b.array()));
        }
    }

    /** GxcPlanet as PlanetSession's: no gravity, no chunks, no occluder; flags big-endian. */
    private byte[] payload(int flags) {
        ByteBuffer b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(id);
        b.putFloat((float) center.x).putFloat((float) center.y).putFloat((float) center.z);
        b.putFloat((float) (surface * unitsPerBlock)).putFloat(0).putInt(0).putFloat(0).putFloat(0);
        b.order(ByteOrder.BIG_ENDIAN).putInt(flags);
        return b.array();
    }
}
