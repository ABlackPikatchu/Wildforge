package com.wildforge.worldgen;

import java.util.Objects;

/**
 * Experimental deterministic world-coordinate sampler. Still does NOT replace
 * the 64x64 legacy test world; streamed chunk generation comes next.
 *
 * Surface biome = climate + elevation + hydrology + independent geology.
 * Undersea water is not counted as one of the 35 surface biomes.
 */
public final class WorldGenerator {
    private static final long CONTINENT = 0x10fa42L;
    private static final long HILLS = 0x20beefL;
    private static final long MOUNTAIN = 0x30deadL;
    private static final long DETAIL = 0x40f00dL;
    private static final long TEMP = 0x50cafeL;
    private static final long MOISTURE = 0x60abcdL;
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
    // Regional site spacing ~192 blocks; adjacent sites may still form larger biomes.
    private static final int REGION_SIZE = 192;

    public static final int TREE_CELL_SIZE = 7;
    public static final int PLANT_CELL_SIZE = 4;

    public enum WaterBody { LAND, OCEAN, RIVER, LAKE }

    public record Surface(int surfaceY, Biome biome, double temperature,
                          double rainfall, double continentalness, boolean river,
                          Geology geology, WaterBody waterBody, int waterDepth,
                          boolean coastal, String topTexture, String fillerTexture,
                          String baseRockTexture, int waterSurfaceY) {
        /** Old code and data tools may still refer to moisture. */
        public double moisture() { return rainfall; }
    }

    public enum TreeType { OAK, MAPLE, PINE, CYPRESS, WILLOW, BIRCH, SPRUCE, ACACIA, ALDER, MANGROVE, PALM }
    /** Regional marine classification; sea floor remains independent of land biome tags. */
    public enum OceanBiome { TEMPERATE, DEEP, WARM, COLD, FROZEN, REEF, KELP, ABYSSAL, ESTUARY, VOLCANIC }

    public record Tree(int x, int z, int trunkHeight, TreeType type) {}
    public record Plant(int x, int z, String species, boolean tall) {}

