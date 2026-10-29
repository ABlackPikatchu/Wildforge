package com.wildforge.worldgen;

/** Regression for an ocean-origin seed that previously crashed on new-world creation. */
public final class SpawnFinderPhase4SmokeTest {
    private static final long OCEAN_ORIGIN_SEED = -2953407489294701774L;

    public static void main(String[] args) throws Exception {
        WorldGenRegistry registry = WorldGenRegistry.loadBuiltIn();
        WorldGenerator world = registry.generator(OCEAN_ORIGIN_SEED);
        SpawnFinder.Spawn spawn = SpawnFinder.find(world, 512);
        if (spawn.platform()) throw new AssertionError("Expected natural land for regression seed");
        if (Math.max(Math.abs(spawn.x()), Math.abs(spawn.z())) <= 512)
            throw new AssertionError("Regression seed was expected to have an ocean-origin spawn");
        if (Math.max(Math.abs(spawn.x()), Math.abs(spawn.z())) > 8192)
            throw new AssertionError("Spawn outside search budget: " + spawn);
        WorldGenerator.Surface ground = world.surface(spawn.x(), spawn.z());
        if (ground.waterBody() != WorldGenerator.WaterBody.LAND)
            throw new AssertionError("Spawn in water: " + spawn);
        if (spawn.y() != ground.surfaceY() + 1)
            throw new AssertionError("Spawn Y differs from generated terrain: " + spawn);
        SpawnFinder.Spawn again = SpawnFinder.find(registry.generator(OCEAN_ORIGIN_SEED), 512);
        if (!spawn.equals(again)) throw new AssertionError("Spawn changed for same seed");
        // A near-origin land seed remains acceptable and uses the same rules.
        SpawnFinder.Spawn ordinary = SpawnFinder.find(registry.generator(20261029L), 512);
        if (!ordinary.platform() && registry.generator(20261029L).surface(
                ordinary.x(), ordinary.z()).waterBody() != WorldGenerator.WaterBody.LAND)
            throw new AssertionError("Ordinary seed spawned in water");
        System.out.println("Phase 4 ocean-origin spawn regression passed: " + spawn);
    }
}
