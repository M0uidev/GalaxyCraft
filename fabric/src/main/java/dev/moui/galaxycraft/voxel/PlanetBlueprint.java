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
public record PlanetBlueprint(String name, int radius, int air, List<Layer> layers) {
    public static final int MIN_AIR = 4, MAX_AIR = 64, MAX_THICKNESS = 64, MAX_LAYERS = 32;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A block (Minecraft's text for a block state, as in /setblock) this many blocks thick. */
    public record Layer(String block, int thickness) {}

    public PlanetBlueprint {
        layers = List.copyOf(layers);
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

    /** What is wrong with it for building, or null if nothing is. */
    public String problem() {
        if (name == null || name.isBlank()) return "no name";
        if (radius < VoxelPlanet.MIN_RADIUS || radius > VoxelPlanet.MAX_RADIUS)
            return "radius not in " + VoxelPlanet.MIN_RADIUS + ".." + VoxelPlanet.MAX_RADIUS;
        if (air < MIN_AIR || air > MAX_AIR) return "air not in " + MIN_AIR + ".." + MAX_AIR;
        if (layers.isEmpty() || layers.size() > MAX_LAYERS) return "1 to " + MAX_LAYERS + " layers";
        for (Layer l : layers) {
            if (l.block() == null || l.block().isBlank()) return "a layer has no block";
            if (l.thickness() < 1 || l.thickness() > MAX_THICKNESS) return "thickness not in 1.." + MAX_THICKNESS;
        }
        return null;
    }

    /** The planet it describes, in blocks' ids (a block blocks cannot read is air). */
    public VoxelPlanet build(Blocks blocks) {
        String p = problem();
        if (p != null) throw new IllegalArgumentException(name + ": " + p);
        List<Integer> down = new ArrayList<>();
        for (Layer l : layers) {
            int id = blocks.parse(l.block());
            for (int i = 0; i < l.thickness() && down.size() < crustDepth(); i++) down.add(id);
        }
        return VoxelPlanet.layered(radius, air, down.stream().mapToInt(Integer::intValue).toArray(), blocks);
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
