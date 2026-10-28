package com.wildforge.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic 3D cave-region classification. This does not carve or fill
 * terrain: WorldGenerator's existing isCave() remains the carving predicate.
 * Aquifer, mineral and fault values are PROXIES until physical fluids and
 * connected cave structures are implemented in the chunk engine.
 */
public final class UndergroundBiomeSampler {
    private static final long WATER_SALT = 0x3858af6a731cL;
    private static final long FAULT_SALT = 0x4a7efa3fe3f9L;
    private static final long VEIN_SALT = 0x55662ffef9bcL;
    private static final long HEAT_SALT = 0x7745eedfa9d0L;
    private static final long COAL_SALT = 0x8466adef51b9L;
    private static final long EVAPORITE_SALT = 0x96f200b9efafL;
    private static final long REGION_SALT = 0xa7efbac3b4c2L;

    /** Values in 0..1; depth is below local terrain relative to bedrock. */
    public record Context(double depthFraction, Geology geology,
            Biome surfaceBiome, double temperature, double rainfall,
            double moisture, double groundwater, boolean fault,
            boolean mineralVein, boolean coalStrata, boolean evaporite,
            boolean volcanic, boolean glacial, boolean wooded) { }

    private final SeededNoise noise;
    private final UndergroundBiomeCatalog catalog;
    private final UndergroundGenSettings options;
    private final WorldGenSettings terrain;

    public UndergroundBiomeSampler(long seed, WorldGenSettings terrain,
            UndergroundBiomeCatalog catalog, UndergroundGenSettings options) {
        noise = new SeededNoise(seed);
        this.terrain = Objects.requireNonNull(terrain);
        this.catalog = Objects.requireNonNull(catalog);
        this.options = Objects.requireNonNull(options);
    }

    public Context contextAt(int x, int y, int z, WorldGenerator.Surface surface) {
        double depth = clamp((surface.surfaceY() - y)
                / (double) Math.max(1, surface.surfaceY() - terrain.minY()), 0, 1);
        Geology rock = surface.geology();
        double waterNoise = normalize(noise.fbm2(x / options.groundwaterScale(),
                z / options.groundwaterScale(), 3, WATER_SALT));
        double water = clamp(waterNoise * .76 + depth * .21
                + (surface.waterBody() != WorldGenerator.WaterBody.LAND ? .20 : 0)
                + (surface.river() ? .16 : 0), 0, 1);
        double moisture = clamp(surface.rainfall() * .52 + water * .48, 0, 1);
        double faultDistance = Math.abs(noise.noise2(x / options.faultScale(),
                z / options.faultScale(), FAULT_SALT));
        boolean fault = faultDistance < options.faultThreshold();
        boolean mineral = noise.noise3(x / options.mineralScale(),
                y / (options.mineralScale() * .55), z / options.mineralScale(), VEIN_SALT)
                > options.mineralThreshold();
        boolean coalStrata = (rock == Geology.SHALE || rock == Geology.SANDSTONE)
                && noise.noise2(x / 300.0, z / 300.0, COAL_SALT) > -0.12;
        boolean evaporite = rock == Geology.SANDSTONE
                && surface.rainfall() < 0.50 && surface.temperature() > 0.40
                && noise.noise2(x / 320.0, z / 320.0, EVAPORITE_SALT) > 0.1;
        boolean volcanic = (rock == Geology.VOLCANIC || rock == Geology.BASALT)
                && noise.noise2(x / options.volcanicScale(),
                        z / options.volcanicScale(), HEAT_SALT) > -0.1;
        boolean glacial = surface.temperature() < options.coldTemperatureThreshold()
                && (surface.biome() == Biome.FROSTSPIRE_HIGHLANDS
                        || surface.biome() == Biome.FROSTMOOR_TUNDRA);
        boolean wooded = switch (surface.biome()) {
            case GREENHEART_FOREST, NEEDLEWOOD_TAIGA, EMBERLEAF_WOODLAND -> true;
            default -> false;
        };
        return new Context(depth, rock, surface.biome(), surface.temperature(),
                surface.rainfall(), moisture, water, fault, mineral,
                coalStrata, evaporite, volcanic, glacial, wooded);
    }

    /**
     * Normalize weights AFTER hard eligibility checks. No biome is forced
     * if the cave's geology/water/temperature has no suitable profile.
     */
    public UndergroundBiome choose(int x, int y, int z, Context context) {
        List<UndergroundBiomeProfile> candidates = new ArrayList<>();
        double total = 0;
        for (UndergroundBiome biome : UndergroundBiome.values()) {
            UndergroundBiomeProfile profile = catalog.get(biome);
            if (eligible(profile, context)) {
                candidates.add(profile);
                total += weight(profile, context);
            }
        }
        if (candidates.isEmpty()) return null;
        // Smooth region noise (instead of a per-block hash) groups cave biomes.
        double region = normalize(noise.noise3(x / options.regionScale(),
                y / (options.regionScale() * .65), z / options.regionScale(), REGION_SALT));
        double pick = region * total;
        for (UndergroundBiomeProfile profile : candidates) {
            pick -= weight(profile, context);
            if (pick < 0) return profile.biome();
        }
        return candidates.get(candidates.size() - 1).biome();
    }

    public boolean eligible(UndergroundBiomeProfile profile, Context ctx) {
        if (!profile.supportsDepth(ctx.depthFraction())) return false;
        return switch (profile.selectionRule()) {
            case FOREST_ROOTS -> ctx.wooded() && ctx.depthFraction() <= 0.23;
            case WET_ROCK -> ctx.moisture() >= 0.37;
            case AQUIFER_CHANNEL -> ctx.groundwater() >= options.groundwaterThreshold();
            case LIMESTONE -> ctx.geology() == Geology.LIMESTONE;
            case COAL_STRATA -> ctx.coalStrata();
            case EVAPORITE -> ctx.evaporite() && ctx.moisture() < 0.55;
            case GLACIAL -> ctx.glacial();
            case FAULT -> ctx.fault();
            case MINERAL_VEIN -> ctx.mineralVein()
                    && (ctx.geology() == Geology.GRANITE || ctx.geology() == Geology.BASALT
                    || ctx.geology() == Geology.VOLCANIC);
            case SPORE_CHAMBER -> ctx.moisture() >= .66 && ctx.groundwater() >= .56;
            case VOLCANIC -> ctx.volcanic();
            case DEEP_AQUIFER -> ctx.groundwater() >= .70 && ctx.depthFraction() > .47;
        };
    }

    private static double weight(UndergroundBiomeProfile profile, Context context) {
        // All supplied weights are relative. Preference fields are SOFT goals,
        // not additional requirements that would make rare cave types disappear.
        double moistureSimilarity = 1 - Math.abs(context.moisture() - profile.moisturePreference());
        double waterSimilarity = 1 - Math.abs(context.groundwater() - profile.waterProximityPreference());
        return profile.relativeWeight() * (.68 + .20 * moistureSimilarity + .12 * waterSimilarity);
    }

    private static double normalize(double value) {
        return clamp(value * .5 + .5, 0, 1);
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.min(hi, Math.max(lo, value));
    }
}
