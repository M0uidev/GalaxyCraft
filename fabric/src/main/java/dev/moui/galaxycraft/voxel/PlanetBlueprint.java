package dev.moui.galaxycraft.voxel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A planet's design, made in the editor (/galaxycraft): a name, the radius of its surface, the
 * room to build above it and its layers from the surface down. The last layer reaches down to the
 * bedrock that always seals the bottom; layers past the crust's depth are left out. Saved as JSON
 * by {@link BlueprintStore}; planets are built from it, never edited through it.
 */
public record PlanetBlueprint(String name, int radius, int air, List<Layer> layers, Mode mode, long seed, String biome, int biomeSize, boolean water,
        int caves, boolean entrances, int ores, int plants) {
    public static final int MIN_AIR = 4, MAX_AIR = 64, MAX_THICKNESS = 64, MAX_LAYERS = 32, MAX_BIOME_SIZE = 512, MAX_CAVES = 100, MAX_ORES = 200, MAX_PLANTS = 200;
    /** The biome of a one-biome planet picked from its seed. */
    public static final String RANDOM = "random";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A block (Minecraft's text for a block state, as in /setblock) this many blocks thick. */
    public record Layer(String block, int thickness) {}

    /**
     * LAYERS: a smooth ball of the layers. GENERATED: terrain and blocks from Minecraft's noises and
     * a biome (voxel.gen), the layers unused; biomeSize 0 makes it all one biome, more mixes biomes
     * about that many blocks across; water fills what lies below the base surface (shallow seas,
     * lakes; oceans and rivers among mixed biomes); caves 0 (none) to 100 (many), opening to the
     * surface with entrances; ores and plants (trees, flowers, grass) percent of Minecraft's amount.
     */
    public enum Mode { LAYERS, GENERATED }

    public PlanetBlueprint {
        layers = List.copyOf(layers);
        if (mode == null) mode = Mode.LAYERS; // saved before there were modes
        if (biome == null) biome = RANDOM;
    }

    public PlanetBlueprint(String name, int radius, int air, List<Layer> layers) {
        this(name, radius, air, layers, Mode.LAYERS, 0, RANDOM, 0, false, 0, false, 0, 0);
    }

    /** What /galaxycraft planet spawn makes: grass, two of dirt, stone. */
    public static PlanetBlueprint standard(String name, int radius) {
        return new PlanetBlueprint(name, radius, VoxelPlanet.defaultAir(radius), List.of(
                new Layer("minecraft:grass_block", 1), new Layer("minecraft:dirt", 2), new Layer("minecraft:stone", 1)));
    }

    /** How deep its crust goes, bedrock included (blocks). */
    public int crustDepth() {
        return VoxelPlanet.crustDepth(radius);
    }

    /** Blocks of crust above the bedrock at this radius: what the layers share. */
    public static int room(int radius) {
        return VoxelPlanet.crustDepth(radius) - 1;
    }

    /**
     * The thicknesses, each at least 1, cut from the bottom layer up until they fit in room. Layers
     * that still do not fit (more layers than room) stay at 1 and are left out when building.
     */
    public static int[] fit(int[] thickness, int room) {
        int[] t = new int[thickness.length];
        int sum = 0;
        for (int i = 0; i < t.length; i++) sum += t[i] = Math.clamp(thickness[i], 1, MAX_THICKNESS);
        for (int i = t.length - 1; i >= 0 && sum > room; i--) {
            int cut = Math.min(t[i] - 1, sum - room);
            t[i] -= cut;
            sum -= cut;
        }
        return t;
    }

    /** What is wrong with it for building, or null if nothing is. */
    public String problem() {
        if (name == null || name.isBlank()) return "no name";
        if (radius < VoxelPlanet.MIN_RADIUS || radius > VoxelPlanet.MAX_RADIUS)
            return "radius not in " + VoxelPlanet.MIN_RADIUS + ".." + VoxelPlanet.MAX_RADIUS;
        if (air < MIN_AIR || air > MAX_AIR) return "air not in " + MIN_AIR + ".." + MAX_AIR;
        if (biomeSize < 0 || biomeSize > MAX_BIOME_SIZE) return "biome size not in 0.." + MAX_BIOME_SIZE;
        if (biome.isBlank()) return "no biome";
        if (caves < 0 || caves > MAX_CAVES) return "caves not in 0.." + MAX_CAVES;
        if (ores < 0 || ores > MAX_ORES) return "ores not in 0.." + MAX_ORES;
        if (plants < 0 || plants > MAX_PLANTS) return "plants not in 0.." + MAX_PLANTS;
        if (layers.isEmpty() || layers.size() > MAX_LAYERS) return "1 to " + MAX_LAYERS + " layers";
        for (Layer l : layers) {
            if (l.block() == null || l.block().isBlank()) return "a layer has no block";
            if (l.thickness() < 1 || l.thickness() > MAX_THICKNESS) return "thickness not in 1.." + MAX_THICKNESS;
        }
        return null;
    }

    /** The planet it describes, in blocks' ids (a block blocks cannot read is air); gen makes generated ones. */
    public VoxelPlanet build(Blocks blocks, dev.moui.galaxycraft.voxel.gen.Worldgen gen) {
        String p = problem();
        if (p != null) throw new IllegalArgumentException(name + ": " + p);
        if (mode == Mode.GENERATED) {
            if (gen == null) throw new IllegalArgumentException(name + ": generated, and no worldgen to make it");
            return dev.moui.galaxycraft.voxel.gen.PlanetGenerator.build(this, gen.noise(seed), gen.biomes(), gen.vegetation(), blocks, blocks::parse);
        }
        List<Integer> down = new ArrayList<>();
        for (Layer l : layers) {
            int id = blocks.parse(l.block());
            for (int i = 0; i < l.thickness() && down.size() < crustDepth(); i++) down.add(id);
        }
        return VoxelPlanet.layered(radius, air, down.stream().mapToInt(Integer::intValue).toArray(), blocks);
    }

    /** A layered one only. */
    public VoxelPlanet build(Blocks blocks) {
        return build(blocks, null);
    }

    public PlanetBlueprint withMode(Mode m) {
        return new PlanetBlueprint(name, radius, air, layers, m, seed, biome, biomeSize, water, caves, entrances, ores, plants);
    }

    public PlanetBlueprint withAir(int air) {
        return new PlanetBlueprint(name, radius, air, layers, mode, seed, biome, biomeSize, water, caves, entrances, ores, plants);
    }

    public PlanetBlueprint withBiome(long seed, String biome, int biomeSize) {
        return new PlanetBlueprint(name, radius, air, layers, mode, seed, biome, biomeSize, water, caves, entrances, ores, plants);
    }

    public PlanetBlueprint withWater(boolean water) {
        return new PlanetBlueprint(name, radius, air, layers, mode, seed, biome, biomeSize, water, caves, entrances, ores, plants);
    }

    public PlanetBlueprint withUnderground(int caves, boolean entrances, int ores) {
        return new PlanetBlueprint(name, radius, air, layers, mode, seed, biome, biomeSize, water, caves, entrances, ores, plants);
    }

    public PlanetBlueprint withPlants(int plants) {
        return new PlanetBlueprint(name, radius, air, layers, mode, seed, biome, biomeSize, water, caves, entrances, ores, plants);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static PlanetBlueprint fromJson(String json) {
        PlanetBlueprint b = GSON.fromJson(json, PlanetBlueprint.class);
        if (b == null || b.layers() == null) throw new JsonParseException("not a planet blueprint");
        return b;
    }
}
