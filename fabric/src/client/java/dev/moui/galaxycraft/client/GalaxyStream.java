package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.universe.OriginPolicy;
import dev.moui.galaxycraft.universe.SystemIndex;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;
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
 * their recipe, or kept from a moment ago), the next one is made ahead, and every other one is a {@link FarPlanet}, drawn
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
    /**
     * The endless universe around the world's galaxy (its home system): the generated systems near
     * Mario, their planets as entries like the world's own (indexes from SystemIndex, centers in
     * universe units), streamed the same way. Null: the world's galaxy alone.
     */
    private final Universe universe;
    private final Map<Universe.Sector, List<GalaxyCatalog.Entry>> systems = new java.util.LinkedHashMap<>();
    /** A system's planets join within this many blocks of its center, and leave past UNLOAD_BLOCKS. */
    static final double LOAD_BLOCKS = 6000, UNLOAD_BLOCKS = 8000;
    private List<GalaxyCatalog.Entry> all;
    private final Map<Integer, FarPlanet> far = new HashMap<>();
    private final List<FarPlanet> farLeaving = new ArrayList<>();
    /** Planets being read or made off this thread: a PlanetStore.Saved or a VoxelPlanet. */
    private final Map<Integer, CompletableFuture<Object>> making = new HashMap<>();
    /** Complete planets whose far view is still up until their own is all sent. */
    private final Map<Integer, PlanetSession> promoting = new HashMap<>();
    private final Set<Integer> failed = new HashSet<>();
    /** Planets that were complete a moment ago, kept as they are: coming back to one is instant. */
    static final int RECENT = 4;
    private final Map<Integer, VoxelPlanet> recent = new java.util.LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, VoxelPlanet> e) {
            return size() > RECENT;
        }
    };
    /** Far views by index << 4 | patches; cells: built from the planet itself (not its recipe). */
    private final Map<Integer, PlanetLod.Part[]> meshes = new HashMap<>();
    private final Set<Integer> fromCells = new HashSet<>();
    private int ticks = EVERY - 1;
    private List<Integer> wanted = List.of(), ahead = List.of();

    GalaxyStream(GalaxySave save, PlanetStore store, String stage, GalaxySave.Galaxy galaxy, Universe universe) {
        this.save = save;
        this.store = store;
        this.stage = stage;
        this.options = galaxy.options();
        this.entries = new ArrayList<>(galaxy.entries());
        this.universe = universe;
    }

    /** The world's own galaxy (galaxy.json). */
    List<GalaxyCatalog.Entry> entries() {
        return entries;
    }

    /** Every planet streamed: the world's galaxy's and the generated systems' near Mario. */
    List<GalaxyCatalog.Entry> all() {
        if (all == null) {
            all = new ArrayList<>(entries);
            for (List<GalaxyCatalog.Entry> s : systems.values()) all.addAll(s);
        }
        return all;
    }

    /** Every streamed planet's gravity (universe units): what a pulse slows down for. */
    List<PlanetLayout.Sphere> spheres() {
        List<PlanetLayout.Sphere> out = new ArrayList<>();
        for (GalaxyCatalog.Entry e : all()) out.add(new PlanetLayout.Sphere(e.center(), PlanetSession.gravityRadius(e.radius()) * UNITS));
        return out;
    }

    /** That planet's entry; a generated system's is made then if its system is not in yet. */
    Optional<GalaxyCatalog.Entry> entry(int index) {
        Optional<GalaxyCatalog.Entry> e = all().stream().filter(x -> x.index() == index).findFirst();
        if (e.isPresent() || !SystemIndex.generated(index)) return e;
        SystemIndex.ref(index).ifPresent(r -> load(r.sector()));
        return all().stream().filter(x -> x.index() == index).findFirst();
    }

    /** The systems near Mario in, the far ones out (only once none of their planets is complete or being made). */
    private void systemsNear(Vector3d from) {
        if (universe == null) return;
        UPos at = UPos.of(from);
        for (Universe.Star star : universe.around(at, 1))
            if (!star.home() && star.center().minus(at).length() < LOAD_BLOCKS * UNITS) load(star.sector());
        for (Universe.Sector s : new ArrayList<>(systems.keySet())) {
            Universe.Star star = universe.star(s).orElse(null);
            if (star != null && star.center().minus(at).length() < UNLOAD_BLOCKS * UNITS) continue;
            List<GalaxyCatalog.Entry> planets = systems.get(s);
            boolean busy = planets.stream().anyMatch(e -> {
                PlanetSession ps = PlanetClient.sessionOf(e.index());
                return ps != null && ps.active() || making.containsKey(e.index()) || promoting.containsKey(e.index());
            });
            if (busy) continue;
            for (GalaxyCatalog.Entry e : planets) {
                dropFar(e.index());
                meshes.keySet().removeIf(k -> k >> 4 == e.index());
                fromCells.remove(e.index());
                recent.remove(e.index());
                failed.remove(e.index());
            }
            systems.remove(s);
            all = null;
            GalaxyCraft.LOG.info("System {} left behind", SystemIndex.name(s));
        }
    }

    /** Out between systems: no planet's system. */
    private static final Universe.Sector NOWHERE = new Universe.Sector(Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);

    /** The sector of the system a planet is in (the world's galaxy: home). */
    private static Universe.Sector sectorOf(GalaxyCatalog.Entry e) {
        return SystemIndex.ref(e.index()).map(SystemIndex.Ref::sector).orElse(Universe.Sector.HOME);
    }

    /** A generated system's planets, as entries (made from the sector's hash: the same every time). */
    private void load(Universe.Sector s) {
        if (universe == null || systems.containsKey(s)) return;
        Universe.Star star = universe.star(s).orElse(null);
        McWorldgen gen = PlanetClient.worldgen();
        if (star == null || star.home() || gen == null) return;
        Vector3d c = star.center().minus(UPos.ZERO);
        List<GalaxyCatalog.Entry> planets = new ArrayList<>();
        for (GalaxyCatalog.Entry e : universe.system(star, gen.biomes().land()).entries())
            planets.add(new GalaxyCatalog.Entry(SystemIndex.index(s, e.index()), c.x + e.x(), c.y + e.y(), c.z + e.z(), e.radius(),
                    e.kind(), e.biome(), e.blueprint(), e.seed()));
        systems.put(s, planets);
        all = null;
        GalaxyCraft.LOG.info("System {} in: {} planets, {} blocks from home", SystemIndex.name(s), planets.size(),
                Math.round(c.length() / UNITS));
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
            systemsNear(from);
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
        // Only the planets of the system Mario is in can be complete: a small system would
        // otherwise fill its places with another's, thousands of blocks off.
        Universe.Sector here = universe == null ? null
                : universe.systemAt(UPos.of(from), OriginPolicy.SYSTEM_MARGIN).map(Universe.Star::sector).orElse(NOWHERE);
        List<GalaxyCatalog.Entry> entries = all().stream().filter(e -> here == null || here.equals(sectorOf(e))).toList();
        for (int k = 0; k < entries.size(); k++) {
            GalaxyCatalog.Entry e = entries.get(k);
            spheres.add(new PlanetLayout.Sphere(e.center(), PlanetSession.gravityRadius(e.radius()) * UNITS));
            PlanetSession s = PlanetClient.sessionOf(e.index());
            if (s != null && s.active()) had.add(k);
        }
        List<Integer> ranked = PlanetLayout.ranked(spheres, from, had, UNITS);
        int n = Math.min(PlanetLayout.NEAR_PLANETS, ranked.size());
        wanted = ranked.subList(0, n).stream().map(k -> entries.get(k).index()).toList();
        ahead = ranked.subList(n, Math.min(n + 1, ranked.size())).stream().map(k -> entries.get(k).index()).toList();
        for (GalaxyCatalog.Entry e : all()) {
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
        VoxelPlanet kept = recent.remove(index);
        if (kept != null) f = CompletableFuture.completedFuture(kept);
        else if (java.nio.file.Files.isRegularFile(store.file(key))) {
            f = CompletableFuture.supplyAsync(() -> {
                try {
                    return store.read(key, blocks, name -> Minecraft.getInstance().submit(() -> blocks.parse(name)).join())
                            .orElseThrow(() -> new IllegalStateException("no file"));
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }, PlanetClient.maker);
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
        recent.put(e.index(), p);
        PlanetClient.release(e.index());
        promoting.remove(e.index());
        GalaxyCraft.LOG.info("Planet {} of the galaxy is far now", e.index());
    }

    /** Every planet not complete is far; its view as detailed as it looks big, a few built per tick. */
    private void meshFar(Vector3d from) {
        int built = 0;
        for (GalaxyCatalog.Entry e : all()) {
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
                for (int p : new int[] {12, 6, 3, 2, 1}) {
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

    /** The floating origin moved: far planets' records still queued are made again from it. */
    void originMoved() {
        for (FarPlanet f : far.values()) f.originMoved();
        for (FarPlanet f : farLeaving) f.originMoved();
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
        recent.clear();
    }

    /** A planet added or changed by hand (editor, commands): its entry, written to galaxy.json. */
    void put(GalaxyCatalog.Entry e) {
        all = null;
        if (SystemIndex.generated(e.index())) {
            // A generated system's planet changed by hand: for this visit (its blocks are saved in its file).
            for (List<GalaxyCatalog.Entry> s : systems.values())
                s.replaceAll(x -> x.index() == e.index() ? e : x);
            dropFar(e.index());
            return;
        }
        entries.removeIf(x -> x.index() == e.index());
        entries.add(e);
        entries.sort(java.util.Comparator.comparingInt(GalaxyCatalog.Entry::index));
        meshes.keySet().removeIf(k -> k >> 4 == e.index());
        fromCells.remove(e.index());
        failed.remove(e.index());
        recent.remove(e.index());
        dropFar(e.index());
        write();
    }

    void removeEntry(int index) {
        all = null;
        for (List<GalaxyCatalog.Entry> s : systems.values()) s.removeIf(x -> x.index() == index);
        entries.removeIf(x -> x.index() == index);
        recent.remove(index);
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
