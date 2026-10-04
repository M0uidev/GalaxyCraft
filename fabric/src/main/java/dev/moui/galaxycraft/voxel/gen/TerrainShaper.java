package dev.moui.galaxycraft.voxel.gen;

/**
 * How high the ground is for a climate, in blocks above a planet's base surface (negative below),
 * Minecraft's way made simple: continentalness raises the base inland, erosion sets how much relief
 * there is and the ridges' peaks and valleys shape it. For planets of radius 64 and up; smaller ones
 * scale it down.
 */
public final class TerrainShaper {
    private static final double[] CONT_AT = {-1, -0.11, 0.03, 0.3, 1}, CONT_H = {-4, -1, 0, 2, 5};
    private static final double[] ERO_AT = {-1, -0.78, -0.375, -0.2225, 0.05, 0.45, 0.55, 1};
    private static final double[] ERO_RELIEF = {1, 0.85, 0.5, 0.35, 0.22, 0.1, 0.25, 0.06};
    private static final double PEAK = 22, VALLEY = 9;

    private TerrainShaper() {}

    /** Minecraft's peaks and valleys from the ridges: -1 in valleys, 1 on peaks. */
    public static double peaksAndValleys(double ridges) {
        return -(Math.abs(Math.abs(ridges) - 2 / 3.0) - 1 / 3.0) * 3;
    }

    public static double height(Climate c) {
        double pv = peaksAndValleys(c.ridges());
        double relief = curve(ERO_AT, ERO_RELIEF, c.erosion());
        return curve(CONT_AT, CONT_H, c.continentalness()) + relief * pv * (pv > 0 ? PEAK : VALLEY);
    }

    /** Piecewise linear through (at[i], v[i]), flat past the ends. */
    static double curve(double[] at, double[] v, double x) {
        if (x <= at[0]) return v[0];
        for (int i = 1; i < at.length; i++)
            if (x <= at[i]) return v[i - 1] + (x - at[i - 1]) / (at[i] - at[i - 1]) * (v[i] - v[i - 1]);
        return v[v.length - 1];
    }
}
