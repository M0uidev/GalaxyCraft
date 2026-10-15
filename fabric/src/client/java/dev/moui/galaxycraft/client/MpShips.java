package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.net.ShipCodec;
import dev.moui.galaxycraft.ship.ShipOps;
import dev.moui.galaxycraft.ship.ShipRecord;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Ships between host and friends. The host's ShipStore is the truth and the host decides who flies what: a guest's
 * request (ShipDo) is applied by the host's game, which tells every guest (ShipPut, ShipPilot); a guest keeps a mirror
 * that is never saved. A flight is the pilot's own client physics; its pose goes out ten times a second (ShipPose) and
 * the host passes it on. Client thread only.
 */
final class MpShips {
    private MpShips() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.ShipList.TYPE, (m, ctx) -> ctx.client().execute(() -> {
            try {
                ShipClient.mirrorList(ShipCodec.decodeList(m.data()));
            } catch (ShipCodec.Bad e) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] a bad ship list from the host: {}", e.getMessage());
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.ShipPut.TYPE, (m, ctx) -> ctx.client().execute(() -> {
            try {
                ShipClient.mirrorPut(ShipCodec.decode(m.data()));
            } catch (ShipCodec.Bad e) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] a bad ship from the host: {}", e.getMessage());
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.ShipPilot.TYPE,
                (m, ctx) -> ctx.client().execute(() -> ShipClient.mirrorPilot(m.id(), m.who())));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.ShipPose.TYPE,
                (m, ctx) -> ctx.client().execute(() -> ShipClient.heardPose(m.id(), new double[] {m.px(), m.py(), m.pz()},
                        new double[] {m.qx(), m.qy(), m.qz(), m.qw()})));
        MpServer.setShipSink(new MpServer.ShipSink() {
            @Override
            public void place(UUID guest, ShipOps.Request r) {
                Minecraft.getInstance().execute(() -> ShipClient.hostPlace(guest, r));
            }

            @Override
            public void board(UUID guest, String shipId) {
                Minecraft.getInstance().execute(() -> ShipClient.hostBoard(guest, shipId));
            }

            @Override
            public void leave(UUID guest, String shipId, double[] pos, double[] rot) {
                Minecraft.getInstance().execute(() -> ShipClient.hostLeave(guest, shipId, pos, rot));
            }

            @Override
            public void pose(UUID guest, String shipId, double[] pos, double[] rot) {
                Minecraft.getInstance().execute(() -> ShipClient.hostPose(guest, shipId, pos, rot));
            }

            @Override
            public void joined(UUID guest) {
                Minecraft.getInstance().execute(() -> ShipClient.hostJoined(guest));
            }

            @Override
            public void left(UUID guest) {
                Minecraft.getInstance().execute(() -> ShipClient.hostLeft(guest));
            }
        });
    }

    /** A guest asks the host; hand 0 main, 1 off (a placing). */
    static void send(int kind, int hand, String id, ShipRecord at) {
        double[] p = at == null ? new double[3] : at.pos(), q = at == null ? new double[] {0, 0, 0, 1} : at.rot();
        ClientPlayNetworking.send(new MpPayloads.ShipDo(kind, hand, id == null ? "" : id, p[0], p[1], p[2], q[0], q[1], q[2], q[3],
                at == null ? "" : at.stage()));
    }

    /** The pilot (a guest) says where the ship is. */
    static void sendPose(ShipRecord r) {
        ClientPlayNetworking.send(new MpPayloads.ShipPose(r.id(), r.pos()[0], r.pos()[1], r.pos()[2], r.rot()[0], r.rot()[1], r.rot()[2],
                r.rot()[3]));
    }

    /** The host's game changed a ship (put down, parked): every guest hears it. */
    static void published(ShipRecord r) {
        MpServer.shipPut(r);
    }

    static void pilot(String shipId, String who) {
        MpServer.shipPilot(shipId, who);
    }

    /** The host is the pilot: its pose goes to every guest. */
    static void hostPose(ShipRecord r) {
        MpServer.shipPoseOut(r.id(), r.pos(), r.rot(), null);
    }
}
