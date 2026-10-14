package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.GuestFolder;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.Protocol;
import dev.moui.galaxycraft.voxel.GalaxySave;
import dev.moui.galaxycraft.voxel.PlanetStore;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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

    private MpClient() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Hello.TYPE,
                (hello, ctx) -> ctx.client().execute(() -> onHello(ctx.client(), hello)));
    }

    static boolean connected() {
        return connected;
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
            GalaxySave save = GalaxySave.of(dir);
            save.markMade();
            // The same galaxy as last time keeps its cached planets (and its saved spot); a changed one is rewritten.
            if (!save.galaxy().map(galaxy::equals).orElse(false)) save.writeGalaxy(galaxy);
            PlanetClient.enterWorld(save);
            connected = true;
            EnteringScreen.show(client);
            ack(true);
        } catch (IOException | RuntimeException e) {
            GalaxyCraft.LOG.warn("Could not enter the host's galaxy: {}", e.toString());
            ack(false);
        }
    }

    private static void ack(boolean ok) {
        ClientPlayNetworking.send(new MpPayloads.HelloAck(Protocol.VERSION, ok));
    }

    /** Left the world (any way). */
    static void reset() {
        connected = false;
        helloWait = 0;
    }
}
