package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.GuestFolder;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.PeerState;
import dev.moui.galaxycraft.net.Protocol;
import dev.moui.galaxycraft.net.SkinCodec;
import dev.moui.galaxycraft.net.StateSampler;
import dev.moui.galaxycraft.net.Wire;
import dev.moui.galaxycraft.voxel.GalaxySave;
import dev.moui.galaxycraft.voxel.PlanetStore;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;

/**
 * The player's end of multiplayer: entering the host's galaxy from its Hello (a guest), and the
 * shared state of the others. Single player never gets here beyond {@link #reset()}.
 */
final class MpClient {
    /** Client ticks a guest waits for Hello after joining a server that speaks our protocol (5 s). */
    private static final int HELLO_TICKS = 100;

    /** This connection is a host's galaxy and we are in it. */
    private static boolean connected;
    private static int helloWait;
    /** This connection is to a server that is not our own, so one Hello is expected (and only one). */
    private static boolean accepting;

    private MpClient() {}

    /** The other players, as the server last told us. */
    static final RemotePlayers remotes = new RemotePlayers();

    /** A state every 2 ticks: 10 a second. */
    private static final int SEND_TICKS = 2;
    private static final StateSampler sampler = new StateSampler(SEND_TICKS);
    private static int seq, ticksSinceSend;
    private static Vector3d lastSent;

    /** The custom skins (deflated pixels) the others wear, as the server last said. */
    private static final java.util.Map<java.util.UUID, byte[]> skinData = new java.util.HashMap<>();
    /** The skin we last told the others about (null: none, or nothing said yet). */
    private static SkinClient.Skin lastSkin;
    private static boolean wasSending;
    private static int skinCooldown;

    /** Another player's custom skin as sent (SkinCodec), or null. */
    static byte[] skinOf(java.util.UUID id) {
        return skinData.get(id);
    }

    private static void onSkin(MpPayloads.Skin skin) {
        if (!SkinCodec.acceptable(skin.data())) return;
        if (SkinCodec.isClear(skin.data())) skinData.remove(skin.id());
        else if (java.util.Arrays.equals(skinData.get(skin.id()), skin.data())) return; // the same again: keep the texture made
        else if (skinData.containsKey(skin.id()) || skinData.size() < Protocol.MAX_PLAYERS) skinData.put(skin.id(), skin.data());
    }

    /** Tells the others about our /skin when it changes, and once when we start telling them anything. */
    private static void syncSkin(Minecraft client) {
        if (skinCooldown > 0) {
            skinCooldown--;
            return;
        }
        SkinClient.Skin now = SkinClient.current();
        if (now == lastSkin || client.player == null) return;
        lastSkin = now;
        skinCooldown = 25; // the server takes about one a second: a change made sooner waits for the next turn
        if (now == null) {
            ClientPlayNetworking.send(new MpPayloads.Skin(client.player.getUUID(), new byte[0]));
        } else if (now.argb().length == SkinCodec.PIXELS) {
            byte[] data = SkinCodec.encode(now.argb());
            if (data.length <= Protocol.SKIN_MAX) ClientPlayNetworking.send(new MpPayloads.Skin(client.player.getUUID(), data));
        }
    }

