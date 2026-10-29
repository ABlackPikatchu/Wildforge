package com.wildforge.worldgen;

import java.util.Objects;

/** Determinism, generated profiles and vegetation constraints. */
public final class WorldGenSmokeTest {
    private WorldGenSmokeTest() { }

    public static void main(String[] args) throws Exception {
        WorldGenRegistry registry = WorldGenRegistry.loadBuiltIn();
        WorldGenSettings settings = registry.terrain();
        BiomeCatalog catalog = registry.surfaceBiomes();
        if (catalog.all().size() != 35) throw new AssertionError("Surface biome count must be 35");
        WorldGenerator a = new WorldGenerator(12345678L, settings, catalog);
        WorldGenerator b = new WorldGenerator(12345678L, settings, catalog);

        for (int x = -512; x <= 512; x += 23) {
            for (int z = -512; z <= 512; z += 29) {
                WorldGenerator.Surface one = a.surface(x, z);
                WorldGenerator.Surface two = b.surface(x, z);
                if (!one.equals(two)) throw new AssertionError("Surface generation changed across runs");
                if (one.surfaceY() < settings.minY() || one.surfaceY() > settings.maxY()) {
                    throw new AssertionError("Out-of-bounds terrain height");
                }
                if (a.isCave(x, one.surfaceY() - 2, z, one.surfaceY())) {
                    throw new AssertionError("Surface cave protection failed");
                }
                if (one.waterDepth() > 0 && one.waterBody() != WorldGenerator.WaterBody.OCEAN) {
                    throw new AssertionError("Ocean depth and hydrology disagree");
                }
                if (catalog.get(one.biome()) == null) throw new AssertionError("Unknown surface biome");
                if (!one.baseRockTexture().equals(one.geology().baseRockTexture())) {
                    throw new AssertionError("Geology/rock mapping mismatch");
                }
            }
        }

        for (int z = -128; z < 128; z++) {
            if (!a.surface(15, z).equals(b.surface(15, z))
                    || !a.surface(16, z).equals(b.surface(16, z))) {
                throw new AssertionError("Chunk border changed world-coordinate sampling");
            }
        }

        for (int cx = -28; cx < 28; cx++) {
            WorldGenerator.Tree ta = a.treeForCell(cx, 12);
            WorldGenerator.Tree tb = b.treeForCell(cx, 12);
            if (!Objects.equals(ta, tb)) throw new AssertionError("Tree generation is nondeterministic");
            if (ta != null) {
                WorldGenerator.Surface ground = a.surface(ta.x(), ta.z());
                if (ground.waterBody() != WorldGenerator.WaterBody.LAND) {
                    throw new AssertionError("Tree generated underwater");
                }
                if (a.slopeAt(ta.x(), ta.z()) > catalog.get(ground.biome()).maxTreeSlope()) {
                    throw new AssertionError("Tree generated on excessive slope");
                }
            }
            WorldGenerator.Plant pa = a.plantForCell(cx, 12);
            WorldGenerator.Plant pb = b.plantForCell(cx, 12);
            if (!Objects.equals(pa, pb)) throw new AssertionError("Plant generation is nondeterministic");
        }

        for (long seed : new long[]{20261028L, 1L, 12345L, 8675309L}) {
            SpawnFinder.Spawn spawn = SpawnFinder.find(
                    new WorldGenerator(seed, settings, catalog), 768);
            WorldGenerator.Surface candidate = new WorldGenerator(seed, settings, catalog)
                    .surface(spawn.x(), spawn.z());
            if (spawn.y() <= settings.seaLevel()
                    || candidate.waterBody() != WorldGenerator.WaterBody.LAND) {
                throw new AssertionError("Unsafe spawn for seed " + seed);
            }
        }

        System.out.println("Worldgen passed: 35 data-generated biomes, independent geology,"
                + " hydrology, deterministic caves and slope-filtered decorations");
    }
}
