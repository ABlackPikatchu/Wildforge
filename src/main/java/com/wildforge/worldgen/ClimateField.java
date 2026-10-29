package com.wildforge.worldgen;

/**
 * Seed-stable, continuous Overworld climate sampled in absolute coordinates.
 * Inspired by Minecraft's multi-noise climate axes, not its implementation.
 * Continentalness, erosion and ridges influence terrain; temperature and
 * humidity describe ecosystems independently of the terrain palette.
 */
public final class ClimateField {
    private static final long CONTINENTS = 0x10fa42L;
    private static final long EROSION = 0x3b47e12L;
    private static final long RIDGES = 0x4c58d23L;
    private static final long TEMP = 0x50cafeL;
    private static final long HUMIDITY = 0x60abcdL;
    private static final long SHIFT_X = 0x41bab23L;
    private static final long SHIFT_Z = 0x91fe173L;
    private final SeededNoise noise;
    private final WorldGenSettings settings;

    public record Sample(double temperature, double humidity, double continentalness,
                         double erosion, double weirdness, double peaksValleys) {}

    public ClimateField(long seed, WorldGenSettings settings) {
        this.noise = new SeededNoise(seed);
        this.settings = settings;
    }

    public Sample sample(int x, int z) {
        // Low-frequency domain shift breaks aligned contours without sharp cell boundaries.
        double warpX = noise.noise2(x / 1350.0, z / 1350.0, SHIFT_X) * 95.0;
        double warpZ = noise.noise2(x / 1350.0, z / 1350.0, SHIFT_Z) * 95.0;
        double sx = x + warpX, sz = z + warpZ;
        double continental = noise.fbm2(sx / settings.continentScale(), sz / settings.continentScale(), 4, CONTINENTS);
        double erosion = noise.fbm2(sx / 510.0, sz / 510.0, 3, EROSION);
        double weirdness = noise.fbm2(sx / settings.mountainsScale(), sz / settings.mountainsScale(), 3, RIDGES);
        double peaks = 1.0 - Math.abs(weirdness);
        double temperature = clamp(0.5 + noise.fbm2(sx / settings.climateScale(), sz / settings.climateScale(), 3, TEMP) * .64
                - Math.max(-.12, Math.min(.12, z / 16000.0)), 0, 1);
        double humidity = clamp(0.5 + noise.fbm2((sx + 8000) / settings.climateScale(),
                (sz - 8000) / settings.climateScale(), 3, HUMIDITY) * .72, 0, 1);
        return new Sample(temperature, humidity, continental, erosion, weirdness, peaks);
    }

    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
}
