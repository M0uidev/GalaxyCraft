package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.net.ToadCodec;
import dev.moui.galaxycraft.toad.ToadRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Toads between host and friends. The host's ToadStore is the truth: every second the host sends each guest what
 * changed (all of it the first time); a guest keeps a mirror that is never saved, draws from it, talks to the Toads
 * by itself and asks the host for what only the host can do (make a Toad stand still, open its merchant screen).
 * Client thread only.
 */
final class MpToads {
    private MpToads() {}

    /** Host: what each guest was last told: Toad id -> a digest of its record. */
    private static final Map<UUID, Map<String, String>> told = new HashMap<>();

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.ToadSync.TYPE, (m, ctx) -> ctx.client().execute(() -> {
            try {
                ToadCodec.Body b = ToadCodec.decode(m.data());
                ToadClient.mirrorApply(b.full(), b.puts(), b.gone());
            } catch (ToadCodec.Bad e) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] a bad Toad list from the host: {}", e.getMessage());
            }
        }));
    }

    static void reset() {
        told.clear();
    }

    private static String digest(ToadRecord r) {
        return r.type().id() + r.stage() + java.util.Arrays.toString(r.pos()) + java.util.Arrays.toString(r.rot())
                + (r.home() == null ? "" : java.util.Arrays.toString(r.home()));
    }

    /** Host, every client tick: once a second the guests hear what changed in the Toad store. */
    static void hostTick(int now) {
        if (now % 20 != 0) return;
        List<UUID> guests = MpServer.memberList();
        told.keySet().removeIf(g -> !guests.contains(g));
        if (guests.isEmpty()) return;
        List<ToadRecord> all = ToadClient.toads();
        Map<String, String> current = new HashMap<>();
        for (ToadRecord r : all) current.put(r.id(), digest(r));
        for (UUID g : guests) {
            Map<String, String> was = told.get(g);
            boolean full = was == null;
            List<ToadRecord> puts = new ArrayList<>();
            for (ToadRecord r : all) if (full || !current.get(r.id()).equals(was.get(r.id()))) puts.add(r);
            List<String> gone = new ArrayList<>();
            if (!full) for (String id : was.keySet()) if (!current.containsKey(id)) gone.add(id);
            if (!full && puts.isEmpty() && gone.isEmpty()) continue;
            MpServer.toadSync(g, dev.moui.galaxycraft.net.ToadCodec.encode(full, puts, gone));
            told.put(g, current);
        }
    }

    /** Guest: a Toad to stand still or walk again (while it is talked to). */
    static void stand(String id, boolean on) {
        ClientPlayNetworking.send(new MpPayloads.ToadDo(on ? MpPayloads.ToadDo.STAND : MpPayloads.ToadDo.FREE, id, "", 0));
    }

    /** Guest: the host opens this Toad's merchant screen on us. */
    static void trade(ToadRecord toad, int tier) {
        ClientPlayNetworking.send(new MpPayloads.ToadDo(MpPayloads.ToadDo.TRADE, toad.id(), toad.type().id(), tier));
    }
}
