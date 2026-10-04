package dev.moui.galaxycraft.voxel.gen;

/**
 * Minecraft's climate at a point, each value about -1..1 as its overworld noises give them:
 * continentalness (sea to inland), erosion (low = mountainous), ridges ("weirdness": peaks and
 * valleys), temperature and humidity.
 */
public record Climate(double continentalness, double erosion, double ridges, double temperature, double humidity) {
    /** Where a biome appears: each value from min's to max's. */
    public record Span(Climate min, Climate max) {
        /** n (-1..1) of each value put into this span, -1 at its min and 1 at its max. */
        public Climate map(Climate n) {
            return new Climate(lerp(min.continentalness, max.continentalness, n.continentalness),
                    lerp(min.erosion, max.erosion, n.erosion), lerp(min.ridges, max.ridges, n.ridges),
                    lerp(min.temperature, max.temperature, n.temperature), lerp(min.humidity, max.humidity, n.humidity));
        }

        private static double lerp(double lo, double hi, double n) {
            return lo + (Math.clamp(n, -1, 1) + 1) / 2 * (hi - lo);
        }
    }
}
