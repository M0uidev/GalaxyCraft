package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.voxel.FarPlanet;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.GalaxySave;
import dev.moui.galaxycraft.voxel.LodSource;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.PlanetLayout;
import dev.moui.galaxycraft.voxel.PlanetLod;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.PlanetStore;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import dev.moui.galaxycraft.voxel.gen.SurfaceSampler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;

/**
 * A world's galaxy streamed from its catalog (GalaxySave.Galaxy): the PlanetLayout.NEAR_PLANETS
 * planets nearest Mario are complete (PlanetClient's sessions: loaded from their file, or made from
 * their recipe), the next two are made ahead, and every other one is a {@link FarPlanet}, drawn
 * with fewer patches the smaller it looks. A planet made from its recipe and never edited is not
 * saved: it comes out the same again.
 */
final class GalaxyStream {
    /** Ticks between looks at which planets are near. */
    static final int EVERY = 10;
    /** Far views built in one tick at most. */
    static final int MESHES_PER_TICK = 2;
    private static final double UNITS = 1 / GravityFrame.SCALE;

    private final GalaxySave save;
    private final PlanetStore store;
    private final String stage;
    private final List<GalaxyCatalog.Entry> entries;
    private final GalaxyCatalog.Options options;
    private final Map<Integer, FarPlanet> far = new HashMap<>();
    private final List<FarPlanet> farLeaving = new ArrayList<>();
    /** Planets being read or made off this thread: a PlanetStore.Saved or a VoxelPlanet. */
    private final Map<Integer, CompletableFuture<Object>> making = new HashMap<>();
    /** Complete planets whose far view is still up until their own is all sent. */
    private final Map<Integer, PlanetSession> promoting = new HashMap<>();
    private final Set<Integer> failed = new HashSet<>();
    /** Far views by index << 4 | patches; cells: built from the planet itself (not its recipe). */
    private final Map<Integer, PlanetLod.Part[]> meshes = new HashMap<>();
    private final Set<Integer> fromCells = new HashSet<>();
    private int ticks = EVERY - 1;
    private List<Integer> wanted = List.of(), ahead = List.of();

    GalaxyStream(GalaxySave save, PlanetStore store, String stage, GalaxySave.Galaxy galaxy) {
        this.save = save;
        this.store = store;
        this.stage = stage;
        this.options = galaxy.options();
        this.entries = new ArrayList<>(galaxy.entries());
    }

    List<GalaxyCatalog.Entry> entries() {
        return entries;
    }

    Optional<GalaxyCatalog.Entry> entry(int index) {
        return entries.stream().filter(e -> e.index() == index).findFirst();
    }

    /** That planet could not be made (it is not waited for). */
    boolean failed(int index) {
        return failed.contains(index);
    }

    /** Complete planets and far ones (tests). */
    int[] tiers() {
        int near = 0;
        for (PlanetSession s : PlanetClient.planets()) if (s.active()) near++;
        return new int[] {near, far.size()};
    }

    /**
     * Once per tick. from: where nearness is measured (Mario, or where he is about to land);
     * sceneId/hostPid as PlanetSession.update.
     */
    void tick(Vector3d from, int sceneId, int hostPid) {
        for (Map.Entry<Integer, PlanetSession> p : new ArrayList<>(promoting.entrySet()))
            if (!p.getValue().active() || p.getValue().queued() == 0) {
                dropFar(p.getKey());
                promoting.remove(p.getKey());
            }
        if (from != null && ++ticks >= EVERY) {
            ticks = 0;
            decide(from);
        }
        if (from != null) {
            finishMaking();
            meshFar(from);
        }
        for (FarPlanet f : far.values()) f.update(sceneId, hostPid);
    }

    /** Which planets are complete, which are made ahead; those that leave go back to far. */
    private void decide(Vector3d from) {
        List<PlanetLayout.Sphere> spheres = new ArrayList<>();
        Set<Integer> had = new HashSet<>();
        for (int k = 0; k < entries.size(); k++) {
            GalaxyCatalog.Entry e = entries.get(k);
            spheres.add(new PlanetLayout.Sphere(e.center(), PlanetSession.gravityRadius(e.radius()) * UNITS));
            PlanetSession s = PlanetClient.sessionOf(e.index());
            if (s != null && s.active()) had.add(k);
        }
        List<Integer> ranked = PlanetLayout.ranked(spheres, from, had, UNITS);
        int n = Math.min(PlanetLayout.NEAR_PLANETS, ranked.size());
        wanted = ranked.subList(0, n).stream().map(k -> entries.get(k).index()).toList();
        ahead = ranked.subList(n, Math.min(n + 2, ranked.size())).stream().map(k -> entries.get(k).index()).toList();
        for (GalaxyCatalog.Entry e : entries) {
            PlanetSession s = PlanetClient.sessionOf(e.index());
            boolean active = s != null && s.active();
            if (active && !wanted.contains(e.index()) && s != PlanetClient.focus()) demote(e, s, from);
        }
        for (int index : wanted) startMaking(index);
        for (int index : ahead) startMaking(index);
        // Made ahead and no longer near: forgotten.
        for (Integer index : new ArrayList<>(making.keySet()))
            if (!wanted.contains(index) && !ahead.contains(index)) making.remove(index).cancel(false);
    }

