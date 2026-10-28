package com.wildforge.worldgen;

import java.util.Objects;

/**
 * Experimental deterministic world-coordinate sampler. Still does NOT replace
 * the 64x64 legacy test world; streamed chunk generation comes next.
 *
 * Surface biome = climate + elevation + hydrology + independent geology.
 * Undersea water is not counted as one of the twelve surface biomes.
 */
public final class WorldGenerator {
    private static final long CONTINENT = 0x10fa42L;
    private static final long HILLS = 0x20beefL;
    private static final long MOUNTAIN = 0x30deadL;
    private static final long DETAIL = 0x40f00dL;
    private static final long TEMP = 0x50cafeL;
    private static final long MOISTURE = 0x60abcdL;
    private static final long RIVER = 0x70abcdL;
    private static final long CAVE = 0x80abcdL;
    private static final long CAVE_BREAKUP = 0x90abcdL;
    private static final long TREES = 0xa0abcdL;
    private static final long PLANTS = 0xb0abcdL;
    private static final long GEOLOGY_A = 0xc0abcdL;
    private static final long GEOLOGY_B = 0xd0abcdL;
    private static final long VOLCANIC = 0xe0abcdL;

    public static final int TREE_CELL_SIZE = 8;
    public static final int PLANT_CELL_SIZE = 4;

    public enum WaterBody { LAND, OCEAN, RIVER }

    public record Surface(int surfaceY, Biome biome, double temperature,
                          double rainfall, double continentalness, boolean river,
                          Geology geology, WaterBody waterBody, int waterDepth,
                          boolean coastal, String topTexture, String fillerTexture,
                          String baseRockTexture) {
        /** Old code and data tools may still refer to moisture. */
        public double moisture() { return rainfall; }
    }

    public enum TreeType { OAK, MAPLE, PINE, CYPRESS, WILLOW }
    public record Tree(int x, int z, int trunkHeight, TreeType type) {}
    public record Plant(int x, int z, String species, boolean tall) {}

    private final long seed;
    private final WorldGenSettings settings;
    private final SeededNoise noise;
    private final BiomeCatalog biomes;
    private final UndergroundBiomeCatalog undergroundBiomes;
    private final UndergroundBiomeSampler undergroundSampler;

    public WorldGenerator(long seed, WorldGenSettings settings) {
        this(seed, settings, BiomeCatalog.loadBuiltInUnchecked());
    }

    public WorldGenerator(long seed, WorldGenSettings settings, BiomeCatalog biomes) {
        this(seed, settings, biomes, UndergroundBiomeCatalog.loadBuiltInUnchecked(),
                loadUndergroundSettings());
    }

    private static UndergroundGenSettings loadUndergroundSettings() {
        try {
            return UndergroundGenSettings.loadBuiltIn();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Missing generated underground settings", ex);
        }
    }

    public WorldGenerator(long seed, WorldGenSettings settings, BiomeCatalog biomes,
                          UndergroundBiomeCatalog undergroundBiomes,
                          UndergroundGenSettings undergroundSettings) {
        this.seed = seed;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.biomes = Objects.requireNonNull(biomes, "biomes");
        this.undergroundBiomes = Objects.requireNonNull(undergroundBiomes, "undergroundBiomes");
        this.undergroundSampler = new UndergroundBiomeSampler(seed, settings, undergroundBiomes,
                Objects.requireNonNull(undergroundSettings, "undergroundSettings"));
        noise = new SeededNoise(seed);
    }

    public long seed() { return seed; }
    public WorldGenSettings settings() { return settings; }
    public BiomeCatalog biomes() { return biomes; }
    public UndergroundBiomeCatalog undergroundBiomes() { return undergroundBiomes; }

    /** Only returns an underground biome for actual carved cave air. */
    public UndergroundBiome caveBiomeAt(int x, int y, int z) {
        Surface land = surface(x, z);
        if (!isCave(x, y, z, land.surfaceY())) return null;
        return undergroundSampler.choose(x, y, z,
                undergroundSampler.contextAt(x, y, z, land));
    }

    /** Exposes computed depth and geology markers to chunk integration/tests. */
    public UndergroundBiomeSampler.Context caveContextAt(int x, int y, int z) {
        return undergroundSampler.contextAt(x, y, z, surface(x, z));
    }

