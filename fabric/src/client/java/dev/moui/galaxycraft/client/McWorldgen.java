package dev.moui.galaxycraft.client;

import com.mojang.datafixers.util.Pair;
import dev.moui.galaxycraft.voxel.gen.BiomeTable;
import dev.moui.galaxycraft.voxel.gen.TerrainNoise;
import dev.moui.galaxycraft.voxel.gen.Worldgen;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Generated planets made with Minecraft's own worldgen, read from the integrated server's
 * registries: the overworld's climate noises (seeded as a world of that seed seeds them) and its
 * biome table, kept to the biomes found at the surface (with and without the watery ones).
 */
public final class McWorldgen implements Worldgen {
    private final HolderGetter<NormalNoise> noises;
    private final Table table;

    public McWorldgen(RegistryAccess registries) {
        noises = registries.lookupOrThrow(Registries.NOISE);
        table = new Table(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters());
    }

    @Override
    public TerrainNoise noise(long seed) {
        PositionalRandomFactory random = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(seed).forkPositional();
        Noise[] n = {Noises.instantiate(noises, random, Noises.CONTINENTALNESS), Noises.instantiate(noises, random, Noises.EROSION),
                Noises.instantiate(noises, random, Noises.RIDGE), Noises.instantiate(noises, random, Noises.TEMPERATURE),
                Noises.instantiate(noises, random, Noises.VEGETATION),
                // Caves: two octaves of Minecraft's noise, about 32 blocks apart (its cave noises have
                // many more octaves than a planet's thin crust needs, and cost as much).
                NormalNoise.builder().setBaseOctave(-5).setOctaveCount(3).build()
                        .create(random.fromHashOf(net.minecraft.resources.Identifier.fromNamespaceAndPath("galaxycraft", "caves")))};
        return (f, x, y, z) -> n[f.ordinal()].get(x, y, z);
    }

    @Override
    public BiomeTable biomes() {
        return table;
    }

    /** The name a biome has in the game ("Snowy Taiga"), by its id. */
    public static String name(String id) {
        int colon = id.indexOf(':');
        return Component.translatable("biome." + id.substring(0, colon) + "." + id.substring(colon + 1)).getString();
    }

    private static final class Table implements BiomeTable {
        private final Climate.ParameterList<String> land, all;
        private final Map<String, dev.moui.galaxycraft.voxel.gen.Climate.Span> spans = new HashMap<>();
        private final java.util.Set<String> landIds = new TreeSet<>(), watery = new java.util.HashSet<>();
        private final List<String> landList, allList;

        Table(Climate.ParameterList<Holder<Biome>> overworld) {
            List<Pair<Climate.ParameterPoint, String>> keptLand = new ArrayList<>(), kept = new ArrayList<>();
            for (Pair<Climate.ParameterPoint, Holder<Biome>> e : overworld.values()) {
                Holder<Biome> b = e.getSecond();
                Climate.ParameterPoint p = e.getFirst();
                if (p.depth().min() > 0 || p.depth().max() < 0) continue; // underground: caves
                String id = b.unwrapKey().orElseThrow().identifier().toString();
                kept.add(Pair.of(p, id));
                if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER)) watery.add(id);
                if (!b.is(BiomeTags.IS_OCEAN) && !b.is(BiomeTags.IS_RIVER) && !b.is(BiomeTags.IS_BEACH)) {
                    keptLand.add(Pair.of(p, id));
                    landIds.add(id);
                }
                var span = new dev.moui.galaxycraft.voxel.gen.Climate.Span(
                        climate(p, true), climate(p, false));
                spans.merge(id, span, (a, c) -> new dev.moui.galaxycraft.voxel.gen.Climate.Span(
                        min(a.min(), c.min()), max(a.max(), c.max())));
            }
            land = new Climate.ParameterList<>(keptLand);
            all = new Climate.ParameterList<>(kept);
            landList = List.copyOf(landIds);
            allList = List.copyOf(new TreeSet<>(spans.keySet()));
        }

        private static dev.moui.galaxycraft.voxel.gen.Climate climate(Climate.ParameterPoint p, boolean min) {
            return new dev.moui.galaxycraft.voxel.gen.Climate(v(p.continentalness(), min), v(p.erosion(), min),
                    v(p.weirdness(), min), v(p.temperature(), min), v(p.humidity(), min));
        }

        private static double v(Climate.Parameter p, boolean min) {
            return Climate.unquantizeCoord(min ? p.min() : p.max());
        }

        private static dev.moui.galaxycraft.voxel.gen.Climate min(dev.moui.galaxycraft.voxel.gen.Climate a, dev.moui.galaxycraft.voxel.gen.Climate b) {
            return new dev.moui.galaxycraft.voxel.gen.Climate(Math.min(a.continentalness(), b.continentalness()),
                    Math.min(a.erosion(), b.erosion()), Math.min(a.ridges(), b.ridges()),
                    Math.min(a.temperature(), b.temperature()), Math.min(a.humidity(), b.humidity()));
        }

        private static dev.moui.galaxycraft.voxel.gen.Climate max(dev.moui.galaxycraft.voxel.gen.Climate a, dev.moui.galaxycraft.voxel.gen.Climate b) {
            return new dev.moui.galaxycraft.voxel.gen.Climate(Math.max(a.continentalness(), b.continentalness()),
                    Math.max(a.erosion(), b.erosion()), Math.max(a.ridges(), b.ridges()),
                    Math.max(a.temperature(), b.temperature()), Math.max(a.humidity(), b.humidity()));
        }

        @Override
        public String find(dev.moui.galaxycraft.voxel.gen.Climate c, boolean water) {
            return (water ? all : land).findValue(Climate.target((float) c.temperature(), (float) c.humidity(), (float) c.continentalness(),
                    (float) c.erosion(), 0, (float) c.ridges()));
        }

        @Override
        public dev.moui.galaxycraft.voxel.gen.Climate.Span span(String biome) {
            return spans.get(biome);
        }

        @Override
        public List<String> land() {
            return landList;
        }

        @Override
        public List<String> all() {
            return allList;
        }

        @Override
        public boolean watery(String biome) {
            return watery.contains(biome);
        }
    }
}
