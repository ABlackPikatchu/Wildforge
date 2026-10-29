package com.wildforge.worldgen;

/** Deterministic, bounded search for a safe *actual* surface spawn in a generated world. */
public final class SpawnFinder {
    /** platform is only used for a pathological world with no suitable land nearby. */
    public record Spawn(int x, int y, int z, Biome biome, boolean platform) {
        public Spawn(int x, int y, int z, Biome biome) { this(x, y, z, biome, false); }
    }

    private static final int MAX_SEARCH_RADIUS = 8192;

    private SpawnFinder() { }

    /**
     * First look for a good survival start near the origin, then widen the search.
     * Worldgen can make continuous ocean/climate regions larger than 512 blocks;
     * biome preference or a particular sea-level band must never make world
     * creation fail. Calls are deterministic and do not generate any chunks.
     *
     * @param preferredRadius radius of the detailed near-origin scan, not an
     *                        absolute maximum for dry-land search.
     */
    public static Spawn find(WorldGenerator generator, int preferredRadius) {
        if (generator == null) throw new IllegalArgumentException("Missing world generator");
        if (preferredRadius < 0) throw new IllegalArgumentException("Negative spawn radius");
        int inner = Math.min(2048, Math.max(32, ((preferredRadius + 31) / 32) * 32));

        // Favor a nearby, walkable location in any nonaquatic biome. We score
        // the entire preferred area rather than accidentally preferring the
        // first location visited on a ring.
        Candidate best = null;
        for (int r = 0; r <= inner; r += 32) {
            for (int x = -r; x <= r; x += 32) {
                for (int z = -r; z <= r; z += 32) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    Candidate c = candidate(generator, x, z, r);
                    if (c != null && (best == null || c.score() > best.score())) best = c;
                }
            }
        }
        if (best != null) return best.spawn();

        // Large oceans are valid geography. Search outward on a wider grid,
        // stopping at the first ring with walkable land; then refine locally.
        // 64-block sampling finds small coastal islands without checking every
        // column across many square kilometers.
        for (int step : new int[]{64, 128}) {
            int start = step == 64 ? ((inner / 64) + 1) * 64 : 2176;
            int end = step == 64 ? 2048 : MAX_SEARCH_RADIUS;
            for (int r = start; r <= end; r += step) {
                Candidate ringBest = null;
                for (int x = -r; x <= r; x += step) {
                    for (int z = -r; z <= r; z += step) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                        Candidate c = candidate(generator, x, z, r);
                        if (c != null && (ringBest == null || c.score() > ringBest.score())) ringBest = c;
                    }
                }
                if (ringBest != null) {
                    best = ringBest;
                    int bx = best.spawn().x(), bz = best.spawn().z();
                    for (int dx = -step / 2; dx <= step / 2; dx += 16) {
                        for (int dz = -step / 2; dz <= step / 2; dz += 16) {
                            int x = bx + dx, z = bz + dz;
                            Candidate c = candidate(generator, x, z, Math.max(Math.abs(x), Math.abs(z)));
                            if (c != null && c.score() > best.score()) best = c;
                        }
                    }
                    return best.spawn();
                }
            }
        }

        // Rare fallback: a tiny persistent survival platform will be added by
        // TerrainMesh after its spawn column exists. Never strand a player
        // underwater or crash world creation because all sampled ground is sea.
        int sea = generator.settings().seaLevel();
        return new Spawn(8, Math.min(generator.settings().maxY() - 4, sea + 3), 8,
                generator.surface(8, 8).biome(), true);
    }

    private record Candidate(Spawn spawn, double score) { }

    private static Candidate candidate(WorldGenerator generator, int x, int z, int radius) {
        // Evaluate authoritative ground and hydrology before considering a location.
        WorldGenerator.Surface surface = generator.surface(x, z);
        WorldGenSettings settings = generator.settings();
        int y = surface.surfaceY();
        if (surface.waterBody() != WorldGenerator.WaterBody.LAND
                || y < settings.seaLevel() + 2
                || y < settings.minY() + 3 || y > settings.maxY() - 5) return null;

        // Check surrounding ground and water, not merely a one-column slope.
        int grade = 0;
        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -4; dz <= 4; dz += 4) {
                if (dx == 0 && dz == 0) continue;
                WorldGenerator.Surface neighbor = generator.surface(x + dx, z + dz);
                if (neighbor.waterBody() != WorldGenerator.WaterBody.LAND) return null;
                grade = Math.max(grade, Math.abs(neighbor.surfaceY() - y));
            }
        }
        if (grade > 6) return null;
        // Density-based overhangs may place stone above the approximate heightmap.
        // Verify the actual generated floor and headroom before accepting spawn.
        int slope=generator.slopeAt(x,z);
        if(!generator.isSolidTerrain(x,y,z,y,slope)
                || generator.isSolidTerrain(x,y+1,z,y,slope)
                || generator.isSolidTerrain(x,y+2,z,y,slope)) return null;

        // Check only cells capable of placing a trunk at the spawn, not the
        // entire 64x64 area: treeForCell samples hydrology and local slopes.
        int cellSize = WorldGenerator.TREE_CELL_SIZE;
        for (int tx = Math.floorDiv(x - 3, cellSize); tx <= Math.floorDiv(x + 3, cellSize); tx++) {
            for (int tz = Math.floorDiv(z - 3, cellSize); tz <= Math.floorDiv(z + 3, cellSize); tz++) {
                WorldGenerator.Tree tree = generator.treeForCell(tx, tz);
                if (tree != null && Math.abs(tree.x() - x) <= 3
                        && Math.abs(tree.z() - z) <= 3) return null;
            }
        }
        // This is a biome-level wood availability *preference*, not a claim
        // that an individual tree definitely generated within 32 blocks.
        BiomeProfile profile = generator.biomes().get(surface.biome());
        boolean hasWood = profile.treeDensity() >= .35 && !profile.trees().isEmpty();
        boolean preferred = switch (surface.biome()) {
            case SUNMEADOW_PLAINS, GREENHEART_FOREST, EMBERLEAF_WOODLAND,
                 NEEDLEWOOD_TAIGA, MISTBLOOM_MEADOW, ELDERROOT_GROVE -> true;
            default -> false;
        };
        // Distance wins over distant biome preferences; all eligible land is
        // safe, even when the climatic region is desert, steppe or tundra.
        double score = (preferred ? 7 : 0) + (hasWood ? 12 : 0)
                - grade * 3 - radius * 0.03;
        return new Candidate(new Spawn(x, y + 1, z, surface.biome()), score);
    }
}