    static void register() {
        MpPlanets.register();
        MpPlanetHost.register();
        HostPlanets.register();
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Skin.TYPE,
                (skin, ctx) -> ctx.client().execute(() -> onSkin(skin)));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Hello.TYPE,
                (hello, ctx) -> ctx.client().execute(() -> onHello(ctx.client(), hello)));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Peer.TYPE,
                (peer, ctx) -> ctx.client().execute(() -> onPeer(ctx.client(), peer)));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Gone.TYPE,
                (gone, ctx) -> ctx.client().execute(() -> remotes.remove(gone.id())));
    }

    private static void onPeer(Minecraft client, MpPayloads.Peer peer) {
        if (client.player == null || peer.id().equals(client.player.getUUID())) return;
        try {
            remotes.push(peer.id(), Wire.decode(peer.data()), System.nanoTime());
        } catch (Wire.Malformed e) {
            GalaxyCraft.LOG.debug("Dropped a malformed peer state: {}", e.getMessage());
        }
    }

    /** Whether to tell the others where we are: a guest in a host's galaxy, or a host with its world open to others. */
    private static boolean sending(Minecraft client) {
        if (connected) return true;
        var server = client.getSingleplayerServer();
        return server != null && server.isPublished();
    }

    /** This tick's state to the server, every {@link #SEND_TICKS} ticks. */
    private static void sendState(Minecraft client) {
        ticksSinceSend++;
        if (!sampler.due()) return;
        LocalPlayer p = client.player;
        Vector3d at = PlanetClient.marioUniverse();
        GravityFrame frame = GalaxyCraftClient.frame();
        if (p == null || at == null || frame == null) return;
        Vector3d up = frame.upGal();
        float vx = 0, vy = 0, vz = 0;
        if (lastSent != null) {
            vx = StateSampler.speed(at.x - lastSent.x, ticksSinceSend);
            vy = StateSampler.speed(at.y - lastSent.y, ticksSinceSend);
            vz = StateSampler.speed(at.z - lastSent.z, ticksSinceSend);
        }
        lastSent = new Vector3d(at);
        ticksSinceSend = 0;
        // Where the head points, in the universe's axes: a Minecraft yaw means something different in each client's frame.
        var eye = p.getLookAngle();
        Vector3d look = frame.dirToGal(new Vector3d(eye.x, eye.y, eye.z)).normalize();
        PeerState s = new PeerState(seq++, at.x, at.y, at.z, (float) up.x, (float) up.y, (float) up.z,
                (float) look.x, (float) look.y, (float) look.z, vx, vy, vz, GalaxyCraftClient.walking() ? Protocol.MODE_STEVE : Protocol.MODE_MARIO,
                StateSampler.flags(p.isShiftKeyDown(), p.isSprinting(), p.isSwimming(), p.isFallFlying(), p.onGround(),
                        ShipClient.aboard().isPresent()));
        if (s.valid()) ClientPlayNetworking.send(new MpPayloads.State(Wire.encode(s)));
    }

    static boolean connected() {
        return connected;
    }

    /** The moment of joining (before anything the server sends): a server that is not ours may send its Hello. */
    static void onJoin(Minecraft client) {
        connected = false;
        accepting = client.getSingleplayerServer() == null;
    }

    /**
     * Joined a server that is not our own integrated one. If it speaks our protocol its Hello is on
     * the way: wait for it (the dark entering screen stays). If not it is a plain Minecraft server,
     * which works as it always did.
     */
    static void joinedRemote(Minecraft client) {
        if (ClientPlayNetworking.canSend(MpPayloads.HelloAck.TYPE)) {
            helloWait = HELLO_TICKS;
            return;
        }
        GalaxyCraft.LOG.info("This server has no Super Minecraft Galaxy: playing it as plain Minecraft");
        EnteringScreen.show(client);
    }

    static void tick(Minecraft client) {
        MpPlanets.tick();
        HostPlanets.tick();
        boolean on = sending(client);
        if (on && !wasSending) lastSkin = null; // a new audience: say again what we wear
        wasSending = on;
        if (on) {
            syncSkin(client);
            sendState(client);
        }
        if (helloWait <= 0) return;
        if (connected) {
            helloWait = 0;
            return;
        }
        if (--helloWait == 0) {
            GalaxyCraft.LOG.warn("The host's Hello never came");
            EnteringScreen.show(client); // no galaxy: lets go of the dark screen
            if (client.getConnection() != null)
                client.getConnection().getConnection().disconnect(Component.literal(
                        "The host's Super Minecraft Galaxy did not answer. Is the mod installed, the same version as yours?"));
        }
    }

    static void onHello(Minecraft client, MpPayloads.Hello hello) {
        if (!accepting || connected) { // one Hello per connection, from a server that is not our own
            GalaxyCraft.LOG.warn("[GalaxyCraft mp] a Hello that was not expected: ignored");
            return;
        }
        accepting = false;
        var refusal = Protocol.refusal(Protocol.VERSION, hello.version());
        if (refusal.isPresent()) {
            GalaxyCraft.LOG.warn("{}", refusal.get());
            ack(false);
            return;
        }
        try {
            Path dir = GuestFolder.of(PlanetStore.dataDir(System.getProperty("os.name"), System::getenv,
                    System.getProperty("user.home")), hello.worldId());
            GalaxySave.Galaxy galaxy = GalaxySave.parseGalaxy(hello.galaxyJson())
                    .orElseThrow(() -> new IllegalArgumentException("the host's galaxy is not usable"));
            var problem = GalaxySave.problem(galaxy);
            if (problem.isPresent()) throw new IllegalArgumentException(problem.get());
            GalaxySave save = GalaxySave.of(dir);
            // The same galaxy as last time keeps its cached planets (and its saved spot). A changed one is
            // another galaxy under this id (a copied world, an edited one): its cached planets are not its own.
            boolean same = save.galaxy().map(galaxy::equals).orElse(false);
            if (!same) deleteTree(save.planets());
            save.markMade();
            if (!same) save.writeGalaxy(galaxy);
            PlanetClient.enterWorld(save);
            if (PlanetClient.galaxy() != save) throw new IllegalStateException("this game keeps its planets in a fixed folder");
            connected = true;
            GalaxyCraft.LOG.info("[GalaxyCraft mp] entered the host's galaxy {}", hello.worldId());
            EnteringScreen.show(client);
            ack(true);
        } catch (IOException | RuntimeException e) {
            GalaxyCraft.LOG.warn("Could not enter the host's galaxy: {}", e.toString());
            ack(false);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!java.nio.file.Files.exists(dir)) return;
        try (var walk = java.nio.file.Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p);
        }
    }

    private static void ack(boolean ok) {
        ClientPlayNetworking.send(new MpPayloads.HelloAck(Protocol.VERSION, ok));
    }

    /** Left the world (any way). */
    static void reset() {
        MpPlanets.reset();
        HostPlanets.reset();
        connected = false;
        accepting = false;
        skinCooldown = 0;
        helloWait = 0;
        remotes.clear();
        skinData.clear();
        lastSkin = null;
        wasSending = false;
        lastSent = null;
        ticksSinceSend = 0;
        sampler.restart();
    }
}
