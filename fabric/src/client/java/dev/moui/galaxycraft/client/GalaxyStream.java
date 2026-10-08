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
    /** Far views built in one tick at most (their costly part was worked out on the workers). */
    static final int MESHES_PER_TICK = 4;
    private static final double UNITS = 1 / GravityFrame.SCALE;

    private final GalaxySave save;
    private final PlanetStore store;
    private final String stage;
    private final List<GalaxyCatalog.Entry> entries;
    private final GalaxyCatalog.Options options;
    /** The world's layout, kept when the galaxy is saved again. */
    private final int layout;
    /**
     * The endless universe around the world's galaxy (its home system): the generated systems near
     * Mario, their planets as entries like the world's own (indexes from SystemIndex, centers in
     * universe units), streamed the same way. Null: the world's galaxy alone.
     */
    private final Universe universe;
    private final Map<Universe.Sector, List<GalaxyCatalog.Entry>> systems = new java.util.LinkedHashMap<>();
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
        this.layout = galaxy.layout();
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

    /** The systems near Mario in (FarSight.load), the far ones out (only once none of their planets is complete or being made). */
    private void systemsNear(Vector3d from) {
        if (universe == null) return;
        UPos at = UPos.of(from);
        // Known (as dots) from FarSight.LOAD blocks: a sector is 8192, so three around reach that far.
        for (Universe.Star star : universe.around(at, 3))
            if (!star.home() && dev.moui.galaxycraft.voxel.FarSight.load(star.center().minus(at).length() / UNITS, false)) load(star.sector());
        for (Universe.Sector s : new ArrayList<>(systems.keySet())) {
            Universe.Star star = universe.star(s).orElse(null);
            if (star != null && dev.moui.galaxycraft.voxel.FarSight.load(star.center().minus(at).length() / UNITS, true)) continue;
            List<GalaxyCatalog.Entry> planets = systems.get(s);
            boolean busy = planets.stream().anyMatch(e -> {
                PlanetSession ps = PlanetClient.sessionOf(e.index());
                return ps != null && ps.active() || making.containsKey(e.index()) || promoting.containsKey(e.index());
            });
            if (busy) continue;
            for (GalaxyCatalog.Entry e : planets) {
                dropFar(e.index());
                meshes.keySet().removeIf(k -> k >> 4 == e.index());
                samplers.remove(e.index());
                levels.remove(e.index());
                dotColors.remove(e.index());
                warming.keySet().removeIf(k -> k >> 4 == e.index());
                fromCells.remove(e.index());
                recent.remove(e.index());
                failed.remove(e.index());
            }
            systems.remove(s);
            all = null;
            GalaxyCraft.LOG.info("System {} left behind", SystemIndex.name(s));
        }
    }

    /** How a planet shows now (tests): complete, far (and its patches), dot, or none (not known here). */
    String shownAs(int index) {
        PlanetSession s = PlanetClient.sessionOf(index);
        if (s != null && s.active()) return "complete";
        FarPlanet f = far.get(index);
        if (f != null && f.patches() > 0 && f.queued() == 0) return "far" + f.patches();
        return all().stream().anyMatch(e -> e.index() == index) ? "dot" : "none";
    }

    /** Whether that system's planets are known here (shown as dots or more): home always. */
    boolean knows(Universe.Sector s) {
        return s.equals(Universe.Sector.HOME) || systems.containsKey(s);
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
        for (GalaxyCatalog.Entry e : universe.system(star, dev.moui.galaxycraft.voxel.gen.LegacyBiome.land()).entries())
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
                    return store.read(key, blocks, name -> PlanetClient.answer(Minecraft.getInstance().submit(() -> blocks.parse(name)), "block " + name))
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

    /**
     * What a catalog planet is made from when it has no file (null: nothing to make it from). A
     * generated one is several biomes (Auto), but the first planet when Create World named its biome.
     */
    PlanetBlueprint recipe(GalaxyCatalog.Entry e) {
        if (e.kind() == GalaxyCatalog.Kind.GENERATED) {
            GalaxyCatalog.First first = options == null ? null : options.first();
            boolean named = e.index() == 0 && first != null && !first.isBlueprint() && first.biome() != null
                    && !PlanetBlueprint.RANDOM.equals(first.biome());
            return PlanetBlueprint.standard("Planet " + e.index(), e.radius()).withMode(PlanetBlueprint.Mode.GENERATED)
                    .withBiome(e.seed(), named ? first.biome() : PlanetBlueprint.RANDOM, named ? 0 : PlanetBlueprint.AUTO).withWater(true).withUnderground(50, true, 100).withPlants(100);
        }
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

    /** Far views at once at most: the game draws 80 planets that only show, a few kept for those leaving. */
    static final int MAX_FAR = 72;
    /** What each planet far away shows as now (no entry: a dot). */
    private final Map<Integer, dev.moui.galaxycraft.voxel.FarSight.Level> levels = new HashMap<>();
    /** Generated planets' samplers: their terrain worked out once, refined views read it again. */
    private final Map<Integer, SurfaceSampler> samplers = new HashMap<>();
    /** Far views being worked out on the workers (index << 4 | patches): built here once done. */
    private final Map<Integer, CompletableFuture<Void>> warming = new HashMap<>();

    /**
     * Every planet not complete is a dot, or a far view once it looks big enough (the biggest on
     * screen first, MAX_FAR at most); a view's costly part is worked out on the workers, then built
     * here from it.
     */
    private void meshFar(Vector3d from) {
        List<GalaxyCatalog.Entry> want = new ArrayList<>();
        Map<Integer, Double> angle = new HashMap<>();
        for (GalaxyCatalog.Entry e : all()) {
            PlanetSession s = PlanetClient.sessionOf(e.index());
            boolean complete = s != null && s.active();
            if (complete && !promoting.containsKey(e.index())) {
                dropFar(e.index());
                levels.remove(e.index());
                continue;
            }
            double a = dev.moui.galaxycraft.voxel.FarSight.angle(e.radius() * UNITS, e.center().distance(from));
            var had = levels.getOrDefault(e.index(), dev.moui.galaxycraft.voxel.FarSight.Level.DOT);
            var now = dev.moui.galaxycraft.voxel.FarSight.level(a, had);
            if (now == dev.moui.galaxycraft.voxel.FarSight.Level.FAR || complete) {
                want.add(e);
                angle.put(e.index(), a);
            } else {
                levels.remove(e.index());
                dropFar(e.index());
            }
        }
        want.sort(java.util.Comparator.comparingDouble((GalaxyCatalog.Entry e) -> -angle.get(e.index())));
        int built = 0;
        for (int k = 0; k < want.size(); k++) {
            GalaxyCatalog.Entry e = want.get(k);
            boolean complete = PlanetClient.sessionOf(e.index()) != null && PlanetClient.sessionOf(e.index()).active();
            if (k >= MAX_FAR && !complete) { // no room: a dot
                levels.remove(e.index());
                dropFar(e.index());
                continue;
            }
            levels.put(e.index(), dev.moui.galaxycraft.voxel.FarSight.Level.FAR);
            FarPlanet f = far.get(e.index());
            int patches = PlanetLayout.farPatches(e.radius() * UNITS, e.center().distance(from), f == null ? 0 : f.patches());
            if (f != null && f.patches() == patches) continue;
            int key = e.index() << 4 | patches;
            PlanetLod.Part[] parts = meshes.get(key);
            if (parts == null) {
                if (!ready(e, patches)) continue; // being worked out
                if (built >= MESHES_PER_TICK) continue;
                parts = build(e, patches);
                built++;
                warming.remove(key);
                if (parts == null) continue;
                meshes.put(key, parts);
            }
            if (f == null) {
                if (complete) continue; // promoting: its own far view is on its way
                f = new FarPlanet(e.center(), e.radius(), UNITS);
                far.put(e.index(), f);
            }
            f.mesh(patches, parts);
        }
    }

    /**
     * Whether a far view at that many patches can be built on this thread now: at once for those
     * cheap to make (blueprints, kept cells); for generated planets once their terrain there has
     * been worked out on the workers (started here the first time).
     */
    private boolean ready(GalaxyCatalog.Entry e, int patches) {
        if (fromCells.contains(e.index())) return true;
        PlanetBlueprint bp = recipe(e);
        if (bp == null || bp.mode() != PlanetBlueprint.Mode.GENERATED) return true;
        int key = e.index() << 4 | patches;
        CompletableFuture<Void> w = warming.get(key);
        if (w == null) {
            SurfaceSampler sampler = samplers.computeIfAbsent(e.index(), i -> new SurfaceSampler(bp));
            warming.put(key, CompletableFuture.runAsync(() -> sampler.warm(patches), PlanetClient.workers));
            return false;
        }
        if (w.isCompletedExceptionally()) {
            warming.remove(key);
            failed.add(e.index());
            return false;
        }
        return w.isDone();
    }

    /**
     * The planets shown only as dots of light now (with the star field): far ones without a far
     * view sent yet, each as much as its system has opened from its star.
     */
    List<dev.moui.galaxycraft.universe.StarField.Dot> dots(Vector3d from) {
        List<dev.moui.galaxycraft.universe.StarField.Dot> out = new ArrayList<>();
        UPos at = UPos.of(from);
        Map<Universe.Sector, Double> open = new HashMap<>();
        for (GalaxyCatalog.Entry e : all()) {
            PlanetSession s = PlanetClient.sessionOf(e.index());
            if (s != null && s.active()) continue;
            FarPlanet f = far.get(e.index());
            if (f != null && f.patches() > 0 && f.queued() == 0) continue; // its far view shows
            Universe.Sector sector = sectorOf(e);
            double weight = open.computeIfAbsent(sector, k -> {
                if (universe == null) return 1.0;
                Universe.Star star = universe.star(k).orElse(null);
                return star == null ? 1.0 : dev.moui.galaxycraft.voxel.FarSight.opened(star.center().minus(at).length() / UNITS);
            });
            out.add(new dev.moui.galaxycraft.universe.StarField.Dot(UPos.of(e.center()), e.radius(), dotColor(e), weight));
        }
        return out;
    }

    private final Map<Integer, Integer> dotColors = new HashMap<>();

    /** A planet's dot color, from its recipe (worked out once). */
    private int dotColor(GalaxyCatalog.Entry e) {
        return dotColors.computeIfAbsent(e.index(), i -> {
            PlanetBlueprint bp = recipe(e);
            if (bp == null) return dev.moui.galaxycraft.voxel.DotColor.AUTO;
            if (bp.mode() == PlanetBlueprint.Mode.GENERATED)
                return dev.moui.galaxycraft.voxel.DotColor.generated(bp.biomeSize() == 0 ? dev.moui.galaxycraft.voxel.gen.PlanetGenerator.biome(bp) : null);
            McBlocks blocks = PlanetClient.blocks();
            if (bp.layers().isEmpty() || blocks == null) return dev.moui.galaxycraft.voxel.DotColor.AUTO;
            var state = blocks.state(blocks.parse(bp.layers().getFirst().block()));
            return dev.moui.galaxycraft.voxel.DotColor.blueprint(state == null ? -1 : state.getBlock().defaultMapColor().col);
        });
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
                SurfaceSampler sampler = samplers.computeIfAbsent(e.index(), i -> new SurfaceSampler(bp));
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
        for (CompletableFuture<Void> w : warming.values()) w.cancel(false);
        warming.clear();
        samplers.clear();
        levels.clear();
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
        GalaxySave.Galaxy g = new GalaxySave.Galaxy(layout, options, List.copyOf(entries));
        PlanetClient.saveLater(() -> {
            try {
                save.writeGalaxy(g);
            } catch (IOException ex) {
                GalaxyCraft.LOG.warn("Could not save the galaxy: {}", ex.toString());
            }
        });
    }
}
