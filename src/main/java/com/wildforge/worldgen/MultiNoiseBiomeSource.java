package com.wildforge.worldgen;

/**
 * Continuous multi-parameter biome lookup. Unlike the legacy Voronoi map, a
 * biome follows shared temperature/humidity/terrain signals and transitions
 * where its authored envelope ceases to fit. Geology/floodplain are special
 * eligibility gates, not random patches. All 35 Wildforge names remain intact.
 */
public final class MultiNoiseBiomeSource {
    private final BiomeCatalog catalog;
    private final SeededNoise noise;

    public MultiNoiseBiomeSource(BiomeCatalog catalog, long seed) {
        this.catalog = catalog;
        this.noise = new SeededNoise(seed);
    }

    /** The caller provides terrain-derived height and classification, never an
     * independently modified biome-dependent height. */
    public Biome select(int x, int z, ClimateField.Sample climate, double temperature,
                        int aboveSea, Geology rock, boolean coast, boolean floodplain,
                        WorldGenerator.Landform landform) {
        Biome best = Biome.SUNMEADOW_PLAINS;
        double lowest = Double.POSITIVE_INFINITY;
        double slowVariation = noise.fbm2(x / 340.0, z / 340.0, 2, 0x7392feaL);
        for (Biome type : Biome.values()) {
            BiomeProfile p = catalog.get(type);
            if (p.coastOnly() && !coast) continue;
            if (p.riverOrFloodplainOnly() && !floodplain) continue;
            if (!p.requiredGeology().isEmpty() && !p.requiredGeology().contains(rock)) continue;
            double distance = intervalDistance(temperature, p.temperature(), .26) * 2.0
                    + intervalDistance(climate.humidity(), p.rainfall(), .26) * 2.0
                    + intervalDistance(aboveSea, p.heightAboveSea(), 70.0) * 1.4;
            // Landform affinities and independently generated regional variations
            // allow rare special biomes without forcing arbitrary square regions.
            distance -= WorldGenerator.landformAffinity(type, landform) * .16;
            distance -= Math.min(20,p.priority()) * .014;
            distance += slowVariation * (.04 + type.ordinal()%5*.015);
            // A smooth individual field prevents equal-range biomes from always
            // resolving to the first enum, without producing one-block speckle.
            distance += noise.noise2(x / 270.0, z / 270.0, 0x28f4a5L + type.ordinal()*31L) * .24;
            if (distance < lowest) { lowest = distance; best = type; }
        }
        return best;
    }

    private static double intervalDistance(double value, BiomeProfile.Range interval, double scale) {
        double outside = value < interval.min() ? interval.min()-value
                : value > interval.max() ? value-interval.max() : 0;
        // Weak center preference produces stable broad cores within envelopes.
        double middle=(interval.min()+interval.max())*.5;
        return Math.pow(outside/scale, 2) + .018 * Math.abs(value-middle)/scale;
    }
}
