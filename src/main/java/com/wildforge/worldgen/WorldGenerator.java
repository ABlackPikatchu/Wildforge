package com.wildforge.worldgen;

/**
 * Seeded WORLD-COORDINATE sampler for future streamed chunks. This class does
 * not yet replace TerrainMesh's 64x64 test world. Every output depends only on
 * the seed, immutable settings, and absolute x/y/z coordinates.
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

    public record Surface(int surfaceY, Biome biome, double temperature,
                          double moisture, double continentalness, boolean river) {}
    public enum TreeType { OAK, MAPLE, PINE }
    public record Tree(int x, int z, int trunkHeight, TreeType type) {}
    public record Plant(int x, int z, String species, boolean tall) {}

    private final long seed;
    private final WorldGenSettings settings;
    private final SeededNoise noise;

    public WorldGenerator(long seed, WorldGenSettings settings) {
        this.seed = seed;
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        noise = new SeededNoise(seed);
    }

    public long seed() { return seed; }
    public WorldGenSettings settings() { return settings; }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double smoothstep(double a, double b, double x) {
        double t = clamp((x - a) / (b - a), 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    public Surface surface(int x, int z) {
        double continent = noise.fbm2(x / settings.continentScale(), z / settings.continentScale(), 5, CONTINENT);
        double hill = noise.fbm2(x / settings.hillsScale(), z / settings.hillsScale(), 4, HILLS);
        double ridge = 1.0 - Math.abs(noise.fbm2(x / settings.mountainsScale(),
                z / settings.mountainsScale(), 3, MOUNTAIN));
        double mountainMask = smoothstep(settings.mountainThreshold(), settings.mountainThreshold() + 0.30, continent);
        double mountains = mountainMask * Math.pow(ridge, 3) * 95.0;
        double detail = noise.fbm2(x / settings.detailScale(), z / settings.detailScale(), 3, DETAIL) * 3.5;

        // Ocean basins, broad lowlands, gently rolling hills and occasional tall ranges.
        double land = settings.seaLevel() + 11.0 + continent * 85.0 + hill * 13.0 + mountains + detail;
        if (continent < settings.oceanThreshold()) {
            land -= (settings.oceanThreshold() - continent) * 28.0;
        }

        boolean river = false;
        if (continent > settings.oceanThreshold() + 0.07 && land > settings.seaLevel() + 2) {
            double field = Math.abs(noise.fbm2(x / settings.riverScale(), z / settings.riverScale(), 3, RIVER));
            double width = smoothstep(0.052, 0.014, field); // high near the meandering centerline
            if (width > 0) {
                land = Math.min(land, land - width * (8.0 + Math.min(20.0, land - settings.seaLevel()) * 0.9));
                river = width > 0.45 && land <= settings.seaLevel() + 3;
            }
        }

        int y = (int) Math.round(clamp(land, settings.minY() + 5, settings.maxY() - 6));
        double temperature = clamp(0.5 + noise.fbm2(x / settings.climateScale(), z / settings.climateScale(), 3, TEMP)
                * 0.48 - Math.max(0, y - settings.seaLevel()) * 0.0035 - (z / 10000.0) * 0.14, 0, 1);
        double moisture = clamp(0.5 + noise.fbm2((x + 8000) / settings.climateScale(),
                (z - 8000) / settings.climateScale(), 3, MOISTURE) * 0.60, 0, 1);
        Biome biome = biomeAt(y, temperature, moisture);
        return new Surface(y, biome, temperature, moisture, continent, river);
    }

    private Biome biomeAt(int y, double temperature, double moisture) {
        if (y < settings.seaLevel() - 18) return Biome.DEEP_OCEAN;
        if (y < settings.seaLevel()) return Biome.OCEAN;
        if (y <= settings.seaLevel() + 3) return Biome.BEACH;
        if (y > settings.seaLevel() + 92 || (y > settings.seaLevel() + 55 && temperature < 0.32)) {
            return Biome.SNOWY_PEAKS;
        }
        if (y > settings.seaLevel() + 58) return Biome.MOUNTAINS;
        if (y > settings.seaLevel() + 31) return Biome.HIGHLANDS;
        if (temperature < 0.30) return Biome.TAIGA;
        if (temperature > 0.62 && moisture < 0.45) return Biome.DESERT;
        if (moisture > 0.68 && y <= settings.seaLevel() + 13) return Biome.WETLAND;
        if (temperature < 0.48 && moisture > 0.53) return Biome.MAPLE_FOREST;
        if (moisture > 0.54) return Biome.FOREST;
        return Biome.PLAINS;
    }

    /** Cheap cave-carving predicate; future chunks apply it after stone filling. */
    public boolean isCave(int x, int y, int z, int surfaceY) {
        if (y < settings.minY() + 5 || y > surfaceY - 6) return false;
        double main = noise.noise3(x / settings.caveScale(), y / (settings.caveScale() * 0.73),
                z / settings.caveScale(), CAVE);
        double breakup = noise.noise3(x / (settings.caveScale() * 0.65),
                y / (settings.caveScale() * 0.55), z / (settings.caveScale() * 0.65), CAVE_BREAKUP);
        return Math.abs(main) < settings.caveThreshold() && breakup > -0.27;
    }

    /**
     * One candidate per 13x13-cell. Check neighboring cells when placing a tree
     * so crowns crossing chunk boundaries are not cut off.
     */
    public Tree treeForCell(int cellX, int cellZ) {
        int x = cellX * 13 + (int) (noise.unit(cellX, 0, cellZ, TREES) * 13);
        int z = cellZ * 13 + (int) (noise.unit(cellX, 1, cellZ, TREES) * 13);
        Surface surface = surface(x, z);
        double density = switch (surface.biome()) {
            case FOREST -> 0.77;
            case MAPLE_FOREST -> 0.83;
            case TAIGA -> 0.79;
            case WETLAND -> 0.28;
            case PLAINS -> 0.09;
            case HIGHLANDS -> 0.13;
            default -> 0.0;
        };
        if (noise.unit(cellX, 2, cellZ, TREES) >= density * settings.treeDensity()) return null;
        TreeType type = switch (surface.biome()) {
            case TAIGA, HIGHLANDS -> TreeType.PINE;
            case MAPLE_FOREST -> TreeType.MAPLE;
            case FOREST -> noise.unit(cellX, 3, cellZ, TREES) > 0.72 ? TreeType.MAPLE : TreeType.OAK;
            default -> TreeType.OAK;
        };
        int height = 5 + (int) (noise.unit(cellX, 4, cellZ, TREES) * 4);
        return new Tree(x, z, height, type);
    }

    /** Candidate for flowers/grass in a 5x5 feature cell. */
    public Plant plantForCell(int cellX, int cellZ) {
        int x = cellX * 5 + (int) (noise.unit(cellX, 0, cellZ, PLANTS) * 5);
        int z = cellZ * 5 + (int) (noise.unit(cellX, 1, cellZ, PLANTS) * 5);
        Surface s = surface(x, z);
        boolean allowed = switch (s.biome()) {
            case PLAINS, FOREST, MAPLE_FOREST, TAIGA, WETLAND, HIGHLANDS -> true;
            default -> false;
        };
        if (!allowed || noise.unit(cellX, 2, cellZ, PLANTS) > settings.plantDensity()) return null;
        String[] flowers = switch (s.biome()) {
            case PLAINS -> new String[] {"small_grass", "small_grass", "tall_grass",
                    "flower_daisy", "flower_poppy", "flower_cornflower", "flower_sunflower",
                    "flower_lavender", "tall_flower_gladiolus"};
            case FOREST -> new String[] {"small_grass", "tall_grass", "flower_bluebell",
                    "flower_foxglove", "flower_poppy", "tall_flower_lilac", "tall_flower_hollyhock"};
            case MAPLE_FOREST -> new String[] {"small_grass", "tall_grass", "flower_bluebell",
                    "flower_foxglove", "tall_flower_delphinium", "tall_flower_lupine"};
            case TAIGA -> new String[] {"small_grass", "small_grass", "flower_snowdrop",
                    "flower_bluebell", "tall_grass"};
            case WETLAND -> new String[] {"tall_grass", "tall_grass", "flower_orchid",
                    "tall_flower_delphinium", "tall_flower_lilac"};
            case HIGHLANDS -> new String[] {"small_grass", "flower_goldenrod", "flower_snowdrop",
                    "tall_flower_lupine", "tall_flower_hollyhock"};
            default -> new String[] {"small_grass"};
        };
        int pick = (int) (noise.unit(cellX, 3, cellZ, PLANTS) * flowers.length);
        String species = flowers[Math.min(pick, flowers.length - 1)];
        boolean tall = species.startsWith("tall_");
        return new Plant(x, z, species, tall);
    }
}
