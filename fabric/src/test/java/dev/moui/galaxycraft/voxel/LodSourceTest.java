package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.gen.GenFixtures;
import dev.moui.galaxycraft.voxel.gen.PlanetGenerator;
import dev.moui.galaxycraft.voxel.gen.SurfaceSampler;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class LodSourceTest {
    @Test void aCoarseFaceIsPatchesByPatchesOfTops() {
        VoxelPlanet p = VoxelPlanet.standard();
        PlanetLod.Part[] parts = PlanetLod.coarse(LodSource.of(p), 12, 80);
        assertEquals(6, parts.length);
        for (PlanetLod.Part part : parts) {
            Vector3d[] v = PlanetLodTest.vertices(part);
            assertEquals(4 * (12 * 12 + 4 * 12), v.length, "tops and the four skirts of a flat face");
            assertTrue(part.sphere()[3] > 0);
        }
    }

    @Test void fewerPatchesWeighLess() {
        VoxelPlanet p = VoxelPlanet.standard();
        int[] size = new int[3];
        int[] levels = {3, 6, 12};
        for (int l = 0; l < 3; l++)
            for (PlanetLod.Part part : PlanetLod.coarse(LodSource.of(p), levels[l], 80)) size[l] += part.displayList().length;
        assertTrue(size[0] < size[1] && size[1] < size[2], java.util.Arrays.toString(size));
    }

    @Test void aSampledPlanetLooksLikeTheBuiltOne() {
        PlanetBlueprint bp = PlanetBlueprint.standard("g", 48).withMode(PlanetBlueprint.Mode.GENERATED).withBiome(3, "minecraft:plains", 0)
                .withUnderground(0, false, 0).withPlants(0);
        VoxelPlanet built = PlanetGenerator.build(bp, null, GenFixtures.B, GenFixtures::id);
        SurfaceSampler s = new SurfaceSampler(bp);
        PlanetLod.Part[] real = PlanetLod.coarse(LodSource.of(built), 6, 80);
        PlanetLod.Part[] sampled = PlanetLod.coarse(LodSource.sampled(s, GenFixtures.B, GenFixtures::id), 6, 80);
        for (int f = 0; f < 6; f++)
            assertEquals(mean(real[f]), mean(sampled[f]), 2.0, "face " + f + ": ground about as high");
    }

    private static double mean(PlanetLod.Part part) {
        double sum = 0;
        Vector3d[] v = PlanetLodTest.vertices(part);
        for (Vector3d x : v) sum += x.length();
        return sum / v.length;
    }

    @Test void aStridedLookAtTheCellsSeesTheSameFlatGround() {
        VoxelPlanet p = VoxelPlanet.standard();
        PlanetLod.Part[] all = PlanetLod.coarse(LodSource.of(p), 6, 80), strided = PlanetLod.coarse(LodSource.of(p, 3), 6, 80);
        for (int f = 0; f < 6; f++) assertEquals(mean(all[f]), mean(strided[f]), 1e-6);
    }

    @Test void aFlatSourceIsABallOfOneBlockAtTheSurface() {
        int grass = CubeBlocks.INSTANCE.id(Material.GRASS);
        LodSource flat = LodSource.flat(48, CubeBlocks.INSTANCE, grass);
        PlanetLod.Part[] parts = PlanetLod.coarse(flat, 6, 80);
        double surface = flat.grid().radiusAt(VoxelPlanet.groundDepth(48));
        for (PlanetLod.Part part : parts) {
            double top = 0;
            for (Vector3d v : PlanetLodTest.vertices(part)) top = Math.max(top, v.length());
            assertEquals(surface, top, 0.05, "the tops (skirts hang below)");
        }
    }
}