    public UndergroundBiomeSampler caveSampler() { return undergroundSampler; }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
    private static double smoothstep(double a, double b, double x) {
        double t = clamp((x - a) / (b - a), 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    private double terrainHeight(int x, int z) {
        double continent = continentalness(x, z);
        double hill = noise.fbm2(x / settings.hillsScale(), z / settings.hillsScale(), 4, HILLS);
        double ridge = 1.0 - Math.abs(noise.fbm2(x / settings.mountainsScale(),
                z / settings.mountainsScale(), 3, MOUNTAIN));
        double mountainMask = smoothstep(settings.mountainThreshold(),
                settings.mountainThreshold() + 0.30, continent);
        double mountains = mountainMask * Math.pow(ridge, 3) * 95.0;
        double detail = noise.fbm2(x / settings.detailScale(), z / settings.detailScale(), 3, DETAIL) * 3.5;
        double land = settings.seaLevel() + 11.0 + continent * 85.0
                + hill * 13.0 + mountains + detail;
        if (continent < settings.oceanThreshold()) {
            land -= (settings.oceanThreshold() - continent) * 28.0;
        }
        if (continent > settings.oceanThreshold() + 0.07
                && land > settings.seaLevel() + 2) {
            double width = riverStrength(x, z);
            if (width > 0) {
                land -= width * (8.0 + Math.min(20.0, land - settings.seaLevel()) * 0.9);
            }
        }
        return clamp(land, settings.minY() + 5, settings.maxY() - 6);
    }

    private double continentalness(int x, int z) {
        return noise.fbm2(x / settings.continentScale(), z / settings.continentScale(), 5, CONTINENT);
    }
    private double riverStrength(int x, int z) {
        double field = Math.abs(noise.fbm2(x / settings.riverScale(),
                z / settings.riverScale(), 3, RIVER));
        return smoothstep(0.052, 0.014, field);
    }

    /** Rock formation is generated independently of the surface biome and climate. */
    public Geology geologyAt(int x, int z) {
        double first = noise.fbm2(x / 620.0, z / 620.0, 3, GEOLOGY_A);
        double second = noise.fbm2((x + 4000) / 690.0,
                (z - 3000) / 690.0, 3, GEOLOGY_B);
        double hotSpot = noise.fbm2(x / 500.0, z / 500.0, 4, VOLCANIC);
        if (hotSpot > 0.35 && first > -0.15) return Geology.VOLCANIC;
        if (first > 0.16) return Geology.LIMESTONE;
        if (first < -0.18 && second <= 0.0) return Geology.SANDSTONE;
        if (second > 0.12) return Geology.SHALE;
        if (second < -0.16) return Geology.BASALT;
        return Geology.GRANITE;
    }

    /** Slope is evaluated lazily when decorations/spawns need it, not per preview pixel. */
    public int slopeAt(int x, int z) {
        int base = (int)Math.round(terrainHeight(x, z));
        int slope = 0;
        for (int[] offset : new int[][]{{3,0},{-3,0},{0,3},{0,-3}}) {
            int y = (int)Math.round(terrainHeight(x + offset[0], z + offset[1]));
            slope = Math.max(slope, Math.abs(y - base));
        }
        return slope;
    }

    public Surface surface(int x, int z) {
        double continent = continentalness(x, z);
        int y = (int)Math.round(terrainHeight(x, z));
        double temperature = clamp(0.5
                + noise.fbm2(x / settings.climateScale(), z / settings.climateScale(), 3, TEMP) * 0.48
                - Math.max(0, y - settings.seaLevel()) * 0.0035
                - (z / 10000.0) * 0.14, 0, 1);
        double rainfall = clamp(0.5
                + noise.fbm2((x + 8000) / settings.climateScale(),
                        (z - 8000) / settings.climateScale(), 3, MOISTURE) * 0.60, 0, 1);
        int depth = Math.max(0, settings.seaLevel() - y);
        boolean river = depth == 0 && riverStrength(x, z) > 0.45
                && y <= settings.seaLevel() + 3;
        WaterBody water = depth > 0 ? WaterBody.OCEAN : river ? WaterBody.RIVER : WaterBody.LAND;
        boolean coastal = continent <= settings.oceanThreshold() + 0.11
                && y >= settings.seaLevel() - 10 && y <= settings.seaLevel() + 23;
        boolean floodplain = river || (y <= settings.seaLevel() + 22
                && riverStrength(x, z) > 0.32);
        Geology geology = geologyAt(x, z);
        Biome biome = biomes.select(temperature, rainfall, y - settings.seaLevel(),
                geology, coastal, floodplain);
        BiomeProfile profile = biomes.get(biome);
        return new Surface(y, biome, temperature, rainfall, continent, river,
                geology, water, depth, coastal,
                profile.topTexture(), profile.fillerTexture(), geology.baseRockTexture());
    }

    /** Cheap 3D cave-carving predicate; stream-chunk engine will apply this later. */
    public boolean isCave(int x, int y, int z, int surfaceY) {
        if (y < settings.minY() + 5 || y > surfaceY - 6) return false;
        double main = noise.noise3(x / settings.caveScale(), y / (settings.caveScale() * 0.73),
                z / settings.caveScale(), CAVE);
        double breakup = noise.noise3(x / (settings.caveScale() * 0.65),
                y / (settings.caveScale() * 0.55), z / (settings.caveScale() * 0.65), CAVE_BREAKUP);
        return Math.abs(main) < settings.caveThreshold() && breakup > -0.27;
    }

    /** One tree candidate per 8x8 world-cell, independent of chunk-loading order. */
    public Tree treeForCell(int cellX, int cellZ) {
        int x = cellX * TREE_CELL_SIZE + (int)(noise.unit(cellX, 0, cellZ, TREES) * TREE_CELL_SIZE);
        int z = cellZ * TREE_CELL_SIZE + (int)(noise.unit(cellX, 1, cellZ, TREES) * TREE_CELL_SIZE);
        Surface surface = surface(x, z);
        if (surface.waterBody() != WaterBody.LAND) return null;
        BiomeProfile profile = biomes.get(surface.biome());
        if (slopeAt(x,z) > profile.maxTreeSlope()) return null;
        double local = clamp(0.85 + noise.noise2(x / 150.0, z / 150.0, TREES) * 0.5, 0.20, 1.0);
        double chance = profile.treeDensity() * settings.treeDensity() * local;
        if (noise.unit(cellX, 2, cellZ, TREES) >= chance) return null;
        String tree = BiomeProfile.chooseFeature(profile.trees(), noise.unit(cellX, 3, cellZ, TREES));
        if (tree == null) return null;
        TreeType type = TreeType.valueOf(tree.toUpperCase(java.util.Locale.ROOT));
        int height = 5 + (int)(noise.unit(cellX, 4, cellZ, TREES) * 4);
        return new Tree(x, z, height, type);
    }

    /** Decorations depend on biome-specific rules, hydrology, slope, and local density. */
    public Plant plantForCell(int cellX, int cellZ) {
        int x = cellX * PLANT_CELL_SIZE + (int)(noise.unit(cellX, 0, cellZ, PLANTS) * PLANT_CELL_SIZE);
        int z = cellZ * PLANT_CELL_SIZE + (int)(noise.unit(cellX, 1, cellZ, PLANTS) * PLANT_CELL_SIZE);
        Surface surface = surface(x, z);
        if (surface.waterBody() != WaterBody.LAND) return null;
        BiomeProfile profile = biomes.get(surface.biome());
        if (slopeAt(x,z) > profile.maxPlantSlope()) return null;
        double local = clamp(0.85 + noise.noise2(x / 75.0, z / 75.0, PLANTS) * .5, .20,1.0);
        double chance = profile.plantDensity() * settings.plantDensity() * local;
        if (noise.unit(cellX, 2, cellZ, PLANTS) >= chance) return null;
        String species = BiomeProfile.chooseFeature(profile.plants(),
                noise.unit(cellX, 3, cellZ, PLANTS));
        if (species == null) return null;
        boolean tall = species.startsWith("tall_")
                || species.equals("marsh_reeds") || species.equals("cattails")
                || species.equals("seaweed");
        return new Plant(x, z, species, tall);
    }
}
