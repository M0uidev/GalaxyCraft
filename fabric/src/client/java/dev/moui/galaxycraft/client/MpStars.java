package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.launchstar.StarOps;
import dev.moui.galaxycraft.launchstar.StarRecord;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.net.StarCodec;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Launch Stars between host and friends. The host's StarStore is the truth: the host's game applies a guest's
 * request (StarDo) and tells every guest (StarPut / StarGone); a guest keeps a mirror that is never saved.
 * The flight itself stays on each player's own client (the route is a function of the two records).
 */
final class MpStars {
    private MpStars() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.StarList.TYPE, (m, ctx) -> ctx.client().execute(() -> {
            try {
                LaunchStarClient.mirrorList(StarCodec.decodeList(m.data()));
            } catch (StarCodec.Bad e) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] a bad star list from the host: {}", e.getMessage());
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.StarPut.TYPE, (m, ctx) -> ctx.client().execute(() -> {
            try {
                LaunchStarClient.mirrorPut(StarCodec.decode(m.data()));
            } catch (StarCodec.Bad e) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] a bad star from the host: {}", e.getMessage());
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.StarGone.TYPE,
                (m, ctx) -> ctx.client().execute(() -> LaunchStarClient.mirrorGone(m.id())));
        MpServer.setStarSink(new MpServer.StarSink() {
            @Override
            public void request(UUID guest, StarOps.Request r) {
                Minecraft.getInstance().execute(() -> LaunchStarClient.hostApply(guest, r));
            }

            @Override
            public void joined(UUID guest) {
                Minecraft.getInstance().execute(() -> MpServer.starList(guest, LaunchStarClient.stars()));
            }
        });
    }

    /** A guest asks the host; hand 0 main, 1 off. */
    static void send(int kind, int hand, String id, double[] pos, double[] up, String stage, String body, String code) {
        double[] p = pos == null ? new double[3] : pos, u = up == null ? new double[3] : up;
        ClientPlayNetworking.send(new MpPayloads.StarDo(kind, hand, id == null ? "" : id, p[0], p[1], p[2], u[0], u[1], u[2],
                stage == null ? "" : stage, body == null ? "" : body, code == null ? "" : code));
    }

    /** The host's game changed a star: every guest hears it. */
    static void published(StarRecord r) {
        MpServer.starPut(r);
    }

    static void gone(String id) {
        MpServer.starGone(id);
    }
}
