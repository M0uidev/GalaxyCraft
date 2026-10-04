package dev.moui.galaxycraft.voxel.gen;

/**
 * A planet's noises, one per climate value, sampled anywhere in 3D: Minecraft's overworld noises
 * in the game (client/McWorldgen), smooth stand-ins in the tests.
 */
public interface TerrainNoise {
    enum Field { CONTINENTALNESS, EROSION, RIDGES, TEMPERATURE, HUMIDITY }

    /** About -1..1 at that point, in the noise's own space (Minecraft samples it at blocks / 4). */
    double value(Field f, double x, double y, double z);
}
