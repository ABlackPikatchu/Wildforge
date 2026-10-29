package com.wildforge.worldgen;

import java.util.Objects;

/** Minecraft-inspired continuous climate/terrain sampler for streamed 512-block worlds.
 * Terrain and biome identity share climate signals but geology remains independent.
 * Oceans, rivers, cave systems, and underground biomes are separate passes.
 */
public final class WorldGenerator {
    /** New-world generation format; increment when terrain rules change incompatibly. */
    public static final int CURRENT_REVISION = 9;
    private static final long HILLS = 0x20beefL;
    private static final long DETAIL = 0x40f00dL;
    private static final long TREES = 0xa0abcdL;
    private static final long PLANTS = 0xb0abcdL;
    private static final long GEOLOGY_A = 0xc0abcdL;
    private static final long GEOLOGY_B = 0xd0abcdL;
    private static final long VOLCANIC = 0xe0abcdL;
    private static final long UPLANDS = 0x2ab13f1L;

    public static final int TREE_CELL_SIZE = 7;
    public static final int PLANT_CELL_SIZE = 4;

    public enum WaterBody { LAND, OCEAN, RIVER, LAKE }

    public record Surface(int surfaceY, Biome biome, double temperature,
                          double rainfall, double continentalness, boolean river,
                          Geology geology, WaterBody waterBody, int waterDepth,
                          boolean coastal, String topTexture, String fillerTexture,
                          String baseRockTexture, int waterSurfaceY) {
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
    private final ClimateField climateField;
    private final CaveShapeSampler caveShapes;
    private final TerrainDensityGenerator terrainDensity;
    private final TreeSpeciesCatalog treeSpecies;
    private final FeaturePlacementEngine placements;
    private final MultiNoiseBiomeSource multiNoise;
    private final int generationRevision;

    public WorldGenerator(long seed, WorldGenSettings settings) {
        this(seed, settings, BiomeCatalog.loadBuiltInUnchecked());
    }

    public WorldGenerator(long seed, WorldGenSettings settings, BiomeCatalog biomes) {
        this(seed, settings, biomes, UndergroundBiomeCatalog.loadBuiltInUnchecked(),
                loadUndergroundSettings(), CURRENT_REVISION);
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
        this(seed, settings, biomes, undergroundBiomes, undergroundSettings, CURRENT_REVISION);
    }

    public WorldGenerator(long seed, WorldGenSettings settings, int revision) {
        this(seed, settings, BiomeCatalog.loadBuiltInUnchecked(),
                UndergroundBiomeCatalog.loadBuiltInUnchecked(), loadUndergroundSettings(), revision);
    }

    public WorldGenerator(long seed, WorldGenSettings settings, BiomeCatalog biomes,
                          UndergroundBiomeCatalog undergroundBiomes,
                          UndergroundGenSettings undergroundSettings, int revision) {
        if (revision != CURRENT_REVISION) throw new IllegalArgumentException("Unsupported worldgen revision: " + revision);
        generationRevision = revision;
        climateField = new ClimateField(seed, settings);
        caveShapes = new CaveShapeSampler(seed, settings);
        terrainDensity = new TerrainDensityGenerator(seed, settings.seaLevel());
        treeSpecies = TreeSpeciesCatalog.loadBuiltInUnchecked();
        placements = FeaturePlacementEngine.loadBuiltInUnchecked();
        multiNoise = new MultiNoiseBiomeSource(biomes, seed);
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
    public int generationRevision() { return generationRevision; }
    public ClimateField.Sample climateAt(int x, int z) { return climateField.sample(x,z); }
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
        ClimateField.Sample c=climateField.sample(x,z);
        return new Geography(c.continentalness(),c.erosion(),c.peaksValleys(),
                noise.fbm2(x/1050.0,z/1050.0,3,UPLANDS),
                noise.fbm2(x/settings.detailScale(),z/settings.detailScale(),2,DETAIL),
                hydrology.sample(x,z,baseTerrainHeight(x,z)).drainage());
    }

    /** Large-scale geographic character, independent of surface block palette. */
    public enum Landform { OCEAN_SHELF, COASTAL_LOWLAND, PLAIN, ROLLING_UPLAND,
                           FOOTHILLS, MOUNTAIN_RANGE, ERODED_PLATEAU, BASIN }

    /** Deterministic regional classification; never depends on chunk-load order. */
    public Landform landformAt(int x, int z) {
        return climateLandform(climateField.sample(x,z),
                noise.fbm2(x/1050.0,z/1050.0,3,UPLANDS));
    }

    /** Same climatic landform test used by new terrain and biome selection;
     * no hydrology trace or duplicate erosion pass per surface lookup. */
    private Landform climateLandform(ClimateField.Sample c,double uplandField) {
        double continental=c.continentalness();
        double coast=smoothstep(settings.oceanThreshold()-.13,settings.oceanThreshold()+.18,continental);
        if(continental<settings.oceanThreshold()-.075)return Landform.OCEAN_SHELF;
        if(coast<.70)return Landform.COASTAL_LOWLAND;
        double uplift=smoothstep(-.20,.47,uplandField+continental*.35);
        double rugged=1.0-smoothstep(-.32,.45,c.erosion());
        if(uplift>.60 && rugged>.48 && c.peaksValleys()>.60)return Landform.MOUNTAIN_RANGE;
        if(uplift>.49 && rugged>.46)return Landform.FOOTHILLS;
        if(uplift>.57 && rugged<.40)return Landform.ERODED_PLATEAU;
        if(c.erosion()>.24 && uplandField<-.05)return Landform.BASIN;
        return uplift>.33?Landform.ROLLING_UPLAND:Landform.PLAIN;
    }

    private double terrainHeight(int x, int z) {
        double base=baseTerrainHeight(x,z);
        return hydrology.sample(x,z,base).groundY();
    }

    private double baseTerrainHeight(int x, int z) {
        ClimateField.Sample c = climateField.sample(x,z);
        double upland=noise.fbm2(x/1050.0,z/1050.0,3,UPLANDS);
        double hills=noise.fbm2(x/settings.hillsScale(),z/settings.hillsScale(),3,HILLS);
        double detail=noise.fbm2(x/settings.detailScale(),z/settings.detailScale(),2,DETAIL);
        return TerrainShaper.height(c,settings,hills,detail,upland);
    }
    private double continentalness(int x,int z){return climateField.sample(x,z).continentalness();}

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
        ClimateField.Sample climate=climateField.sample(x,z);
        double temperature=clamp(climate.temperature()-Math.max(0,y-settings.seaLevel())*.003,0,1);
        double rainfall=climate.humidity();
        int depth=Math.max(0,settings.seaLevel()-y);
        WaterBody water=(depth>0)
                ?WaterBody.OCEAN:drainage.wet() ?
                drainage.kind()==HydrologyNetwork.Kind.LAKE?WaterBody.LAKE:WaterBody.RIVER:WaterBody.LAND;
        boolean river=water==WaterBody.RIVER;
        int waterY=water==WaterBody.OCEAN?settings.seaLevel():
                water==WaterBody.LAND?y:drainage.waterY();
        boolean coastal = continent <= settings.oceanThreshold() + 0.11
                && y >= settings.seaLevel() - 10 && y <= settings.seaLevel() + 23;
        // Modern wetlands should form along real waterways AND in broad, low,
        // humid basins. Minecraft's swamps are ecosystems, not only river beds.
        // This floodplain rule is shared by all newly generated worlds.
        boolean floodplain = river || water==WaterBody.LAKE ||
                (drainage.drainage()>0.4 && y<=settings.seaLevel()+45);
        if(!floodplain && y<=settings.seaLevel()+38 && rainfall>=.58) {
            double basinWetness=noise.fbm2(x/275.0,z/275.0,2,0x71f10dL);
            floodplain=(drainage.drainage()>.08 || basinWetness>.025)
                    && (climate.erosion()>-.18 || coastal);
        }
        Geology geology = geologyAt(x, z);
        Biome biome=multiNoise.select(x,z,climate,temperature,y-settings.seaLevel(),
                geology,coastal,floodplain,climateLandform(climate,
                    noise.fbm2(x/1050.0,z/1050.0,3,UPLANDS)));
        BiomeProfile profile = biomes.get(biome);
        return new Surface(y, biome, temperature, rainfall, continent, river,
                geology, water, depth, coastal,
                profile.topTexture(), profile.fillerTexture(), geology.baseRockTexture(),waterY);
    }