    private final long seed;
    private final WorldGenSettings settings;
    private final SeededNoise noise;
    private final BiomeCatalog biomes;
    private final UndergroundBiomeCatalog undergroundBiomes;
    private final UndergroundBiomeSampler undergroundSampler;
    private final HydrologyNetwork hydrology;

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
        hydrology = new HydrologyNetwork(seed, settings.seaLevel(), this::baseTerrainHeight);
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
        return new Geography(c, erosion, ridge, highland, detail,
                hydrology.sample(x,z,baseTerrainHeight(x,z)).drainage());
    }

    /** Large-scale geographic character, independent of surface block palette. */
    public enum Landform { OCEAN_SHELF, COASTAL_LOWLAND, PLAIN, ROLLING_UPLAND,
                           FOOTHILLS, MOUNTAIN_RANGE, ERODED_PLATEAU, BASIN }

    /** Deterministic regional classification; never depends on chunk-load order. */
    public Landform landformAt(int x, int z) {
        Geography g = geographyAt(x, z);
        double c = g.continentalness();
        double shoreline = smoothstep(settings.oceanThreshold() - 0.13,
                settings.oceanThreshold() + 0.18, c);
        if (c < settings.oceanThreshold() - 0.075) return Landform.OCEAN_SHELF;
        if (shoreline < 0.70) return Landform.COASTAL_LOWLAND;
        double upland = smoothstep(-0.16, 0.40, g.upland() + c * 0.45);
        double rugged = 1.0 - smoothstep(-0.22, 0.40, g.erosion());
        if (upland > 0.63 && rugged > 0.50 && g.ridges() > 0.48)
            return Landform.MOUNTAIN_RANGE;
        if (upland > 0.50 && rugged > 0.48) return Landform.FOOTHILLS;
        if (upland > 0.58 && rugged < 0.42) return Landform.ERODED_PLATEAU;
        if (g.erosion() > 0.24 && g.upland() < -0.05) return Landform.BASIN;
        if (upland > 0.33) return Landform.ROLLING_UPLAND;
        return Landform.PLAIN;
    }

    private double terrainHeight(int x, int z) {
        double base=baseTerrainHeight(x,z);
        return hydrology.sample(x,z,base).groundY();
    }

    private double baseTerrainHeight(int x, int z) {
        double continent = continentalness(x, z);
        double sea = settings.seaLevel();
        double ocean = settings.oceanThreshold();

        // Wide continental shelf and interior; flatter lowlands make room for real plains.
        double shoreline = smoothstep(ocean - 0.13, ocean + 0.18, continent);
        double interior = smoothstep(ocean + 0.04, ocean + 0.40, continent);
        double continentalHeight = sea - 16.0 + shoreline * 25.0 + interior * 15.0;
        // Continental shelf, deep basins and occasional narrow trenches form one
        // continuous depth field, not separate rectangles cut into the seabed.
        double offshore = 1.0 - shoreline;
        double basin = smoothstep(-0.13, 0.12,
                noise.fbm2(x/760.0,z/760.0,3,CONTINENT ^ 0x341aL));
        double trench = smoothstep(0.42,0.73,Math.abs(
                noise.fbm2(x/330.0,z/330.0,3,CONTINENT ^ 0x8720L)));
        continentalHeight -= offshore * (34.0*basin + 37.0*trench);


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
        // The expanded 512-block world permits real mountain massifs, rather than
        // merely moving the old 256-block landscape up by 160 blocks.
        // Broad lift forms a connected highland base; two ridge frequencies
        // create main summits and subordinate spurs without single-cell spikes.
        double massif = smoothstep(0.25, 0.77, highlandField + continent * 0.48);
        double ruggedLift = shoreline * massif * (14.0 + 40.0 * ruggedness);
        double ridgeSpurs = 1.0 - Math.abs(noise.fbm2(x / 360.0, z / 360.0, 3, PEAKS + 17));
        double summitMask = smoothstep(0.40, 0.82, massif * ruggedness + 0.18 * peakField);
        double peaks = shoreline * mountainRegion * (24.0 + 90.0 * ruggedness)
                * Math.pow(clamp(ridges, 0.0, 1.0), 3.0)
                * (0.72 + 0.28 * clamp(ridgeSpurs, 0.0, 1.0));
        double spurs = shoreline * summitMask * 19.0
                * Math.pow(clamp(ridgeSpurs, 0.0, 1.0), 4.0);
        // Erosion-resistant ridgelines develop craggy shoulders and narrow saddles.
        // This modulation is masked to mountains and fades to zero in lowlands.
        double alpine = shoreline * mountainRegion * smoothstep(0.35,0.78,massif);
        double crest = 1.0 - Math.abs(noise.fbm2(x/155.0,z/155.0,3,PEAKS+31));
        double ribs = 1.0 - Math.abs(noise.fbm2(x/77.0,z/77.0,2,MOUNTAIN+43));
        double crags = alpine * ruggedness * (10.0*Math.pow(clamp(crest,0,1),5.0)
                + 5.0*Math.pow(clamp(ribs,0,1),6.0)-3.5);

        // Small-scale relief is attenuated on plains and near the shoreline.
        double hills = noise.fbm2(x / settings.hillsScale(), z / settings.hillsScale(), 4, HILLS);
        double detail = noise.fbm2(x / settings.detailScale(), z / settings.detailScale(), 3, DETAIL);
        double localRelief = shoreline * (3.0 + highland * 7.0) * hills
                + shoreline * (1.2 + 1.8 * highland) * detail;
        // Broad geological landforms complement the narrow noise ridges. These
        // smooth fields make recognizable plateaus and low basins rather than
        // randomly scattering large mountains into plains.
        double plateauMask = smoothstep(0.23, 0.60, highlandField + continent * 0.40)
                * smoothstep(-0.10, 0.38, erosionField);
        double basinMask = smoothstep(0.12, -0.35, highlandField)
                * smoothstep(-0.05, 0.40, erosionField);
        double plateauHeight = 9.0 * plateauMask * shoreline;
        double basinLowering = 5.0 * basinMask * shoreline;
        double land = continentalHeight + foothills + ruggedLift + peaks + spurs + crags
                + localRelief + plateauHeight - basinLowering;

        // HydrologyNetwork incises connected downhill streams separately. The
        // base field must never call hydrology or trace routing becomes recursive.
        return clamp(land, settings.minY() + 5, settings.maxY() - 6);
    }

    private double continentalness(int x, int z) {
        return noise.fbm2(x / settings.continentScale(), z / settings.continentScale(), 5, CONTINENT);
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
        HydrologyNetwork.Sample drainage=hydrology.sample(x,z,baseTerrainHeight(x,z));
        int y=(int)Math.round(drainage.groundY());
        double temperature = clamp(0.5
                + noise.fbm2(x / settings.climateScale(), z / settings.climateScale(), 3, TEMP) * 0.48
                - Math.max(0, y - settings.seaLevel()) * 0.0035
                - (z / 10000.0) * 0.14, 0, 1);
        double rainfall = clamp(0.5
                + noise.fbm2((x + 8000) / settings.climateScale(),
                        (z - 8000) / settings.climateScale(), 3, MOISTURE) * 0.60, 0, 1);
        int depth=Math.max(0,settings.seaLevel()-y);
        WaterBody water=depth>0?WaterBody.OCEAN:drainage.wet() ?
                drainage.kind()==HydrologyNetwork.Kind.LAKE?WaterBody.LAKE:WaterBody.RIVER:WaterBody.LAND;
        boolean river=water==WaterBody.RIVER;
        int waterY=water==WaterBody.OCEAN?settings.seaLevel():
                water==WaterBody.LAND?y:drainage.waterY();
        boolean coastal = continent <= settings.oceanThreshold() + 0.11
                && y >= settings.seaLevel() - 10 && y <= settings.seaLevel() + 23;
        boolean floodplain = river || water==WaterBody.LAKE ||
                (drainage.drainage()>0.4 && y<=settings.seaLevel()+45);
        Geology geology = geologyAt(x, z);
        Biome biome = regionalBiome(x, z, temperature, rainfall,
                y - settings.seaLevel(), geology, coastal, floodplain);
        BiomeProfile profile = biomes.get(biome);
        return new Surface(y, biome, temperature, rainfall, continent, river,
                geology, water, depth, coastal,
                profile.topTexture(), profile.fillerTexture(), geology.baseRockTexture(),waterY);
    }

    /** Irregular, seed-stable regional sites. Biome footprints vary in size (~120-400 blocks, adjacent matching regions can merge).
     * Site selection is independent of generation order and smooth world coordinates.
     * Climatic suitability is checked at the queried terrain position; candidate sites
     * receive randomized radii so no tiny per-block biome fragments appear.
     */
    private Biome regionalBiome(int x, int z, double temperature, double rainfall,
                                int height, Geology geology, boolean coastal, boolean floodplain) {
        // Domain-warp the regional Voronoi lookup to eliminate straight borders.
        // Coordinates remain in world space, so neighboring chunks agree.
        double warpedX = x + noise.fbm2((x + 1300.0) / 180.0,
                (z - 900.0) / 180.0, 3, REGION ^ 0x9135L) * 42.0;
        double warpedZ = z + noise.fbm2((x - 700.0) / 180.0,
                (z + 1100.0) / 180.0, 3, REGION ^ 0x227aL) * 42.0;
        int cellX = (int)Math.floor(warpedX / REGION_SIZE);
        int cellZ = (int)Math.floor(warpedZ / REGION_SIZE);
        double closest = Double.POSITIVE_INFINITY, runnerUp = Double.POSITIVE_INFINITY;
        int bestX = cellX, bestZ = cellZ;
        int secondX = cellX, secondZ = cellZ;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int gx = cellX + dx, gz = cellZ + dz;
            double px = (gx + 0.15 + 0.70 * noise.unit(gx, 0, gz, REGION)) * REGION_SIZE;
            double pz = (gz + 0.15 + 0.70 * noise.unit(gx, 1, gz, REGION)) * REGION_SIZE;
            double radius = 0.84 + noise.unit(gx, 2, gz, REGION) * 0.32;
            double d = ((warpedX - px)*(warpedX - px) + (warpedZ - pz)*(warpedZ - pz)) / (radius * radius);
            if (d < closest) {
                runnerUp=closest;secondX=bestX;secondZ=bestZ;
                closest=d;bestX=(int)px;bestZ=(int)pz;
            } else if(d<runnerUp) {
                runnerUp=d;secondX=(int)px;secondZ=(int)pz;
            }
        }
        Biome primary = biomeForSite(bestX,bestZ);
        // Soft boundary mixing: a narrow band selects between compatible
        // neighboring regional identities in large coherent 8-15 block patches.
        // The terrain height itself remains continuous and is not rescaled.
        double separation=(runnerUp-closest)/(REGION_SIZE*(double)REGION_SIZE);
        if(separation<.30 && runnerUp<Double.POSITIVE_INFINITY){
            double patch=noise.fbm2(x/12.0,z/12.0,2,REGION^0x7181L);
            double closeness=Math.max(0,Math.min(1,1.0-separation/.30));
            if((patch+.35)*.65 < closeness*.48)
                return biomeForSite(secondX,secondZ);
        }
        return primary;
    }

    private Biome biomeForSite(int bestX,int bestZ) {
        // Region-wide sampled climate discourages tiny fragmented biome patches.
        int regionY = (int)Math.round(terrainHeight(bestX, bestZ));
        double t = clamp(0.5 + noise.fbm2(bestX / settings.climateScale(),
                bestZ / settings.climateScale(), 3, TEMP) * 0.48
                - Math.max(0, regionY-settings.seaLevel()) * 0.0035
                - (bestZ / 10000.0) * 0.14, 0, 1);
        double rain = clamp(0.5 + noise.fbm2((bestX + 8000) / settings.climateScale(),
                (bestZ - 8000) / settings.climateScale(), 3, MOISTURE) * .60, 0, 1);
        // Mild per-region microclimate variation creates additional biome identities
        // without generating one-block patches or breaking chunk determinism.
        t = clamp(t + (noise.unit(bestX, 17, bestZ, REGION ^ 0x1e9L)-0.5)*0.13, 0, 1);
        rain = clamp(rain + (noise.unit(bestX, 23, bestZ, REGION ^ 0x94eL)-0.5)*0.17, 0, 1);
        // Evaluate eligibility at the regional anchor, not independently for every
        // surface column. This prevents single-column volcanic/coastal intrusions.
        Geology siteGeology = geologyAt(bestX, bestZ);
        Landform siteLandform = landformAt(bestX, bestZ);
        double siteContinent = continentalness(bestX, bestZ);
        boolean siteCoastal = siteLandform == Landform.COASTAL_LOWLAND
                || (siteContinent <= settings.oceanThreshold() + 0.11
                    && regionY >= settings.seaLevel() - 10
                    && regionY <= settings.seaLevel() + 23);
        boolean siteFloodplain = hydrology.sample(bestX,bestZ,baseTerrainHeight(bestX,bestZ)).drainage()>0.40
                && regionY <= settings.seaLevel()+45;
        return selectLandformBiome(t, rain, regionY - settings.seaLevel(),
                siteGeology, siteCoastal, siteFloodplain, siteLandform, bestX, bestZ);
    }

    /**
     * Selects only biomes whose authored climate/geology constraints match the
     * regional anchor, then prefers the ones fitting that anchor's landform.
     * Affinity is a soft preference, not a new unconditional biome override.
     */
    private Biome selectLandformBiome(double temp, double rain, int height,
                                      Geology geology, boolean coastal,
                                      boolean floodplain, Landform landform, int siteX, int siteZ) {
        Biome bestBiome = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Biome candidate : Biome.values()) {
            BiomeProfile p = biomes.get(candidate);
            if (!p.matches(temp, rain, height, geology, coastal, floodplain)) continue;
            double tempMid = (p.temperature().min() + p.temperature().max()) * 0.5;
            double rainMid = (p.rainfall().min() + p.rainfall().max()) * 0.5;
            // A preference of up to ~5 points can overcome a small priority
            // difference, but not completely override the authored requirements.
            // Balance overlapping climate ranges and preferred terrain. The old
            // priority*10 term caused one high-priority biome to monopolize broad
            // regions, an especially noticeable problem with 35 profiles.
            double tempHalf = Math.max(.12, (p.temperature().max()-p.temperature().min())*.5);
            double rainHalf = Math.max(.12, (p.rainfall().max()-p.rainfall().min())*.5);
            double climateFit = 5.0
                    - 2.3*Math.abs(temp-tempMid)/tempHalf
                    - 2.0*Math.abs(rain-rainMid)/rainHalf;
            // Coarse region-only variation: never per-block random selection.
            double variation = (noise.unit(siteX, candidate.ordinal(), siteZ,
                    REGION ^ 0x35b10eL)-.5)*5.0;
            double score = p.priority()*.045 + climateFit
                    + landformAffinity(candidate, landform)*.85 + variation;
            if (score > bestScore) {
                bestScore = score;
                bestBiome = candidate;
            }
        }
        // Keep the existing catalog fallback for climates with no matching profile.
        return bestBiome != null ? bestBiome
                : biomes.select(temp, rain, height, geology, coastal, floodplain);
    }

    private static double landformAffinity(Biome biome, Landform landform) {
        return switch (biome) {
            case FROSTSPIRE_HIGHLANDS -> landform == Landform.MOUNTAIN_RANGE ? 5.0
                    : landform == Landform.FOOTHILLS ? 2.5 : -4.0;
            case NEEDLEWOOD_TAIGA -> landform == Landform.FOOTHILLS
                    || landform == Landform.ROLLING_UPLAND ? 3.0 : 0.0;
            case SUNMEADOW_PLAINS -> landform == Landform.PLAIN
                    || landform == Landform.BASIN ? 3.5 : -1.0;
            case GREENHEART_FOREST, EMBERLEAF_WOODLAND ->
                    landform == Landform.ROLLING_UPLAND || landform == Landform.PLAIN ? 2.5 : 0.0;
            case MISTFEN_WETLANDS -> landform == Landform.BASIN
                    || landform == Landform.COASTAL_LOWLAND ? 4.0 : -1.5;
            case WHITECLIFF_COAST -> landform == Landform.COASTAL_LOWLAND ? 5.0 : -3.0;
            case REDSCAR_BADLANDS -> landform == Landform.ERODED_PLATEAU ? 4.5 : 0.0;
            case SUNCRACK_DESERT -> landform == Landform.PLAIN
                    || landform == Landform.BASIN ? 2.0 : 0.0;
            case HOLLOWSTONE_KARST -> landform == Landform.ERODED_PLATEAU
                    || landform == Landform.ROLLING_UPLAND ? 3.0 : 0.0;
            case CINDERFALL_VOLCANIC_FIELDS -> landform == Landform.MOUNTAIN_RANGE
                    || landform == Landform.ERODED_PLATEAU ? 3.0 : 0.0;
            case FROSTMOOR_TUNDRA -> landform == Landform.PLAIN
                    || landform == Landform.ROLLING_UPLAND ? 2.5 : 0.0;
            case ELDERROOT_GROVE, BRACKENWOOD_THICKET, MOSSVEIL_RAINWOOD ->
                    landform == Landform.ROLLING_UPLAND || landform == Landform.BASIN ? 3.3 : 0.2;
            case FERNSHADE_RAVINE -> landform == Landform.ERODED_PLATEAU
                    || landform == Landform.FOOTHILLS ? 4.3 : -1.0;
            case SILVERPINE_BARRENS, SNOWMANTLE_WOODS ->
                    landform == Landform.FOOTHILLS || landform == Landform.ROLLING_UPLAND ? 3.0 : -0.7;
            case GOLDGRASS_STEPPE, BRIARFIELD_HEATH,
                    THORNSCRUB_SAVANNA, MISTBLOOM_MEADOW ->
                    landform == Landform.PLAIN || landform == Landform.ROLLING_UPLAND ? 3.2 : -1.0;
            case BLACKWATER_BOG, FLOODROOT_FOREST, REEDWATER_DELTA ->
                    landform == Landform.BASIN || landform == Landform.COASTAL_LOWLAND ? 4.0 : -2.5;
            case STORMBREAK_COAST, SALTWIND_FLATS, SUNWASH_STRAND ->
                    landform == Landform.COASTAL_LOWLAND ? 4.8 : -4.0;
            case SHALECREST_FOOTHILLS -> landform == Landform.FOOTHILLS ? 4.0 : 0.0;
            case GRANITE_CROWN, GLIMMERFROST_GLACIER -> landform == Landform.MOUNTAIN_RANGE ? 4.2 : -2.7;
            case VERMILION_CANYON -> landform == Landform.ERODED_PLATEAU ? 5.0 : -1.0;
            case SUNSCORCH_SALTPAN -> landform == Landform.BASIN ? 5.0 : -2.0;
            case EMBERGLASS_WASTES, ASHENWOOD_RECOVERY ->
                    landform == Landform.ERODED_PLATEAU || landform == Landform.MOUNTAIN_RANGE ? 3.4 : 0.0;
        };
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

    /**
     * Broad noise caverns retain an intact roof; dedicated CaveCarvers supplies
     * the rare entrances. Cave frequency tapers naturally toward the surface.
     */
    public boolean isCave(int x, int y, int z, int surfaceY) {
        int depth = surfaceY - y;
        if (y < settings.minY() + 5 || depth < 14) return false;
        double taper = smoothstep(14.0, 34.0, depth);
        double threshold = settings.caveThreshold() * (0.45 + 0.55 * taper);
        double main = noise.noise3(x / settings.caveScale(), y / (settings.caveScale() * 0.73),
                z / settings.caveScale(), CAVE);
        if (Math.abs(main) >= threshold) return false;
        double breakup = noise.noise3(x / (settings.caveScale() * 0.68),
                y / (settings.caveScale() * 0.59), z / (settings.caveScale() * 0.68), CAVE_BREAKUP);
        // Intersect independent density ridges to form tubes rather than broad
        // near-zero sheets. The original single-ridge test produced concentric
        // cavern shells and unmanageably large overlapping cavities in spectator.
        double secondaryLimit=threshold*(1.5 + 0.65*taper);
        return Math.abs(breakup) < secondaryLimit;

    }

    /** One tree candidate per 8x8 world-cell, independent of chunk-loading order. */
    public Tree treeForCell(int cellX, int cellZ) {
        int x = cellX * TREE_CELL_SIZE + (int)(noise.unit(cellX, 0, cellZ, TREES) * TREE_CELL_SIZE);
        int z = cellZ * TREE_CELL_SIZE + (int)(noise.unit(cellX, 1, cellZ, TREES) * TREE_CELL_SIZE);
        Surface surface = surface(x, z);
        if (surface.waterBody() != WaterBody.LAND) return null;
        BiomeProfile profile = biomes.get(surface.biome());
        int treeSlope=slopeAt(x,z);
        if (treeSlope > profile.maxTreeSlope()) return null;
        // No mature trees on bare alpine cliffs or exposed upper snowfields.
        // Pine survives in sheltered lower slopes, but thins near the treeline.
        if (surface.biome()==Biome.FROSTSPIRE_HIGHLANDS) {
            if (treeSlope>2 || surface.surfaceY()>settings.seaLevel()+94) return null;
        }
        double local = clamp(0.85 + noise.noise2(x / 150.0, z / 150.0, TREES) * 0.5, 0.20, 1.0);
        // Individual patches have clustered forest openings. This is not a
        // rectangular tree grid: each candidate is jittered and seed-derived.
        double cluster = noise.fbm2(x/96.0,z/96.0,3,TREES ^ 0x771aL);
        double forestBoost = profile.treeDensity()>.35 ? 1.24 : 1.0;
        double chance = clamp(profile.treeDensity()*settings.treeDensity()*local
                *forestBoost*(0.88+0.45*cluster),0,0.91);
        if (noise.unit(cellX, 2, cellZ, TREES) >= chance) return null;
        String tree = BiomeProfile.chooseFeature(profile.trees(), noise.unit(cellX, 3, cellZ, TREES));
        if (tree == null) return null;
        TreeType type = TreeType.valueOf(tree.toUpperCase(java.util.Locale.ROOT));
        // Distinct tree species and young/mature/ancient sizes. Heights are
        // measured in blocks and remain stable for a given seed and cell.
        double sizeRoll = noise.unit(cellX, 4, cellZ, TREES);
        int min = switch (type) {
            case OAK, MAPLE -> 9;
            case PINE -> 12;
            case WILLOW -> 8;
            case CYPRESS -> 13;
            case BIRCH -> 9; case SPRUCE -> 12; case ACACIA -> 7;
            case ALDER -> 8; case MANGROVE -> 6; case PALM -> 9;
        };
        int max = switch (type) {
            case OAK, MAPLE -> 16;
            case PINE -> 22;
            case WILLOW -> 15;
            case CYPRESS -> 23;
            case BIRCH -> 17; case SPRUCE -> 25; case ACACIA -> 13;
            case ALDER -> 16; case MANGROVE -> 12; case PALM -> 19;
        };
        int height = min + (int)Math.floor(sizeRoll * (max - min + 1));
        return new Tree(x, z, height, type);
    }

    /** Coherent sea-climate classification; material patches do not decide biomes. */
    public OceanBiome oceanBiomeAt(int x,int z,Surface s) {
        if(s.waterBody()!=WaterBody.OCEAN) return OceanBiome.TEMPERATE;
        int depth=s.waterDepth();
        double regional=noise.fbm2(x/250.0,z/250.0,3,0x0c3aebL);
        double local=noise.fbm2(x/105.0,z/105.0,2,0x0c3aebL+1);
        if(depth>=69 && regional>.05) return OceanBiome.ABYSSAL;
        if(s.geology()==Geology.VOLCANIC && depth>=15 && regional>-.22) return OceanBiome.VOLCANIC;
        if(depth>=34) return OceanBiome.DEEP;
        if(s.temperature()<.22) return OceanBiome.FROZEN;
        if(s.temperature()<.37) return OceanBiome.COLD;
        if(depth<=12 && s.rainfall()>.64 && regional+local*.15>.10) return OceanBiome.ESTUARY;
        if(s.temperature()>.64) {
            if(depth<=16 && regional+local*.15>.14) return OceanBiome.REEF;
            return OceanBiome.WARM;
        }
        if(depth>=9 && depth<=37 && regional>.12) return OceanBiome.KELP;
        return OceanBiome.TEMPERATE;
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
        // A 4x4 feature grid places at most one plant per 16 surface cells.
        // Raise coverage in vegetated biomes without forcing plants into deserts or cliffs.
        double coverage=surface.rainfall()>0.4 ? 1.65 : 1.15;
        double chance = clamp(profile.plantDensity() * settings.plantDensity() * local * coverage,0,0.88);
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
