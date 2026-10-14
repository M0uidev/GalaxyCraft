package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.PlanetXfer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * A guest's planets: it has no server to generate them, so the host sends each one it needs, once
 * per session, as the bytes of its save. The copy is kept in the guest's folder; the next session
 * tells the host its etag, and an unchanged planet is not sent again. All on the client's thread
 * except the file work.
 */
final class MpPlanets {
    /** A transfer with no news for this long (client ticks, 30 s) is given up: the host is gone, or busy. */
    private static final int IDLE_TICKS = 600;

    private static final class Pending {
        final int index;
        final Path file;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        PlanetXfer.Assembler assembler;
        long etag;
        int idle;

        Pending(int index, Path file) {
            this.index = index;
            this.file = file;
        }
    }

    private static final Map<Integer, Pending> pending = new HashMap<>();
    /** Planets whose copy is the host's of this session (written by another thread, so concurrent). */
    private static final Set<Integer> fresh = ConcurrentHashMap.newKeySet();

    private MpPlanets() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.PlanetHead.TYPE,
                (head, ctx) -> ctx.client().execute(() -> onHead(head)));
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.PlanetChunk.TYPE,
                (chunk, ctx) -> ctx.client().execute(() -> onChunk(chunk)));
    }

    /**
     * Completes when the planet's file in the guest's folder is the host's of this session; null if
     * too many are being waited for already (ask again next tick). Client thread.
     */
    static CompletableFuture<Void> fetch(int index, Path file) {
        if (fresh.contains(index) && Files.isRegularFile(file)) return CompletableFuture.completedFuture(null);
        Pending had = pending.get(index);
        if (had != null) return had.done;
        if (pending.size() >= PlanetXfer.MAX_ASKED) return null;
        Pending p = new Pending(index, file);
        pending.put(index, p);
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> etagOf(file)).thenAccept(etag -> mc.execute(() -> {
            if (pending.get(index) != p) return; // given up meanwhile
            p.etag = etag;
            ClientPlayNetworking.send(new MpPayloads.PlanetAsk(index, etag));
        }));
        return p.done;
    }

    /** The etag of the copy we hold, 0 if none (or unreadable). */
    private static long etagOf(Path file) {
        try {
            return Files.isRegularFile(file) ? PlanetXfer.etag(Files.readAllBytes(file)) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void onHead(MpPayloads.PlanetHead head) {
        Pending p = pending.get(head.index());
        if (p == null || p.assembler != null) return; // not asked for, or a second head
        p.idle = 0;
        switch (head.state()) {
            case PlanetXfer.STATE_SAME -> {
                if (head.etag() != p.etag || !Files.isRegularFile(p.file)) {
                    fail(p, "the host said our copy is the same, and it is not");
                    return;
                }
                finish(p);
            }
            case PlanetXfer.STATE_DATA -> {
                try {
                    p.assembler = new PlanetXfer.Assembler(head.total(), head.etag());
                } catch (IllegalArgumentException e) {
                    fail(p, e.getMessage());
                }
            }
            default -> fail(p, "the host could not make it");
        }
    }

    private static void onChunk(MpPayloads.PlanetChunk chunk) {
        Pending p = pending.get(chunk.index());
        if (p == null || p.assembler == null) return;
        p.idle = 0;
        if (!p.assembler.accept(chunk.seq(), chunk.data())) {
            fail(p, "a chunk out of order");
            return;
        }
        if (!p.assembler.done()) return;
        byte[] data = p.assembler.result();
        if (data == null) {
            fail(p, "the planet arrived damaged");
            return;
        }
        // The copy replaces the old one whole and atomically, off the game's thread.
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(p.file.getParent());
                Path tmp = p.file.resolveSibling(p.file.getFileName() + ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, p.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, PlanetClient.maker).whenComplete((v, ex) -> mc.execute(() -> {
            if (ex != null) fail(p, "could not keep it: " + ex);
            else finish(p);
        }));
    }

    private static void finish(Pending p) {
        if (pending.get(p.index) != p) return;
        pending.remove(p.index);
        fresh.add(p.index);
        GalaxyCraft.LOG.info("[GalaxyCraft mp] planet {} from the host is in", p.index);
        p.done.complete(null);
    }

    private static void fail(Pending p, String why) {
        if (pending.get(p.index) != p) return;
        pending.remove(p.index);
        GalaxyCraft.LOG.warn("[GalaxyCraft mp] planet {} from the host: {}", p.index, why);
        p.done.completeExceptionally(new IllegalStateException(why));
    }

    /** Each client tick: a transfer that went quiet is given up. */
    static void tick() {
        if (pending.isEmpty()) return;
        for (Pending p : new java.util.ArrayList<>(pending.values()))
            if (++p.idle > IDLE_TICKS) fail(p, "the host did not answer");
    }

    /** Left the world: nothing is waited for, nothing is the host's of this session any more. */
    static void reset() {
        for (Pending p : new java.util.ArrayList<>(pending.values())) fail(p, "left the world");
        fresh.clear();
    }
}
