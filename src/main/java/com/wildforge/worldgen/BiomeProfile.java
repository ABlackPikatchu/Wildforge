package com.wildforge.worldgen;

import java.util.List;
import java.util.Objects;

/** Data-loaded climate, material and decoration rules for one surface biome. */
public record BiomeProfile(
        Biome biome,
        String displayName,
        int mapColor,
        Range temperature,
        Range rainfall,
        Range heightAboveSea,
        List<Geology> requiredGeology,
        boolean coastOnly,
        boolean riverOrFloodplainOnly,
        int priority,
        String topTexture,
        String fillerTexture,
        double treeDensity,
        double plantDensity,
        int maxTreeSlope,
        int maxPlantSlope,
        List<WeightedFeature> trees,
        List<WeightedFeature> plants) {

    public record Range(double min, double max) {
        public Range {
            if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) {
                throw new IllegalArgumentException("Invalid range " + min + ".." + max);
            }
        }
        public boolean contains(double value) { return value >= min && value <= max; }
    }

    public record WeightedFeature(String id, int weight) {
        public WeightedFeature {
            if (id == null || !id.matches("[a-z][a-z0-9_]*") || weight <= 0) {
                throw new IllegalArgumentException("Invalid decoration feature " + id + ":" + weight);
            }
        }
    }

    public BiomeProfile {
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(temperature, "temperature");
        Objects.requireNonNull(rainfall, "rainfall");
        Objects.requireNonNull(heightAboveSea, "heightAboveSea");
        Objects.requireNonNull(topTexture, "topTexture");
        Objects.requireNonNull(fillerTexture, "fillerTexture");
        requiredGeology = List.copyOf(requiredGeology);
        trees = List.copyOf(trees);
        plants = List.copyOf(plants);
        if (!Double.isFinite(treeDensity) || treeDensity < 0 || treeDensity > 1
                || !Double.isFinite(plantDensity) || plantDensity < 0 || plantDensity > 1
                || maxTreeSlope < 0 || maxPlantSlope < 0) {
            throw new IllegalArgumentException("Invalid vegetation constraints in " + biome);
        }
    }

    public boolean matches(double temp, double rain, double height,
                           Geology geology, boolean coastal, boolean floodplain) {
        return temperature.contains(temp)
                && rainfall.contains(rain)
                && heightAboveSea.contains(height)
                && (requiredGeology.isEmpty() || requiredGeology.contains(geology))
                && (!coastOnly || coastal)
                && (!riverOrFloodplainOnly || floodplain);
    }

    public static String chooseFeature(List<WeightedFeature> features, double selection) {
        if (features.isEmpty()) return null;
        int total = 0;
        for (WeightedFeature feature : features) total += feature.weight();
        // The deterministic hash is in [0,1), so total weight cannot overflow selection.
        double pick = Math.max(0.0, Math.min(Math.nextDown(1.0), selection)) * total;
        for (WeightedFeature feature : features) {
            pick -= feature.weight();
            if (pick < 0) return feature.id();
        }
        return features.get(features.size() - 1).id();
    }
}