    static double landformAffinity(Biome biome, Landform landform) {
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

    /** New density router: a world-coordinate 3D cliff function on the climate height. */
    public double terrainDensity(int x,int y,int z,int surfaceY) {
        return terrainDensity(x,y,z,surfaceY,slopeAt(x,z));
    }
    public double terrainDensity(int x,int y,int z,int surfaceY,int slope) {
        return terrainDensity.sample(x,y,z,surfaceY,slope);
    }

    public boolean isSolidTerrain(int x, int y, int z, int surfaceY, int slope) {
        if (y <= settings.minY() + 4) return true;
        return terrainDensity(x, y, z, surfaceY, slope) > 0.0;
    }

    /** Kept for previews and existing callers. */
    public boolean isSolidTerrain(int x, int y, int z, int surfaceY) {
        return isSolidTerrain(x, y, z, surfaceY, slopeAt(x, z));
    }

    /** Noise cave geometry is independent of the surface biome and biome decorator. */
    public boolean isCave(int x,int y,int z,int surfaceY) {
        return caveShapes.carved(x,y,z,surfaceY);
    }

    /** One tree candidate per 8x8 world-cell, independent of chunk-loading order. */
    public Tree treeForCell(int cellX, int cellZ) {
        int x = cellX * TREE_CELL_SIZE + (int)(noise.unit(cellX, 0, cellZ, TREES) * TREE_CELL_SIZE);
        int z = cellZ * TREE_CELL_SIZE + (int)(noise.unit(cellX, 1, cellZ, TREES) * TREE_CELL_SIZE);
        Surface surface = surface(x, z);
        BiomeProfile profile = biomes.get(surface.biome());
        int treeSlope=slopeAt(x,z);
        if (!placements.validTree(profile,surface,treeSlope,settings.seaLevel()))return null;
        double local = clamp(0.85 + noise.noise2(x / 150.0, z / 150.0, TREES) * 0.5, 0.20, 1.0);
        // Individual patches have clustered forest openings. This is not a
        // rectangular tree grid: each candidate is jittered and seed-derived.
        double cluster = noise.fbm2(x/placements.treePatchScale(),z/placements.treePatchScale(),3,TREES ^ 0x771aL);
        double chance=placements.treeChance(profile,settings.treeDensity(),local,cluster);
        if (noise.unit(cellX, 2, cellZ, TREES) >= chance) return null;
        String tree = BiomeProfile.chooseFeature(profile.trees(), noise.unit(cellX, 3, cellZ, TREES));
        if (tree == null) return null;
        TreeType type = TreeType.valueOf(tree.toUpperCase(java.util.Locale.ROOT));
        // Distinct tree species and young/mature/ancient sizes. Heights are
        // measured in blocks and remain stable for a given seed and cell.
        double sizeRoll = noise.unit(cellX, 4, cellZ, TREES);
        int height = treeSpecies.get(type).choose(sizeRoll);
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
        BiomeProfile profile = biomes.get(surface.biome());
        int slope=slopeAt(x,z);
        if(!placements.validPlant(profile,surface,slope))return null;
        double local = clamp(0.85 + noise.noise2(x / placements.plantPatchScale(), z / placements.plantPatchScale(), PLANTS) * .5, .20,1.0);
        // A 4x4 feature grid places at most one plant per 16 surface cells.
        // Riparian reeds only belong near rivers/lakes, on their actual dry
        // banks. Use the same seeded hydrology signal as the channel geometry.
        // Stable world-coordinate samples prevent chunk-boundary clusters.
        double bank=hydrology.sample(x,z,baseTerrainHeight(x,z)).drainage();
        if(bank>0.28 && slope<=2 &&
                noise.unit(cellX,5,cellZ,PLANTS^0x2C4L)<placements.bankChance())
            return new Plant(x,z,noise.unit(cellX,6,cellZ,PLANTS^0x531L)<0.5
                    ? "cattails" : "marsh_reeds",true);
        // Raise coverage in vegetated biomes without forcing plants into deserts or cliffs.
        double chance=placements.plantChance(profile,settings.plantDensity(),local,surface.rainfall());
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
