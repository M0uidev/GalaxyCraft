package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.net.PlanetXfer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/**
 * The host's end of the planets: a guest asks for a catalog planet, and this game (which owns them)
 * hands the server its bytes. The planet is read from its save, or taken from the game as it is
 * now, or made for the occasion (see {@link GalaxyStream#serve}).
 */
final class MpPlanetHost {
    private MpPlanetHost() {}

    static void register() {
        MpServer.setPlanetSource(MpPlanetHost::ask);
    }

    /** On the server's thread: the work goes to the game's, where the planets are. */
    private static void ask(UUID guest, int index, long guestEtag) {
        Minecraft.getInstance().execute(() -> {
            // The planet becomes shared first, and its edit sequence is read at the same moment the bytes are taken.
            if (!HostPlanets.admit(guest, index)) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] no room to hold planet {} for a guest", index);
                MpServer.offer(guest, index, guestEtag, null);
                return;
            }
            int seq = HostPlanets.seq(index);
            CompletableFuture<byte[]> bytes = PlanetClient.serve(index);
            if (bytes == null) {
                MpServer.offer(guest, index, guestEtag, null);
                return;
            }
            bytes.whenComplete((data, ex) -> Minecraft.getInstance().execute(() -> {
                if (ex != null) GalaxyCraft.LOG.warn("[GalaxyCraft mp] planet {} for a guest: {}", index, ex.toString());
                if (data != null) HostPlanets.supply(index, data);
                MpServer.offer(guest, index, guestEtag, data == null ? null : new PlanetXfer.Offer(data, PlanetXfer.etag(data), seq));
            }));
        });
    }
}
