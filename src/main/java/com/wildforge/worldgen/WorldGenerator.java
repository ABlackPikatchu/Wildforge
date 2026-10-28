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
    private static final long REGION = 0x55ca2b71L;
    private static final long UPLANDS = 0x2ab13f1L;
    private static final long EROSION = 0x3b47e12L;
    private static final long PEAKS = 0x4c58d23L;
    // Regional cell size ~320 blocks, sites vary in position and influence.
    private static final int REGION_SIZE = 320;

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

    /**
     * Geographic-scale relief followed by local details. All fields use world coordinates,
     * so adjacent streamed chunks always sample the same underlying landscape.
     * This is a heightfield model (not simulated hydraulic erosion).
     */
    /** Read-only geographical signals for offline debug maps and tuning. */
    public record Geography(double continentalness, double erosion, double ridges,
                            double upland, double detail, double river) {}

    public Geography geographyAt(int x, int z) {
        double c = continentalness(x,z);
        double erosion = noise.fbm2(x / 500.0, z / 500.0, 3, EROSION);
        double ridge = 1.0 - Math.abs(noise.fbm2(x / settings.mountainsScale(),
                z / settings.mountainsScale(), 3, MOUNTAIN));
        double highland = noise.fbm2(x / 1050.0, z / 1050.0, 3, UPLANDS);
        double detail = noise.fbm2(x / settings.detailScale(), z / settings.detailScale(), 3, DETAIL);
        return new Geography(c, erosion, ridge, highland, detail, riverStrength(x,z));
    }

    private double terrainHeight(int x, int z) {
        double continent = continentalness(x, z);
        double sea = settings.seaLevel();
        double ocean = settings.oceanThreshold();

        // Wide continental shelf and interior; flatter lowlands make room for real plains.
        double shoreline = smoothstep(ocean - 0.13, ocean + 0.18, continent);
        double interior = smoothstep(ocean + 0.04, ocean + 0.40, continent);
        double continentalHeight = sea - 16.0 + shoreline * 25.0 + interior * 15.0;

        // Two independent regional fields yield broad uplands with irregular footprints.
        double highlandField = noise.fbm2(x / 1050.0, z / 1050.0, 3, UPLANDS);
        double highland = smoothstep(-0.16, 0.40, highlandField + continent * 0.45);
        double erosionField = noise.fbm2(x / 500.0, z / 500.0, 3, EROSION);
        // Eroded landscapes are gentler; protected ridges can rise sharply.
        double ruggedness = 1.0 - smoothstep(-0.22, 0.40, erosionField);
        double foothills = shoreline * highland * (12.0 + 17.0 * ruggedness);

        double ridgeField = noise.fbm2(x / settings.mountainsScale(),
                z / settings.mountainsScale(), 3, MOUNTAIN);
        double ridges = 1.0 - Math.abs(ridgeField);
        double peakField = noise.fbm2(x / 260.0, z / 260.0, 3, PEAKS);
        double mountainRegion = highland * smoothstep(0.08, 0.48,
                highlandField + continent * 0.50 + peakField * 0.18);
        double peaks = shoreline * mountainRegion * (13.0 + 46.0 * ruggedness)
                * Math.pow(clamp(ridges, 0.0, 1.0), 3.0);

        // Small-scale relief is attenuated on plains and near the shoreline.
        double hills = noise.fbm2(x / settings.hillsScale(), z / settings.hillsScale(), 4, HILLS);
        double detail = noise.fbm2(x / settings.detailScale(), z / settings.detailScale(), 3, DETAIL);
        double localRelief = shoreline * (3.0 + highland * 7.0) * hills
                + shoreline * (1.2 + 1.8 * highland) * detail;
        double land = continentalHeight + foothills + peaks + localRelief;

        // Incised valleys soften the surrounding terrain; don't create deep dry trenches
        // across submerged shelves or excessively broad cuts on mountain slopes.
        if (shoreline > 0.5 && land > sea + 3.0) {
            double strength = riverStrength(x, z);
            if (strength > 0.0) {
                land -= strength * Math.min(12.0, 5.0 + Math.max(0.0, land - sea) * 0.12);
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
        Biome biome = regionalBiome(x, z, temperature, rainfall,
                y - settings.seaLevel(), geology, coastal, floodplain);
        BiomeProfile profile = biomes.get(biome);
        return new Surface(y, biome, temperature, rainfall, continent, river,
                geology, water, depth, coastal,
                profile.topTexture(), profile.fillerTexture(), geology.baseRockTexture());
    }

    /** Irregular, seed-stable regional sites. Biome footprints vary in size (~200-600 blocks).
     * Site selection is independent of generation order and smooth world coordinates.
     * Climatic suitability is checked at the queried terrain position; candidate sites
     * receive randomized radii so no tiny per-block biome fragments appear.
     */
    private Biome regionalBiome(int x, int z, double temperature, double rainfall,
                                int height, Geology geology, boolean coastal, boolean floodplain) {
        // Domain-warp the regional Voronoi lookup to eliminate straight borders.
        // Coordinates remain in world space, so neighboring chunks agree.
        double warpedX = x + noise.fbm2((x + 1300.0) / 180.0,
                (z - 900.0) / 180.0, 3, REGION ^ 0x9135L) * 70.0;
        double warpedZ = z + noise.fbm2((x - 700.0) / 180.0,
                (z + 1100.0) / 180.0, 3, REGION ^ 0x227aL) * 70.0;
        int cellX = (int)Math.floor(warpedX / REGION_SIZE);
        int cellZ = (int)Math.floor(warpedZ / REGION_SIZE);
        double closest = Double.POSITIVE_INFINITY;
        int bestX = cellX, bestZ = cellZ;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int gx = cellX + dx, gz = cellZ + dz;
            double px = (gx + 0.15 + 0.70 * noise.unit(gx, 0, gz, REGION)) * REGION_SIZE;
            double pz = (gz + 0.15 + 0.70 * noise.unit(gx, 1, gz, REGION)) * REGION_SIZE;
            double radius = 0.80 + noise.unit(gx, 2, gz, REGION) * 0.48;
            double d = ((warpedX - px)*(warpedX - px) + (warpedZ - pz)*(warpedZ - pz)) / (radius * radius);
            if (d < closest) { closest = d; bestX = (int)px; bestZ = (int)pz; }
        }
        // Region-wide sampled climate discourages tiny fragmented biome patches.
        int regionY = (int)Math.round(terrainHeight(bestX, bestZ));
        double t = clamp(0.5 + noise.fbm2(bestX / settings.climateScale(),
                bestZ / settings.climateScale(), 3, TEMP) * 0.48
                - Math.max(0, regionY-settings.seaLevel()) * 0.0035
                - (bestZ / 10000.0) * 0.14, 0, 1);
        double rain = clamp(0.5 + noise.fbm2((bestX + 8000) / settings.climateScale(),
                (bestZ - 8000) / settings.climateScale(), 3, MOISTURE) * .60, 0, 1);
        Biome regional = biomes.select(t, rain, regionY-settings.seaLevel(),
                geologyAt(bestX,bestZ), coastal, floodplain);
        // Respect extreme highlands and regionally restricted coastline/volcanic geology.
        if (coastal || floodplain || height > 95 || geology == Geology.VOLCANIC) {
            return biomes.select(temperature,rainfall,height,geology,coastal,floodplain);
        }
        return regional;
    }

    // First-stage solid-density model. The heightfield supplies the broad landform,
    // while 3D noise changes cliff faces and creates shallow shelves/overhangs.
    // These are world-coordinate samples: neighboring chunks agree exactly.
    private static final long DENSITY_SHAPE = 0x6d2e31a9L;
    private static final long DENSITY_DETAIL = 0x7bff42c1L;

    /**
     * World-coordinate terrain density. Mountain cliffs may form small ledges;
     * lowlands retain the familiar stable ground surface. Positive = solid.
     */
    public double terrainDensity(int x, int y, int z, int surfaceY) {
        return terrainDensity(x, y, z, surfaceY, slopeAt(x, z));
    }

    /** Column-cached variant used during streaming chunk generation. */
    public double terrainDensity(int x, int y, int z, int surfaceY, int slope) {
        double depth = surfaceY + 0.5 - y;
        if (depth > 18.0 || depth < -12.0) return depth;
        // Only genuinely steep, high-altitude terrain may project beyond its
        // heightfield. This prevents floating shelves in ordinary forests.
        double steep = smoothstep(3.5, 12.5, slope);
        double altitude = smoothstep(settings.seaLevel() + 23.0,
                                     settings.seaLevel() + 61.0, surfaceY);
        double relief = steep * altitude;
        if (relief < 0.01) return depth;
        double broad = noise.noise3(x / 52.0, y / 37.0, z / 52.0, DENSITY_SHAPE);
        double detail = noise.noise3(x / 23.0, y / 27.0, z / 23.0, DENSITY_DETAIL);
        // Displacement is continuous, deliberately modest, and tapered with
        // distance from the approximate land surface.
        double taper = 1.0 - smoothstep(4.0, 18.0, Math.abs(depth));
        return depth + 5.0 * relief * taper * (0.8 * broad + 0.2 * detail);
    }

    public boolean isSolidTerrain(int x, int y, int z, int surfaceY, int slope) {
        if (y <= settings.minY() + 4) return true;
        return terrainDensity(x, y, z, surfaceY, slope) > 0.0;
    }

    /** Kept for previews and existing callers. */
    public boolean isSolidTerrain(int x, int y, int z, int surfaceY) {
        return isSolidTerrain(x, y, z, surfaceY, slopeAt(x, z));
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
