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
        for (PlanetBlueprint b : new PlanetBlueprint[] {bp("minecraft:plains", 0), bp(PlanetBlueprint.RANDOM, 64)}) {
            TerrainNoise noise = GenFixtures.waves(b.seed());
            VoxelPlanet p = PlanetGenerator.build(b, noise, GenFixtures.TABLE, null, GenFixtures.B, GenFixtures::id);
            SurfaceSampler s = new SurfaceSampler(b, noise, GenFixtures.TABLE);
            CubeSphere g = p.sphere();
            Random r = new Random(1);
            for (int t = 0; t < 60; t++) {
                int f = r.nextInt(6), i = r.nextInt(g.n), j = r.nextInt(g.n);
                Vector3d dir = SurfaceSampler.columnDir(g, f, i, j);
                SurfaceSampler.Column c = s.at(dir);
                int cell0 = g.index(f, i, j, 0), k = g.layers - 1;
                while (k > 0 && (p.get(cell0 + k) == Blocks.AIR || p.material(cell0 + k) == Material.ICE)) k--;
                int expected = s.depth() - 1 + c.height();
                assertTrue(k == expected || k == expected + 1, "column " + f + "," + i + "," + j + ": top " + k + " vs " + expected);
                assertEquals(c, s.at(dir), "the same every time");
            }
        }
    }
}
