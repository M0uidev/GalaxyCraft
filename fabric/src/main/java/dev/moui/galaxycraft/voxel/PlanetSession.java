package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import org.joml.Vector3d;

/**
 * The voxel planet as the mod plays it: where it is in the galaxy, what to send the host
 * (GXC_MSG_PLANET / CHUNK / PLANET_TP, in order) and the player's edits. Galaxy units outside,
 * blocks inside {@link VoxelPlanet}. No Minecraft types, so it is unit tested.
 */
public final class PlanetSession {
    public record Msg(int type, byte[] payload) {}

    /** Gravity reaches this far above the surface, blocks; the planet spawns beyond it. */
    public static final double GRAVITY_ABOVE = 40, SPAWN_ABOVE = 64;
    public static final double REACH = 4.5;

    private final double unitsPerBlock;
    private final Deque<Msg> outbox = new ArrayDeque<>();
    private VoxelPlanet planet;
    private Vector3d center;
    private int id;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;

    public PlanetSession(double unitsPerBlock) {
        this.unitsPerBlock = unitsPerBlock;
    }

    public boolean active() {
        return planet != null;
    }

    public VoxelPlanet planet() {
        return planet;
    }

    public Vector3d center() {
        return center;
    }

    /** A new planet above the player (out of its gravity's reach), replacing any other. */
    public void spawn(Vector3d feetGal, Vector3d upGal) {
        planet = VoxelPlanet.standard();
        id++;
        double above = (planet.surface() + SPAWN_ABOVE) * unitsPerBlock;
        center = new Vector3d(upGal).normalize().mul(above).add(feetGal);
        sendAll();
    }

    public void remove() {
        if (planet == null) return;
        planet = null;
        outbox.clear();
        outbox.add(new Msg(Layout.MSG_PLANET, planetPayload(0)));
    }

    /** Mario onto the surface (after the chunks already queued). */
    public void teleport() {
        if (planet != null) outbox.add(new Msg(Layout.MSG_PLANET_TP, new byte[0]));
    }

    /** Once per tick: a new scene or host lost everything, so it is all sent again; then the edits. */
    public void update(int sceneId, int hostPid) {
        if (planet == null) return;
        if (sceneId != scene || hostPid != host) {
            scene = sceneId;
            host = hostPid;
            outbox.clear();
            sendAll();
        }
        for (int c : planet.takeDirty()) {
            PlanetMesher.ChunkMesh m = PlanetMesher.mesh(planet, c, unitsPerBlock);
            ByteBuffer b = ByteBuffer.allocate(16 + m.displayList().length + m.kcl().length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(c).putInt(planet.version(c)).putInt(m.displayList().length).putInt(m.kcl().length);
            b.put(m.displayList()).put(m.kcl());
            outbox.add(new Msg(Layout.MSG_CHUNK, b.array()));
        }
    }

    /** Next message to send, or null; {@link #sent()} once the ring took it. */
    public Msg peek() {
        return outbox.peek();
    }

    public void sent() {
        outbox.poll();
    }

    public int queued() {
        return outbox.size();
    }

    /** Breaks the block the eye looks at. False if none in reach or it is unbreakable. */
    public boolean breakBlock(Vector3d eyeGal, Vector3d lookGal) {
        PlanetRaycast.Hit h = cast(eyeGal, lookGal);
        if (h == null || !planet.get(h.hit()).breakable()) return false;
        planet.set(h.hit(), Material.AIR);
        return true;
    }

    /** Places m against the block the eye looks at, unless Mario stands in that cell. */
    public boolean placeBlock(Vector3d eyeGal, Vector3d lookGal, Material m, Vector3d marioFeetGal) {
        PlanetRaycast.Hit h = cast(eyeGal, lookGal);
        if (h == null || h.before() < 0 || m == null || !m.solid()) return false;
        Vector3d feet = local(marioFeetGal);
        Vector3d up = new Vector3d(feet).normalize();
        for (double y : new double[] {0.1, 0.9, 1.7})
            if (planet.grid.cellAt(new Vector3d(up).mul(y).add(feet)) == h.before()) return false;
        planet.set(h.before(), m);
        return true;
    }

    private PlanetRaycast.Hit cast(Vector3d eyeGal, Vector3d lookGal) {
        if (planet == null) return null;
        return PlanetRaycast.cast(planet, local(eyeGal), new Vector3d(lookGal).normalize(), REACH);
    }

    private Vector3d local(Vector3d gal) {
        return new Vector3d(gal).sub(center).div(unitsPerBlock);
    }

    private void sendAll() {
        outbox.add(new Msg(Layout.MSG_PLANET, planetPayload(id)));
        planet.markAllDirty();
    }

    private byte[] planetPayload(int planetId) {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(planetId);
        Vector3d c = center == null ? new Vector3d() : center;
        b.putFloat((float) c.x).putFloat((float) c.y).putFloat((float) c.z);
        double surface = planet == null ? 0 : planet.surface();
        b.putFloat((float) (surface * unitsPerBlock)).putFloat((float) ((surface + GRAVITY_ABOVE) * unitsPerBlock));
        return b.array();
    }
}
