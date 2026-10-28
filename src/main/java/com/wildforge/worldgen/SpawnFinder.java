package com.wildforge.worldgen;

/** Finds a practical starting location without requiring a handcrafted starting island. */
public final class SpawnFinder {
    public record Spawn(int x, int y, int z, Biome biome) { }

    private SpawnFinder() { }

    /**
     * Scan outward from origin in rings. Prefer gentle ground with nearby wood,
     * never deep ocean, mountain ledges or very steep spawn points.
     * This is deliberately calculated at world creation, not for every chunk.
     */
    public static Spawn find(WorldGenerator generator, int radius) {
        WorldGenSettings settings = generator.settings();
        Spawn fallback = null;
        double bestScore = -Double.MAX_VALUE;
        for (int dist = 0; dist <= radius; dist += 16) {
            for (int x = -dist; x <= dist; x += 16) {
                for (int z = -dist; z <= dist; z += 16) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != dist) continue;
                    WorldGenerator.Surface s = generator.surface(x, z);
                    boolean goodBiome = s.biome() == Biome.PLAINS
                            || s.biome() == Biome.FOREST
                            || s.biome() == Biome.MAPLE_FOREST
                            || s.biome() == Biome.TAIGA;
                    if (!goodBiome || s.surfaceY() < settings.seaLevel() + 5
                            || s.surfaceY() > settings.seaLevel() + 32) continue;

                    int flatness = 0;
                    for (int dx = -7; dx <= 7; dx += 7) {
                        for (int dz = -7; dz <= 7; dz += 7) {
                            flatness = Math.max(flatness,
                                    Math.abs(generator.surface(x + dx, z + dz).surfaceY() - s.surfaceY()));
                        }
                    }
                    if (flatness > 4) continue;

                    boolean hasWood = false;
                    for (int cellX = Math.floorDiv(x - 40, 13); cellX <= Math.floorDiv(x + 40, 13); cellX++) {
                        for (int cellZ = Math.floorDiv(z - 40, 13); cellZ <= Math.floorDiv(z + 40, 13); cellZ++) {
                            WorldGenerator.Tree tree = generator.treeForCell(cellX, cellZ);
                            if (tree != null && Math.abs(tree.x() - x) <= 40 && Math.abs(tree.z() - z) <= 40) {
                                hasWood = true;
                                break;
                            }
                        }
                        if (hasWood) break;
                    }
                    double score = (hasWood ? 15 : 0) - flatness * 4 - dist * 0.015;
                    if (score > bestScore) {
                        bestScore = score;
                        fallback = new Spawn(x, s.surfaceY() + 1, z, s.biome());
                    }
                    // Good, nearby, wooded location: stop scanning early.
                    if (hasWood && dist <= 160 && flatness <= 2) return fallback;
                }
            }
        }
        if (fallback != null) return fallback;
        throw new IllegalStateException("No safe spawn found within " + radius + " blocks");
    }
}