    private void startMaking(int index) {
        PlanetSession s = PlanetClient.sessionOf(index);
        if (s != null && s.active() || making.containsKey(index) || failed.contains(index)) return;
        GalaxyCatalog.Entry e = entry(index).orElse(null);
        if (e == null) return;
        String key = PlanetStore.key(stage, index);
        McBlocks blocks = PlanetClient.blocks();
        if (blocks == null) return;
        CompletableFuture<Object> f;
        if (java.nio.file.Files.isRegularFile(store.file(key))) {
            f = CompletableFuture.supplyAsync(() -> {
                try {
                    return store.read(key, blocks, name -> Minecraft.getInstance().submit(() -> blocks.parse(name)).join())
                            .orElseThrow(() -> new IllegalStateException("no file"));
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        } else {
            PlanetBlueprint bp = recipe(e);
            if (bp == null) {
                failed.add(index);
                GalaxyCraft.LOG.warn("Planet {} of the galaxy has neither a file nor a recipe", index);
                return;
            }
            if (bp.mode() == PlanetBlueprint.Mode.GENERATED) {
                CompletableFuture<VoxelPlanet> g = PlanetClient.generateAsync(bp);
                if (g == null) return; // no worldgen yet: tried again next time
                f = g.thenApply(p -> p);
            } else f = CompletableFuture.completedFuture(bp.build(blocks));
        }
        making.put(index, f);
    }

    /** What a catalog planet is made from when it has no file (null: nothing to make it from). */
    static PlanetBlueprint recipe(GalaxyCatalog.Entry e) {
        if (e.kind() == GalaxyCatalog.Kind.GENERATED)
            return PlanetBlueprint.standard("Planet " + e.index(), e.radius()).withMode(PlanetBlueprint.Mode.GENERATED)
                    .withBiome(e.seed(), e.biome(), 0).withWater(true).withUnderground(50, true, 100).withPlants(100);
        if (e.blueprint() == null) return null;
        try {
            return PlanetClient.blueprints.read(e.blueprint()).orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Planets made: those still wanted become complete; a failure is not tried again. */
    private void finishMaking() {
        for (Map.Entry<Integer, CompletableFuture<Object>> m : new ArrayList<>(making.entrySet())) {
            if (!m.getValue().isDone()) continue;
            int index = m.getKey();
            making.remove(index);
            Object made;
            try {
                made = m.getValue().join();
            } catch (RuntimeException ex) {
                failed.add(index);
                GalaxyCraft.LOG.warn("Could not make planet {} of the galaxy: {}", index, ex.toString());
                continue;
            }
            if (!wanted.contains(index)) {
                // Made ahead: kept until it is wanted (or no longer ahead).
                if (ahead.contains(index)) making.put(index, CompletableFuture.completedFuture(made));
                continue;
            }
            GalaxyCatalog.Entry e = entry(index).orElse(null);
            if (e == null) continue;
            PlanetSession s = PlanetClient.claim(index);
            if (made instanceof PlanetStore.Saved saved) s.load(saved);
            else {
                s.spawnAt((VoxelPlanet) made, e.center());
                s.clean(); // the same again from its recipe: saved only once edited
            }
            if (far.containsKey(index)) promoting.put(index, s);
            GalaxyCraft.LOG.info("Planet {} of the galaxy is complete", index);
        }
    }

    /** A complete planet back to far: saved if edited, its far view from its own cells. */
    private void demote(GalaxyCatalog.Entry e, PlanetSession s, Vector3d from) {
        VoxelPlanet p = s.planet();
        int patches = PlanetLayout.farPatches(p.surface() * UNITS, s.center().distance(from), 0);
        int stride = Math.max(1, p.grid.n / patches / 4);
        meshes.keySet().removeIf(k -> k >> 4 == e.index());
        meshes.put(e.index() << 4 | patches, PlanetLod.coarse(LodSource.of(p, stride), patches, UNITS));
        fromCells.add(e.index());
        PlanetClient.saveOne(s);
        PlanetClient.release(e.index());
        promoting.remove(e.index());
        GalaxyCraft.LOG.info("Planet {} of the galaxy is far now", e.index());
    }

    /** Every planet not complete is far; its view as detailed as it looks big, a few built per tick. */
    private void meshFar(Vector3d from) {
        int built = 0;
        for (GalaxyCatalog.Entry e : entries) {
            PlanetSession s = PlanetClient.sessionOf(e.index());
            boolean complete = s != null && s.active();
            if (complete && !promoting.containsKey(e.index())) {
                dropFar(e.index());
                continue;
            }
            FarPlanet f = far.get(e.index());
            double surface = e.radius();
            int patches = PlanetLayout.farPatches(surface * UNITS, e.center().distance(from), f == null ? 0 : f.patches());
            if (f != null && f.patches() == patches) continue;
            PlanetLod.Part[] parts = meshes.get(e.index() << 4 | patches);
            if (parts == null) {
                if (built >= MESHES_PER_TICK) continue;
                parts = build(e, patches);
                built++;
                if (parts == null) continue;
                meshes.put(e.index() << 4 | patches, parts);
            }
            if (f == null) {
                if (complete) continue; // promoting: its own far view is on its way
                f = new FarPlanet(e.center(), surface, UNITS);
                far.put(e.index(), f);
            }
            f.mesh(patches, parts);
        }
    }

    /** A far view at that many patches: from the generator, a blueprint's top layer, or a ball of grass. */
    private PlanetLod.Part[] build(GalaxyCatalog.Entry e, int patches) {
        McBlocks blocks = PlanetClient.blocks();
        if (blocks == null) return null;
        try {
            if (fromCells.contains(e.index())) {
                // Its cells are gone with its session: the patches it had, until it is complete again.
                for (int p : new int[] {12, 6, 3}) {
                    PlanetLod.Part[] had = meshes.get(e.index() << 4 | p);
                    if (had != null) return had;
                }
            }
            PlanetBlueprint bp = recipe(e);
            if (bp != null && bp.mode() == PlanetBlueprint.Mode.GENERATED) {
                McWorldgen gen = PlanetClient.worldgen();
                if (gen == null) return null;
                SurfaceSampler sampler = new SurfaceSampler(bp, gen.noise(bp.seed()), gen.biomes());
                return PlanetLod.coarse(LodSource.sampled(sampler, blocks, blocks::parse), patches, UNITS);
            }
            String top = bp != null && !bp.layers().isEmpty() ? bp.layers().getFirst().block() : "minecraft:grass_block";
            return PlanetLod.coarse(LodSource.flat(e.radius(), blocks, blocks.parse(top)), patches, UNITS);
        } catch (RuntimeException ex) {
            GalaxyCraft.LOG.warn("Could not draw planet {} from afar: {}", e.index(), ex.toString());
            failed.add(e.index());
            return null;
        }
    }

    private void dropFar(int index) {
        FarPlanet f = far.remove(index);
        if (f == null) return;
        f.remove();
        farLeaving.add(f);
    }

    /** The far planets' messages to the game, after the complete ones'. */
    void send(dev.moui.galaxycraft.bridge.BridgeClient bridge, BooleanSupplier bulk) {
        for (FarPlanet f : farLeaving)
            for (PlanetSession.Msg m; (m = f.peek()) != null && bridge.send(m.type(), m.payload()); ) f.sent();
        farLeaving.removeIf(f -> f.queued() == 0);
        for (FarPlanet f : far.values())
            for (PlanetSession.Msg m; bulk.getAsBoolean() && (m = f.peek()) != null && bridge.send(m.type(), m.payload()); ) f.sent();
    }

    /** The world is left (the game drops every planet itself): ids given back, nothing more made. */
    void clear() {
        for (FarPlanet f : far.values()) f.remove();
        for (FarPlanet f : farLeaving) f.remove();
        far.clear();
        farLeaving.clear();
        for (CompletableFuture<Object> f : making.values()) f.cancel(false);
        making.clear();
        promoting.clear();
    }

    /** A planet added or changed by hand (editor, commands): its entry, written to galaxy.json. */
    void put(GalaxyCatalog.Entry e) {
        entries.removeIf(x -> x.index() == e.index());
        entries.add(e);
        entries.sort(java.util.Comparator.comparingInt(GalaxyCatalog.Entry::index));
        meshes.keySet().removeIf(k -> k >> 4 == e.index());
        fromCells.remove(e.index());
        failed.remove(e.index());
        dropFar(e.index());
        write();
    }

    void removeEntry(int index) {
        entries.removeIf(x -> x.index() == index);
        dropFar(index);
        making.remove(index);
        write();
    }

    private void write() {
        GalaxySave.Galaxy g = new GalaxySave.Galaxy(1, options, List.copyOf(entries));
        PlanetClient.saveLater(() -> {
            try {
                save.writeGalaxy(g);
            } catch (IOException ex) {
                GalaxyCraft.LOG.warn("Could not save the galaxy: {}", ex.toString());
            }
        });
    }
}
