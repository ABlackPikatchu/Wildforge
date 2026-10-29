package com.wildforge.worldgen;

import java.util.EnumSet;

/** Run by Gradle with generated JSON profiles and the real Gson dependency. */
public final class MultiNoiseWorldgenPhase4SmokeTest {
    public static void main(String[] args) throws Exception {
        WorldGenRegistry registry=WorldGenRegistry.loadBuiltIn();
        long seed=20261029L;
        WorldGenerator revised=registry.generator(seed);
        if(revised.generationRevision()!=WorldGenerator.CURRENT_REVISION
                ||revised.settings().minY()!=0||revised.settings().maxY()!=511)
            throw new AssertionError("Current-world revision/height mismatch: expected revision "
                    +WorldGenerator.CURRENT_REVISION+" and Y 0..511, got revision "
                    +revised.generationRevision()+" and Y "
                    +revised.settings().minY()+".."+revised.settings().maxY());
        try {
            new WorldGenerator(seed,revised.settings(),WorldGenerator.CURRENT_REVISION-1);
            throw new AssertionError("Previous generation revision was accepted");
        }
        catch(IllegalArgumentException expected) {}
        EnumSet<Biome> found=EnumSet.noneOf(Biome.class);
        int ocean=0,land=0;
        for(int x=-4800;x<=4800;x+=140)for(int z=-4800;z<=4800;z+=140) {
            WorldGenerator.Surface s=revised.surface(x,z);
            if(s.surfaceY()<0||s.surfaceY()>511)throw new AssertionError("Terrain escaped world");
            if(revised.biomes().get(s.biome())==null)throw new AssertionError("Unknown surface biome");
            if(s.waterBody()==WorldGenerator.WaterBody.OCEAN)ocean++;else land++;
            found.add(s.biome());
        }
        if(found.size()<29||ocean<100||land<100)
            throw new AssertionError("Insufficient climate/biome coverage "+found.size()+", oceans="+ocean);
        for(int x=-500;x<=500;x+=71)for(int z=-500;z<=500;z+=83) {
            if(!revised.surface(x,z).equals(revised.surface(x,z)))throw new AssertionError("Non-deterministic world");
        }
        System.out.println("Multi-noise biome spread and current revision "
                +WorldGenerator.CURRENT_REVISION+" passed: "+found.size()+" biomes");
    }
}
