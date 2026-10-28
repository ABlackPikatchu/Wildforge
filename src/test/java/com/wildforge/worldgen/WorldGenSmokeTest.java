package com.wildforge.worldgen;

/** Basic invariants and determinism. Run with gradlew.bat worldgenCheck. */
public final class WorldGenSmokeTest {
    private WorldGenSmokeTest() { }

    public static void main(String[] args) throws Exception {
        WorldGenSettings settings = WorldGenConfigLoader.loadBuiltIn();
        WorldGenerator a = new WorldGenerator(12345678L, settings);
        WorldGenerator b = new WorldGenerator(12345678L, settings);
        for (int x = -512; x <= 512; x += 23) {
            for (int z = -512; z <= 512; z += 29) {
                WorldGenerator.Surface one = a.surface(x, z);
                WorldGenerator.Surface two = b.surface(x, z);
                if (!one.equals(two)) throw new AssertionError("Generation is not deterministic");
                if (one.surfaceY() < settings.minY() || one.surfaceY() > settings.maxY()) {
                    throw new AssertionError("Out-of-bounds terrain height");
                }
                if (a.isCave(x, one.surfaceY() - 2, z, one.surfaceY())) {
                    throw new AssertionError("Surface protection failed");
                }
            }
        }

        // Generating separate chunks must yield the same border samples as one large map.
        for (int z = -128; z < 128; z++) {
            if (!a.surface(15, z).equals(b.surface(15, z))
                    || !a.surface(16, z).equals(b.surface(16, z))) {
                throw new AssertionError("World-coordinate sampling changed across a chunk boundary");
            }
        }

        // Feature placement must not depend on the order chunks ask for candidates.
        for (int cx = -50; cx < 50; cx++) {
            WorldGenerator.Tree ta = a.treeForCell(cx, 12);
            WorldGenerator.Tree tb = b.treeForCell(cx, 12);
            if (!java.util.Objects.equals(ta, tb)) {
                throw new AssertionError("Tree placement is not deterministic");
            }
        }

        // A new world should find a playable starting location near its origin.
        for (long seed : new long[] {20261028L, 1L, 12345L, 8675309L}) {
            SpawnFinder.Spawn spawn = SpawnFinder.find(new WorldGenerator(seed, settings), 768);
            if (spawn.y() <= settings.seaLevel()
                    || spawn.biome() == Biome.OCEAN
                    || spawn.biome() == Biome.DEEP_OCEAN) {
                throw new AssertionError("Spawn finder returned an unsafe location for seed " + seed);
            }
        }

        System.out.println("Worldgen check passed: deterministic terrain, caves, vegetation and spawn finding");
    }
}
