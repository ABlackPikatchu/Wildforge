package com.wildforge.worldgen;

import java.util.EnumSet;
import java.util.Objects;

/** Standalone validation of generated profiles and deterministic cave classification. */
public final class UndergroundWorldGenSmokeTest {
    private UndergroundWorldGenSmokeTest() { }

    public static void main(String[] args) throws Exception {
        WorldGenRegistry registry = WorldGenRegistry.loadBuiltIn();
        if (registry.surfaceBiomes().all().size() != 35
                || registry.undergroundBiomes().all().size() != 12) {
            throw new AssertionError("Expected exactly 35 surface + 12 underground biomes");
        }

        int newTextures = 0;
        EnumSet<UndergroundBiomeProfile.Rule> rules = EnumSet.noneOf(UndergroundBiomeProfile.Rule.class);
        for (UndergroundBiomeProfile profile : registry.undergroundBiomes().all().values()) {
            newTextures += profile.newTextures().size();
            if (!rules.add(profile.selectionRule())) throw new AssertionError("Duplicate selection rule");
            if (profile.transitions().isEmpty()) throw new AssertionError("Missing cave transitions");
        }
        if (newTextures != 33 || rules.size() != 12) {
            throw new AssertionError("Unexpected underground texture/selection-rule coverage");
        }

        EnumSet<UndergroundBiome> observed = EnumSet.noneOf(UndergroundBiome.class);
        int seenCaves = 0;
        int classified = 0;
        for (long seed : new long[]{1L, 20261028L, 8675309L}) {
            WorldGenerator first = registry.generator(seed);
            WorldGenerator second = registry.generator(seed);
            for (int x = -700; x <= 700; x += 41) {
                for (int z = -700; z <= 700; z += 43) {
                    WorldGenerator.Surface surface = first.surface(x, z);
                    for (double fraction : new double[]{.12,.32,.56,.79}) {
                        int y = surface.surfaceY()
                                - (int) Math.round(fraction * (surface.surfaceY() - registry.terrain().minY()));
                        if (y < registry.terrain().minY() + 6) continue;
                        boolean carve = first.isCave(x, y, z, surface.surfaceY());
                        UndergroundBiome biome = first.caveBiomeAt(x, y, z);
                        UndergroundBiome again = second.caveBiomeAt(x, y, z);
                        if (!Objects.equals(biome, again)) {
                            throw new AssertionError("Non-deterministic cave biome at " + x + "," + y + "," + z);
                        }
                        if (biome != null) {
                            if (!carve) throw new AssertionError("Cave biome assigned to solid rock");
                            classified++;
                            observed.add(biome);
                            UndergroundBiomeSampler.Context ctx = first.caveContextAt(x, y, z);
                            UndergroundBiomeProfile profile = registry.undergroundBiomes().get(biome);
                            if (!profile.supportsDepth(ctx.depthFraction())
                                    || !first.caveSampler().eligible(profile, ctx)) {
                                throw new AssertionError("Cave " + biome + " violates eligibility criteria");
                            }
                            if (biome == UndergroundBiome.HOLLOWSTONE_VAULTS
                                    && ctx.geology() != Geology.LIMESTONE) {
                                throw new AssertionError("Non-limestone Hollowstone cave");
                            }
                            if (biome == UndergroundBiome.SALTVEIL_CHAMBERS && !ctx.evaporite()) {
                                throw new AssertionError("Non-evaporite Saltveil cave");
                            }
                            if (biome == UndergroundBiome.CINDERVEIN_TUBES && !ctx.volcanic()) {
                                throw new AssertionError("Non-volcanic Cindervein cave");
                            }
                        }
                        if (carve) seenCaves++;
                    }
                }
            }
        }
        if (seenCaves == 0 || classified == 0) {
            throw new AssertionError("No usable cave-biome samples found");
        }

        System.out.printf("Underground worldgen passed: 12 cave profiles, 33 unique new textures, "
                + "%d classified cave samples from %d cave-air samples; %d biome types sampled%n",
                classified, seenCaves, observed.size());
    }
}
