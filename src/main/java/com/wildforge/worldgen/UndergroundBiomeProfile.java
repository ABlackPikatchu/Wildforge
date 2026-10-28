package com.wildforge.worldgen;

import java.util.List;
import java.util.Objects;

/** Immutable typed projection of a generated underground profile. */
public record UndergroundBiomeProfile(
        UndergroundBiome biome,
        String displayName,
        BiomeProfile.Range depthFraction,
        String rarity,
        Rule selectionRule,
        int relativeWeight,
        int minChamberRadius,
        int maxChamberRadius,
        double moisturePreference,
        double waterProximityPreference,
        List<String> hostRocks,
        List<String> existingTextures,
        List<String> newTextures,
        List<UndergroundBiome> transitions,
        String placement,
        String geometry,
        String description) {

    public enum Rule {
        FOREST_ROOTS, WET_ROCK, AQUIFER_CHANNEL, LIMESTONE, COAL_STRATA,
        EVAPORITE, GLACIAL, FAULT, MINERAL_VEIN, SPORE_CHAMBER, VOLCANIC, DEEP_AQUIFER
    }

    public UndergroundBiomeProfile {
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(depthFraction, "depthFraction");
        Objects.requireNonNull(selectionRule, "selectionRule");
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(description, "description");
        hostRocks = List.copyOf(hostRocks);
        existingTextures = List.copyOf(existingTextures);
        newTextures = List.copyOf(newTextures);
        transitions = List.copyOf(transitions);
        if (!rarity.matches("common|uncommon|rare")) {
            throw new IllegalArgumentException("Bad cave rarity: " + rarity);
        }
        if (depthFraction.min() < 0 || depthFraction.max() > 1
                || relativeWeight <= 0 || minChamberRadius < 1
                || maxChamberRadius < minChamberRadius || maxChamberRadius > 128
                || !normalized(moisturePreference) || !normalized(waterProximityPreference)
                || hostRocks.isEmpty() || newTextures.isEmpty()) {
            throw new IllegalArgumentException("Invalid underground profile: " + biome);
        }
    }

    private static boolean normalized(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    public boolean supportsDepth(double depth) {
        return depthFraction.contains(depth);
    }
}
