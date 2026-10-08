package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.Random;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class SurfaceSamplerTest {
    private static PlanetBlueprint bp(String biome, int size) {
        return PlanetBlueprint.standard("g", 48).withMode(PlanetBlueprint.Mode.GENERATED).withBiome(7, biome, size)
                .withUnderground(0, false, 0).withPlants(0);
    }

    @Test void theSamplerSaysHowHighTheGeneratedGroundIs() {
        for (PlanetBlueprint b : new PlanetBlueprint[] {bp("minecraft:plains", 0), bp(PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO),
                PlanetBlueprint.standard("g", 128).withMode(PlanetBlueprint.Mode.GENERATED).withBiome(3, "minecraft:windswept_hills", 0)
                        .withUnderground(0, false, 0).withPlants(0)}) {
            VoxelPlanet p = PlanetGenerator.build(b, null, GenFixtures.B, GenFixtures::id);
            SurfaceSampler s = new SurfaceSampler(b);
            CubeSphere g = p.sphere();
            Random r = new Random(1);
            int near = 0, all = 300;
            for (int t = 0; t < all; t++) {
                int f = r.nextInt(6), i = r.nextInt(g.n), j = r.nextInt(g.n);
                Vector3d dir = SurfaceSampler.columnDir(g, f, i, j);
                SurfaceSampler.Column c = s.at(f, i, j);
                int cell0 = g.index(f, i, j, 0), k = g.layers - 1;
                while (k > 0 && (p.get(cell0 + k) == Blocks.AIR || p.material(cell0 + k) == Material.ICE
                        || p.material(cell0 + k) == Material.WATER)) k--;
                // The sampler interpolates the builder's own lattice: only caves (none here) differ.
                if (k == s.depth() - 1 + c.height()) near++;
                assertEquals(c, s.at(f, i, j), "the same every time");
            }
            assertEquals(all, near, "columns at the same height");
        }
    }
}
